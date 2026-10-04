package dev.framegen

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class FrameGenAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var current: FrameGenAccessibilityService? = null

        fun isEnabled(): Boolean = current != null

        fun showFrameGenOverlay(
            projection: MediaProjection,
            multiplier: Int,
            width: Int,
            height: Int,
            dpi: Int,
            refreshHz: Float,
            modelDirectory: String,
            aiWarmupMs: Long
        ): Boolean {
            val service = current ?: return false
            service.showOverlayInternal(
                projection,
                multiplier,
                width,
                height,
                dpi,
                refreshHz,
                modelDirectory,
                aiWarmupMs
            )
            return true
        }

        fun hideFrameGenOverlay() {
            current?.hideOverlayInternal()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: FrameLayout? = null
    private var overlaySurface: SurfaceView? = null
    private var renderer: FrameGenRenderer? = null
    private var fpsText: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No accessibility actions are performed. The service exists only to
        // host a trusted, pass-through rendering overlay for FrameGen.
    }

    override fun onInterrupt() = Unit

    private fun showOverlayInternal(
        projection: MediaProjection,
        multiplier: Int,
        width: Int,
        height: Int,
        dpi: Int,
        refreshHz: Float,
        modelDirectory: String,
        aiWarmupMs: Long
    ) {
        main.post {
            hideOverlayInternal()

            val (cw, ch) = scaledDims(width, height, 0.67f)

            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
            }

            val surface = SurfaceView(this).apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                setZOrderMediaOverlay(true)
                holder.setFixedSize(cw, ch)
            }

            val stats = TextView(this).apply {
                text = "FG —  •  IN —  •  AI WARMUP"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(14, 8, 14, 8)
                setBackgroundColor(Color.argb(190, 5, 12, 24))
            }

            val statsParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = 18
                leftMargin = 18
            }

            container.addView(
                surface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            container.addView(stats, statsParams)

            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (renderer != null) return

                    renderer = FrameGenRenderer(
                        holder.surface,
                        projection,
                        multiplier,
                        cw,
                        ch,
                        dpi,
                        refreshHz,
                        modelDirectory,
                        aiWarmupMs,
                        { nw, nh ->
                            main.post {
                                overlaySurface?.holder?.setFixedSize(nw, nh)
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

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    w: Int,
                    h: Int
                ) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    renderer?.shutdown()
                    renderer = null
                }
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )

            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

            overlay = container
            overlaySurface = surface
            fpsText = stats

            try {
                wm.addView(container, params)
            } catch (t: Throwable) {
                renderer?.shutdown()
                renderer = null
                overlay = null
                overlaySurface = null
                fpsText = null
            }
        }
    }

    private fun hideOverlayInternal() {
        renderer?.shutdown()
        renderer = null

        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }

        overlay = null
        overlaySurface = null
        fpsText = null
    }

    override fun onDestroy() {
        hideOverlayInternal()
        if (current === this) current = null
        super.onDestroy()
    }
}
