package dev.framegen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 42
    private var mult = 2
    private var quality = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 72, 56, 56)
        }

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, 18, 0, 8)
        }

        root.addView(label("FrameGen — Game Mode", 28f))
        root.addView(label(
            "Real-time frame interpolation for Android 14+ games.\n\n" +
            "1. Choose your frame multiplier.\n" +
            "2. Choose processing quality.\n" +
            "3. Press APPLY & START FRAMEGEN.\n" +
            "4. In Android's capture dialog, choose \"A single app\" and select your game."
        ))

        root.addView(label("Frame multiplier"))
        val multGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((i, value) in listOf(2, 3, 4).withIndex()) {
            multGroup.addView(RadioButton(this).apply {
                text = value.toString() + "X"
                id = 100 + i
                isChecked = (value == 2)
            })
        }
        multGroup.setOnCheckedChangeListener { _, id ->
            if (id in 100..102) mult = listOf(2, 3, 4)[id - 100]
        }
        root.addView(multGroup)

        root.addView(label("Processing resolution"))
        val qGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val qs = listOf("Fast" to 0.5f, "Balanced" to 0.67f, "Sharp" to 1.0f)
        for ((i, q) in qs.withIndex()) {
            qGroup.addView(RadioButton(this).apply {
                text = q.first
                id = 200 + i
                isChecked = (i == 0)
            })
        }
        qGroup.setOnCheckedChangeListener { _, id ->
            if (id in 200..202) quality = qs[id - 200].second
        }
        root.addView(qGroup)

        root.addView(Button(this).apply {
            text = "APPLY & START FRAMEGEN"
            setOnClickListener { startFrameGen() }
        })

        root.addView(Button(this).apply {
            text = "STOP FRAMEGEN"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, FrameGenService::class.java)
                        .setAction(FrameGenService.ACTION_STOP)
                )
            }
        })

        root.addView(label(
            "Compatibility: Vulkan/OpenGL ES games that Android allows MediaProjection to capture. " +
            "Games using secure/protected rendering may not be capturable.",
            12f
        ))

        root.gravity = Gravity.TOP
        setContentView(root)
    }

    private fun startFrameGen() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow Display over other apps for FrameGen, then press APPLY & START again.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, FrameGenService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("mult", mult)
                .putExtra("quality", quality)

            startForegroundService(serviceIntent)
            Toast.makeText(this, "FrameGen starting…", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }
    }
}
