package dev.framegen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

data class GameEntry(
    val label: String,
    val packageName: String
)

class MainActivity : Activity() {
    private val reqCapture = 42
    private var mult = 2
    private var quality = 0.5f
    private var selectedGame: GameEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, 16, 0, 8)
        }

        root.addView(label("FrameGen — Choose Game", 28f))
        root.addView(label(
            "Select the game first. FrameGen will only start after the selected game is opened. " +
            "After Android capture permission is approved, the game gets launched and FrameGen waits 10 seconds before processing."
        ))

        root.addView(label("1. Choose your game"))
        val games = findLaunchableGames()
        if (games.isEmpty()) {
            root.addView(label("No launchable games/apps were found on this device.", 13f))
        } else {
            val spinner = Spinner(this)
            val names = games.map { it.label }
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
            spinner.setSelection(0)
            selectedGame = games[0]
            spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    selectedGame = games.getOrNull(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    selectedGame = null
                }
            })
            root.addView(spinner)
            root.addView(label(
                "Selected package: " + (selectedGame?.packageName ?: "none"),
                11f
            ))
        }

        root.addView(label("2. Frame multiplier"))
        val multSpinner = Spinner(this)
        multSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("2X — recommended", "3X", "4X")
        )
        multSpinner.setSelection(0)
        multSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                mult = position + 2
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        root.addView(multSpinner)

        root.addView(label("3. Processing resolution"))
        val qSpinner = Spinner(this)
        qSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Fast — 50%", "Balanced — 67%", "Sharp — 100%")
        )
        qSpinner.setSelection(0)
        qSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                quality = listOf(0.5f, 0.67f, 1.0f)[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        root.addView(qSpinner)

        root.addView(Button(this).apply {
            text = "APPLY & START FRAMEGEN"
            setOnClickListener { applyAndStart() }
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
            "Recommended flow: select the game → Apply & Start → allow overlay → allow Android screen capture → " +
            "the selected game opens → 10-second warm-up → FrameGen starts."
        , 12f))

        root.gravity = Gravity.TOP
        setContentView(root)
    }

    private fun findLaunchableGames(): List<GameEntry> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_ALL
        )

        val entries = resolved
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map {
                GameEntry(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                ) to (it.category == ApplicationInfo.CATEGORY_GAME)
            }
            .sortedWith(
                compareByDescending<Pair<GameEntry, Boolean>> { it.second }
                    .thenBy { it.first.label.lowercase() }
            )
            .map { it.first }

        return entries
    }

    private fun applyAndStart() {
        val game = selectedGame
        if (game == null) {
            Toast.makeText(this, "Choose a game first.", Toast.LENGTH_LONG).show()
            return
        }

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

        // Android requires user consent for every MediaProjection capture session.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    private fun launchGameThenStartCaptureService(
        resultCode: Int,
        data: Intent,
        game: GameEntry
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(game.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Could not launch " + game.label, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(
            this,
            game.label + " starting — FrameGen will begin after 10 seconds.",
            Toast.LENGTH_LONG
        ).show()

        // Open the game immediately after capture permission.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Start the foreground service immediately, but keep the actual capture/
        // interpolation pipeline stopped for the requested 10-second warm-up.
        val serviceIntent = Intent(this, FrameGenService::class.java)
            .putExtra("code", resultCode)
            .putExtra("data", data)
            .putExtra("mult", mult)
            .putExtra("quality", quality)
            .putExtra("delayMs", 10_000L)
            .putExtra("gameName", game.label)

        startForegroundService(serviceIntent)
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val game = selectedGame ?: return
            launchGameThenStartCaptureService(resultCode, data, game)
        }
    }
}
