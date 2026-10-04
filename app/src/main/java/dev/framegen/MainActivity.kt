package dev.framegen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var quality = 0.67f
    private var selectedGame: GameEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, 12, 0, 8)
        }

        root.addView(label("FrameGen — Neural AI", 27f))
        root.addView(label(
            "Choose a game, apply your settings, then press START FRAMEGEN. " +
            "The selected game opens, Android asks you to choose its window, and the AI starts after the 10-second warm-up.",
            14f
        ))

        root.addView(label("1. CHOOSE GAME", 16f))
        val games = findLaunchableGames()

        if (games.isEmpty()) {
            root.addView(label("No launchable games/apps were found on this device.", 13f))
        } else {
            val spinner = Spinner(this).apply {
                minimumHeight = 56
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    games.map { it.label }
                )
                setSelection(0)
            }

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
        }

        // Prominent start button: kept immediately after the game picker so it
        // remains visible on small screens and is impossible to miss.
        val startButton = Button(this).apply {
            text = "START FRAMEGEN"
            textSize = 18f
            minHeight = 72
            setOnClickListener { applyAndStart() }
        }
        root.addView(startButton)

        root.addView(label("2. FRAME MULTIPLIER", 16f))
        val multSpinner = Spinner(this).apply {
            minimumHeight = 56
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("2X — Recommended", "3X", "4X")
            )
            setSelection(0)
        }
        multSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                mult = position + 2
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        root.addView(multSpinner)

        root.addView(label("3. AI PROCESSING QUALITY", 16f))
        val qSpinner = Spinner(this).apply {
            minimumHeight = 56
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Fast", "Balanced", "Sharp")
            )
            setSelection(1)
        }
        qSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                quality = listOf(0.5f, 0.67f, 1.0f)[position]
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        root.addView(qSpinner)

        root.addView(Button(this).apply {
            text = "APPLY SETTINGS"
            minHeight = 60
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Settings applied for " + (selectedGame?.label ?: "selected game"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        root.addView(Button(this).apply {
            text = "STOP FRAMEGEN"
            minHeight = 60
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, FrameGenService::class.java)
                        .setAction(FrameGenService.ACTION_STOP)
                )
            }
        })

        root.addView(label(
            "Flow: choose game → START FRAMEGEN → Android capture permission → " +
            "game window → 10-second AI warm-up → RIFE neural frame generation.",
            12f
        ))

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
                "Allow Display over other apps for FrameGen, then press START FRAMEGEN again.",
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

        // Android 14+ supports a user-selected app window. Launch the game
        // first, then let the system capture picker target that game window.
        val launchIntent = packageManager.getLaunchIntentForPackage(game.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Could not launch " + game.label, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(
            this,
            game.label + " starting — choose its window in the capture dialog.",
            Toast.LENGTH_LONG
        ).show()

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        Handler(Looper.getMainLooper()).postDelayed(
            { requestGameCapture() },
            2000L
        )
    }

    private fun requestGameCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val config = MediaProjectionConfig.createConfigForUserChoice()
        startActivityForResult(
            mpm.createScreenCaptureIntent(config),
            reqCapture
        )
    }

    private fun startCaptureService(
        resultCode: Int,
        data: Intent,
        game: GameEntry
    ) {
        val serviceIntent = Intent(this, FrameGenService::class.java)
            .putExtra("code", resultCode)
            .putExtra("data", data)
            .putExtra("mult", mult)
            .putExtra("quality", quality)
            .putExtra("delayMs", 10_000L)
            .putExtra("gameName", game.label)

        startForegroundService(serviceIntent)
        Toast.makeText(
            this,
            "Capture connected. FrameGen will warm up for 10 seconds, then generate AI frames.",
            Toast.LENGTH_LONG
        ).show()
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val game = selectedGame ?: return
                startCaptureService(resultCode, data, game)
            } else {
                Toast.makeText(this, "Screen capture was cancelled. FrameGen did not start.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
