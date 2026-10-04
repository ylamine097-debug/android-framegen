package dev.framegen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class CaptureActivity : Activity() {

    companion object {
        const val EXTRA_GAME_NAME = "gameName"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val REQUEST_CAPTURE = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent foreground Activity: Android's consent UI remains attached
        // to a visible Activity while the selected game stays behind it.
        window.setDimAmount(0f)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        android.os.Handler(mainLooper).post {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val config = MediaProjectionConfig.createConfigForUserChoice()
        startActivityForResult(
            manager.createScreenCaptureIntent(config),
            REQUEST_CAPTURE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return

        val result = Intent()
        result.putExtra(EXTRA_RESULT_CODE, resultCode)

        if (data != null) {
            result.putExtra(EXTRA_RESULT_DATA, data)
        }

        setResult(resultCode, result)
        finish()
    }
}
