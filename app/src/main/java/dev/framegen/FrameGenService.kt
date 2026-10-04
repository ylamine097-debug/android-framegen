package dev.framegen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast

fun scaledDims(w: Int, h: Int, q: Float): Pair<Int, Int> =
    Pair(((w * q).toInt() / 16 * 16).coerceAtLeast(64), ((h * q).toInt() / 16 * 16).coerceAtLeast(64))

class FrameGenService : Service() {
    companion object { const val ACTION_STOP = "dev.framegen.STOP" }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var renderer: FrameGenRenderer? = null
    private var overlay: SurfaceView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var quality = 0.5f
    private var mult = 2

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            val (w, h) = scaledDims(width, height, quality)
            renderer?.requestResize(w, h)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            // hide the overlay while the shared app is not on screen
            val p = lp ?: return
            val v = overlay ?: return
            p.alpha = if (isVisible) 1f else 0f
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        mult = intent.getIntExtra("mult", 2).coerceIn(2, 4)
        quality = intent.getFloatExtra("quality", 0.5f)
        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra("data", Intent::class.java)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(callback, main)   // must be registered before createVirtualDisplay on Android 14+
            setupOverlay(mp)
        } catch (e: Exception) {
            Toast.makeText(this, "FrameGen failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupOverlay(mp: MediaProjection) {
        val b = wm.maximumWindowMetrics.bounds
        val (cw, ch) = scaledDims(b.width(), b.height(), quality)
        val dpi = resources.displayMetrics.densityDpi

        val sv = SurfaceView(this)
        sv.holder.setFixedSize(cw, ch)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (renderer == null) {
                    renderer = FrameGenRenderer(holder.surface, mp, mult, cw, ch, dpi) { w, h ->
                        main.post { overlay?.holder?.setFixedSize(w, h) }
                    }.also { it.start() }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { stopSelf() }
        })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp = p
        overlay = sv
        wm.addView(sv, p)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fg", "FrameGen", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, FrameGenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stopPi
        ).build()
        return Notification.Builder(this, "fg")
            .setContentTitle("FrameGen is running")
            .setContentText("${mult}X frame generation active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        renderer?.shutdown(); renderer = null
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        projection?.let { try { it.unregisterCallback(callback); it.stop() } catch (_: Exception) {} }
        projection = null
        super.onDestroy()
    }
}
