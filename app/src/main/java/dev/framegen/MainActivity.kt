package dev.framegen

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private val bg = Color.rgb(7, 13, 24)
    private val card = Color.rgb(14, 25, 43)
    private val cardAlt = Color.rgb(18, 32, 53)
    private val line = Color.rgb(38, 57, 82)
    private val uiText = Color.rgb(244, 248, 255)
    private val muted = Color.rgb(142, 160, 187)
    private val purple = Color.rgb(124, 92, 255)
    private val blue = Color.rgb(76, 126, 255)
    private val cyan = Color.rgb(70, 217, 255)
    private val green = Color.rgb(53, 229, 162)
    private val red = Color.rgb(255, 100, 124)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        fill: Int,
        stroke: Int? = null,
        radius: Int = 18
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun gradientButton(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(blue, purple)
        ).apply {
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun label(
        value: String,
        size: Float = 14f,
        color: Int = uiText,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = true
        }
    }

    private fun addMargin(view: View, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) {
        val p = view.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(-1, -2)
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom))
        view.layoutParams = p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = Color.rgb(4, 8, 15)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            setBackgroundColor(bg)
        }
        scroll.addView(root)

        // Header / brand
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "FG"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(purple, null, 14)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleBox.addView(label("FRAMEGEN", 22f, uiText, true))
        titleBox.addView(label("NEURAL AI FRAME GENERATION", 10f, cyan, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val status = TextView(this).apply {
            text = "● READY"
            textSize = 10f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Color.rgb(12, 49, 41), Color.rgb(26, 94, 78), 20)
        }
        header.addView(status)

        root.addView(header)
        addMargin(header, bottom = 18)

        // Hero card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = rounded(card, line, 20)
        }

        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(label("GAME FRAME GENERATION", 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val local = label("ON-DEVICE", 10f, cyan, true).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(Color.rgb(9, 45, 57), Color.rgb(28, 91, 106), 16)
        }
        heroTop.addView(local)
        hero.addView(heroTop)

        hero.addView(label(
            "RIFE v4.6 neural interpolation • Vulkan / NCNN • ARM64",
            12f, text, false
        ).also { addMargin(it, top = 7, bottom = 2) })

        hero.addView(label(
            "Designed for modern Adreno and Mali devices.",
            11f, muted, false
        ))

        root.addView(hero)
        addMargin(hero, bottom = 16)

        // Game section
        val gameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(card, line, 20)
        }

        gameCard.addView(label("1  •  CHOOSE GAME", 13f, text, true))
        gameCard.addView(label(
            "Pick the game before starting FrameGen.",
            11f, muted
        ).also { addMargin(it, top = 3, bottom = 10) })

        val games = findLaunchableGames()
        val gameSpinner = Spinner(this).apply {
            minimumHeight = dp(54)
            background = rounded(cardAlt, line, 14)
            setPadding(dp(12), 0, dp(10), 0)
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                games.map { it.label }
            )
        }

        if (games.isEmpty()) {
            gameSpinner.isEnabled = false
            gameCard.addView(label("No launchable games/apps found.", 13f, red))
        } else {
            selectedGame = games[0]
            gameSpinner.setSelection(0)
            gameSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    selectedGame = games.getOrNull(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectedGame = null
                }
            })
            gameCard.addView(gameSpinner)
            gameCard.addView(label(
                "Selected: " + (selectedGame?.packageName ?: "none"),
                10f, muted
            ).also { addMargin(it, top = 8) })
        }

        root.addView(gameCard)
        addMargin(gameCard, bottom = 14)

        // Big start button — deliberately near the top.
        val startButton = Button(this).apply {
            text = "START FRAMEGEN"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(72)
            background = gradientButton()
            elevation = dp(5).toFloat()
            stateListAnimator = null
            setOnClickListener { applyAndStart() }
        }
        root.addView(startButton)
        addMargin(startButton, bottom = 16)

        // Settings section
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(card, line, 20)
        }

        settingsCard.addView(label("2  •  FRAME GENERATION SETTINGS", 13f, text, true))

        val multTitle = label("Frame multiplier", 11f, muted, true)
        settingsCard.addView(multTitle)
        addMargin(multTitle, top = 11, bottom = 5)

        val multSpinner = Spinner(this).apply {
            minimumHeight = dp(52)
            background = rounded(cardAlt, line, 14)
            setPadding(dp(12), 0, dp(10), 0)
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("2X  •  Recommended", "3X", "4X")
            )
            setSelection(0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    mult = position + 2
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        settingsCard.addView(multSpinner)

        val qualityTitle = label("AI processing quality", 11f, muted, true)
        settingsCard.addView(qualityTitle)
        addMargin(qualityTitle, top = 12, bottom = 5)

        val qSpinner = Spinner(this).apply {
            minimumHeight = dp(52)
            background = rounded(cardAlt, line, 14)
            setPadding(dp(12), 0, dp(10), 0)
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Fast", "Balanced", "Sharp")
            )
            setSelection(1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    quality = listOf(0.5f, 0.67f, 1.0f)[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        settingsCard.addView(qSpinner)

        root.addView(settingsCard)
        addMargin(settingsCard, bottom = 14)

        // Apply / stop controls
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val apply = Button(this).apply {
            text = "APPLY SETTINGS"
            textSize = 13f
            setTextColor(uiText)
            minHeight = dp(54)
            background = rounded(cardAlt, line, 15)
            stateListAnimator = null
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Settings applied for " + (selectedGame?.label ?: "selected game"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        actions.addView(apply, LinearLayout.LayoutParams(0, dp(54), 1f))

        val stop = Button(this).apply {
            text = "STOP"
            textSize = 13f
            setTextColor(red)
            minHeight = dp(54)
            background = rounded(Color.rgb(45, 18, 28), Color.rgb(96, 42, 56), 15)
            stateListAnimator = null
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, FrameGenService::class.java)
                        .setAction(FrameGenService.ACTION_STOP)
                )
            }
        }
        val stopParams = LinearLayout.LayoutParams(0, dp(54), 0.55f)
        stopParams.leftMargin = dp(9)
        actions.addView(stop, stopParams)

        root.addView(actions)
        addMargin(actions, bottom = 14)

        // Info card
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(Color.rgb(9, 20, 34), Color.rgb(26, 47, 72), 18)
        }
        info.addView(label("HOW IT WORKS", 10f, cyan, true))
        info.addView(label(
            "Select game → Start FrameGen → choose the game window in Android's capture dialog → " +
            "10-second warm-up → RIFE neural frame generation.",
            11f, muted
        ).also { addMargin(it, top = 6) })

        root.addView(info)

        setContentView(scroll)
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

        return resolved
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
                Toast.makeText(
                    this,
                    "Screen capture was cancelled. FrameGen did not start.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
