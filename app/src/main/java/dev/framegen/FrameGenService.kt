package dev.framegen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private var projection: MediaProjection? = null
    private var modelDirectory: String? = null
    private var captureCode = 0
    private var captureData: Intent? = null

    private var quality = 0.67f
    private var mult = 2
    private var gameName = "selected game"
    private var aiWarmupMs = DEFAULT_DELAY_MS

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            FrameGenAccessibilityService.hideFrameGenOverlay()
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // The renderer handles the VirtualDisplay resize.
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            // When Android temporarily hides the captured game (for example
            // behind system UI), hide/pause only the visual overlay. Keep the
            // MediaProjection service alive so FrameGen can resume when the
            // game becomes visible again.
            FrameGenAccessibilityService.setFrameGenOverlayVisible(isVisible)
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null) {
            return START_NOT_STICKY
        }

        captureCode = intent.getIntExtra("code", 0)
        captureData = intent.getParcelableExtra("data", Intent::class.java)
        mult = intent.getIntExtra("mult", 2).coerceIn(2, 4)
        quality = intent.getFloatExtra("quality", 0.67f)
        gameName = intent.getStringExtra("gameName") ?: "selected game"
        aiWarmupMs = intent
            .getLongExtra("delayMs", DEFAULT_DELAY_MS)
            .coerceIn(0L, 30_000L)

        if (captureData == null) {
            Toast.makeText(
                this,
                "Game capture token is missing. Please authorize capture again.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            1,
            buildNotification("Starting capture • " + gameName),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // Use the one-time MediaProjection token immediately after consent.
        // Android 14+ requires consent for each capture session and each
        // MediaProjection instance may only create its capture display once.
        beginCapture()

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

            val modelDir = prepareRifeModel()
            if (modelDir == null) {
                Toast.makeText(
                    this,
                    "RIFE AI model is missing from the APK.",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }

            modelDirectory = modelDir

            // Android 14 requires a callback on the MediaProjection before
            // the VirtualDisplay is created.
            mp.registerCallback(projectionCallback, main)

            val bounds = getSystemService(android.view.WindowManager::class.java)
                .maximumWindowMetrics.bounds

            val (cw, ch) = scaledDims(bounds.width(), bounds.height(), quality)

            val dpi = resources.displayMetrics.densityDpi

            val refreshHz = getSystemService(DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.refreshRate
                ?.coerceAtLeast(60f)
                ?: 60f

            // AccessibilityService hosts the trusted full-screen overlay so
            // Android can safely pass touch input through to the game.
            if (!FrameGenAccessibilityService.showFrameGenOverlay(
                    mp,
                    mult,
                    cw,
                    ch,
                    dpi,
                    refreshHz,
                    modelDir,
                    aiWarmupMs
                )
            ) {
                Toast.makeText(
                    this,
                    "FrameGen Touch Bridge is not enabled. Enable it in Accessibility settings first.",
                    Toast.LENGTH_LONG
                ).show()
                try { mp.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
                stopSelf()
                return
            }

            updateNotification("RIFE AI FrameGen active • " + gameName)
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Android denied the capture session. Authorize game capture again.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "FrameGen failed: " + (t.message ?: "unknown error"),
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
        } catch (_: Throwable) {
            null
        }
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
            android.graphics.drawable.Icon.createWithResource(
                this,
                android.R.drawable.ic_delete
            ),
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
        FrameGenAccessibilityService.hideFrameGenOverlay()

        projection?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
        }

        projection = null
        captureData = null
        modelDirectory = null
        super.onDestroy()
    }
}
