package dev.framegen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

fun scaledDims(w: Int, h: Int, q: Float): Pair<Int, Int> {
    // Bound neural inference to practical flagship-mobile budgets.
    val maxPixels = when {
        q <= 0.5f -> 1280L * 720L
        q <= 0.67f -> 1600L * 900L
        else -> 1920L * 1080L
    }
    val sourcePixels = w.toLong() * h.toLong()
    val scale = minOf(
        q.toDouble(),
        kotlin.math.sqrt(maxPixels.toDouble() / sourcePixels.coerceAtLeast(1L))
    )
    val nw = ((w * scale).toInt() / 16 * 16).coerceAtLeast(64)
    val nh = ((h * scale).toInt() / 16 * 16).coerceAtLeast(64)
    return Pair(nw, nh)
}

class FrameGenService : Service() {
    companion object {
        const val ACTION_STOP = "dev.framegen.STOP"
        private const val DEFAULT_DELAY_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var renderer: FrameGenRenderer? = null
    private var overlay: FrameLayout? = null
    private var overlaySurface: SurfaceView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var fpsText: TextView? = null

    private var quality = 0.67f
    private var mult = 2
    private var gameName = "selected game"
    private var modelDirectory: String? = null

    private var captureCode = 0
    private var captureData: Intent? = null
    private var startScheduled = false
    private var delayEndAt = 0L

    private val delayedStart = object : Runnable {
        override fun run() {
            if (projection != null || !startScheduled) return

            val remaining = delayEndAt - System.currentTimeMillis()
            if (remaining > 0L) {
                val seconds = (remaining + 999L) / 1000L
                updateNotification("Starting FrameGen in " + seconds + "s • " + gameName)
                main.postDelayed(this, 250L)
            } else {
                startScheduled = false
                updateNotification("FrameGen starting • " + gameName)
                beginCapture()
            }
        }
    }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            val (w, h) = scaledDims(width, height, quality)
            renderer?.requestResize(w, h)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            // Intentionally ignored. Our overlay covers the selected game window,
            // so Android can report the source as occluded even while capture is
            // healthy. Toggling or hiding the overlay here causes flicker.
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            main.removeCallbacks(delayedStart)
            startScheduled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null || startScheduled) return START_NOT_STICKY

        captureCode = intent.getIntExtra("code", 0)
        captureData = intent.getParcelableExtra("data", Intent::class.java)
        mult = intent.getIntExtra("mult", 2).coerceIn(2, 4)
        quality = intent.getFloatExtra("quality", 0.67f)
        gameName = intent.getStringExtra("gameName") ?: "selected game"

        if (captureData == null) {
            Toast.makeText(this, "FrameGen capture data is missing.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val delayMs = intent
            .getLongExtra("delayMs", DEFAULT_DELAY_MS)
            .coerceIn(0L, 30_000L)

        startForeground(
            1,
            buildNotification("Preparing " + gameName + "…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        delayEndAt = System.currentTimeMillis() + delayMs
        startScheduled = true
        main.post(delayedStart)

        return START_NOT_STICKY
    }

    private fun beginCapture() {
        val data = captureData ?: run {
            stopSelf()
            return
        }

        try {
            val mp = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(captureCode, data)

            projection = mp
            mp.registerCallback(callback, main)

            val modelDir = prepareRifeModel()
            if (modelDir == null) {
                Toast.makeText(
                    this,
                    "AI model files are missing. Rebuild the APK with the bundled RIFE model.",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }

            modelDirectory = modelDir
            setupOverlay(mp)
            updateNotification("RIFE AI FrameGen active • " + gameName)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "FrameGen failed: " + (e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun prepareRifeModel(): String? {
        return try {
            val outDir = java.io.File(filesDir, "models/rife-v4.6")
            outDir.mkdirs()

            val files = arrayOf("flownet.param", "flownet.bin")
            for (name in files) {
                val out = java.io.File(outDir, name)
                if (!out.exists() || out.length() == 0L) {
                    assets.open("models/rife-v4.6/" + name).use { input ->
                        out.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            if (files.all { java.io.File(outDir, it).exists() }) {
                outDir.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not prepare AI model: " + (e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
            null
        }
    }

    private fun setupOverlay(mp: MediaProjection) {
        val b = wm.maximumWindowMetrics.bounds
        val (cw, ch) = scaledDims(b.width(), b.height(), quality)
        val dpi = resources.displayMetrics.densityDpi

        val refreshHz = getSystemService(DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.refreshRate
            ?.coerceAtLeast(60f)
            ?: 60f

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val sv = SurfaceView(this).apply {
            // Keep the text HUD above the rendering surface.
            setZOrderMediaOverlay(true)
            holder.setFixedSize(cw, ch)
        }

        val stats = TextView(this).apply {
            text = "FG 0 FPS  •  IN 0 FPS  •  AI 0 FPS"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(14, 8, 14, 8)
            setBackgroundColor(Color.argb(190, 5, 12, 24))
        }

        val statsLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 18
            leftMargin = 18
        }

        container.addView(
            sv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(stats, statsLp)

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (renderer == null) {
                    renderer = FrameGenRenderer(
                        holder.surface,
                        mp,
                        mult,
                        cw,
                        ch,
                        dpi,
                        refreshHz,
                        modelDirectory ?: "",
                        { w, h ->
                            main.post {
                                overlaySurface?.holder?.setFixedSize(w, h)
                            }
                        },
                        { inputFps, outputFps, aiFps ->
                            main.post {
                                fpsText?.text =
                                    "FG " + outputFps + " FPS  •  IN " +
                                        inputFps + " FPS  •  AI " + aiFps + " FPS"
                            }
                        }
                    ).also { it.start() }
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopSelf()
            }
        })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        p.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        lp = p
        overlay = container
        overlaySurface = sv
        fpsText = stats
        wm.addView(container, p)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                "fg",
                "FrameGen",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val stopPi = PendingIntent.getService(
            this,
            0,
            Intent(this, FrameGenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete),
            "Stop",
            stopPi
        ).build()

        return Notification.Builder(this, "fg")
            .setContentTitle("FrameGen")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        main.removeCallbacks(delayedStart)
        startScheduled = false

        renderer?.shutdown()
        renderer = null

        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlay = null
        overlaySurface = null
        fpsText = null

        projection?.let {
            try {
                it.unregisterCallback(callback)
                it.stop()
            } catch (_: Exception) {
            }
        }

        projection = null
        captureData = null
        super.onDestroy()
    }
}
