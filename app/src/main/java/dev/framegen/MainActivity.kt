package dev.framegen

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
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

    private var captureCode: Int? = null
    private var captureData: Intent? = null
    private var captureReady = false

    private var startButton: Button? = null
    private var touchBridgeStatus: TextView? = null
    private var notificationStatus: TextView? = null
    private var captureStatus: TextView? = null
    private var prepareCaptureButton: Button? = null
    private var selectedPackageText: TextView? = null

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

    private fun addMargin(
        view: View,
        top: Int = 0,
        bottom: Int = 0,
        left: Int = 0,
        right: Int = 0
    ) {
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

        val status = label("● SETUP", 10f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Color.rgb(9, 45, 57), Color.rgb(28, 91, 106), 20)
        }
        header.addView(status)

        root.addView(header)
        addMargin(header, bottom = 18)

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
        heroTop.addView(label("ON-DEVICE", 10f, cyan, true).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(Color.rgb(9, 45, 57), Color.rgb(28, 91, 106), 16)
        })
        hero.addView(heroTop)
        hero.addView(label(
            "RIFE v4.6 neural interpolation • Vulkan / NCNN • ARM64",
            12f, uiText
        ).also { addMargin(it, top = 7, bottom = 2) })
        hero.addView(label(
            "Optimized for modern Adreno and Mali-class devices.",
            11f, muted
        ))
        root.addView(hero)
        addMargin(hero, bottom = 16)

        val gameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(card, line, 20)
        }
        gameCard.addView(label("1  •  CHOOSE GAME", 13f, uiText, true))
        gameCard.addView(label(
            "Games are listed first; Android game flags and additional game candidates are detected automatically.",
            11f, muted
        ).also { addMargin(it, top = 3, bottom = 10) })

        val games = findLaunchableGames()
        if (games.isEmpty()) {
            gameCard.addView(label(
                "No game candidates detected on this device. Some games may not expose any game metadata to Android.",
                12f, red
            ))
        } else {
            val gameSpinner = Spinner(this).apply {
                minimumHeight = dp(54)
                background = rounded(cardAlt, line, 14)
                setPadding(dp(12), 0, dp(10), 0)
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    games.map { it.label }
                )
                setSelection(0)
            }
            selectedGame = games[0]
            gameSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedGame = games.getOrNull(position)
                    selectedPackageText?.text =
                        "Selected: " + (selectedGame?.packageName ?: "none")
                    refreshReadiness()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectedGame = null
                    refreshReadiness()
                }
            })
            gameCard.addView(gameSpinner)
            selectedPackageText = label(
                "Selected: " + selectedGame?.packageName,
                10f, muted
            )
            gameCard.addView(selectedPackageText)
            addMargin(selectedPackageText!!, top = 8)
        }

        root.addView(gameCard)
        addMargin(gameCard, bottom = 14)

        val permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(card, line, 20)
        }
        permissionCard.addView(label("2  •  PERMISSIONS & CAPTURE", 13f, uiText, true))
        permissionCard.addView(label(
            "Enable the Touch Bridge + notifications, then authorize game-window capture before START FRAMEGEN unlocks.",
            11f, muted
        ).also { addMargin(it, top = 3, bottom = 8) })

        touchBridgeStatus = label("", 12f)
        notificationStatus = label("", 12f)
        captureStatus = label("", 12f)
        permissionCard.addView(touchBridgeStatus)
        permissionCard.addView(notificationStatus)
        permissionCard.addView(captureStatus)

        val enableTouchBridge = Button(this).apply {
            text = "ENABLE TOUCH BRIDGE"
            textSize = 12f
            minHeight = dp(50)
            setTextColor(uiText)
            background = rounded(cardAlt, line, 15)
            stateListAnimator = null
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        permissionCard.addView(enableTouchBridge)
        addMargin(enableTouchBridge, top = 7)

        val enableNotifications = Button(this).apply {
            text = "ENABLE NOTIFICATIONS"
            textSize = 12f
            minHeight = dp(50)
            setTextColor(uiText)
            background = rounded(cardAlt, line, 15)
            stateListAnimator = null
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                }
            }
        }
        permissionCard.addView(enableNotifications)
        addMargin(enableNotifications, top = 7)

        prepareCaptureButton = Button(this).apply {
            text = "AUTHORIZE GAME CAPTURE"
            textSize = 12f
            minHeight = dp(54)
            setTextColor(uiText)
            background = rounded(cardAlt, line, 15)
            stateListAnimator = null
            setOnClickListener { prepareGameCapture() }
        }
        permissionCard.addView(prepareCaptureButton)
        addMargin(prepareCaptureButton!!, top = 7)

        root.addView(permissionCard)
        addMargin(permissionCard, bottom = 14)

        startButton = Button(this).apply {
            text = "START FRAMEGEN"
            textSize = 19f
            minHeight = dp(78)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = gradientButton()
            elevation = dp(6).toFloat()
            stateListAnimator = null
            setOnClickListener { startFrameGen() }
        }
        root.addView(startButton)
        addMargin(startButton!!, bottom = 16)

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(card, line, 20)
        }
        settingsCard.addView(label("3  •  FRAME GENERATION SETTINGS", 13f, uiText, true))

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
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    quality = listOf(0.5f, 0.67f, 1.0f)[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        settingsCard.addView(qSpinner)
        root.addView(settingsCard)
        addMargin(settingsCard, bottom = 14)

        val apply = Button(this).apply {
            text = "APPLY SETTINGS"
            textSize = 13f
            minHeight = dp(54)
            setTextColor(uiText)
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
        root.addView(apply)
        addMargin(apply, bottom = 9)

        val stop = Button(this).apply {
            text = "STOP FRAMEGEN"
            textSize = 13f
            minHeight = dp(54)
            setTextColor(red)
            background = rounded(Color.rgb(45, 18, 28), Color.rgb(96, 42, 56), 15)
            stateListAnimator = null
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, FrameGenService::class.java)
                        .setAction(FrameGenService.ACTION_STOP)
                )
            }
        }
        root.addView(stop)
        addMargin(stop, bottom = 14)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(Color.rgb(9, 20, 34), Color.rgb(26, 47, 72), 18)
        }
        info.addView(label("STARTUP FLOW", 10f, cyan, true))
        info.addView(label(
            "Choose game → enable permissions → authorize capture → START FRAMEGEN → " +
            "game opens → 10-second AI warm-up → RIFE neural frame generation.",
            11f, muted
        ).also { addMargin(it, top = 6) })
        root.addView(info)

        setContentView(scroll)
        refreshReadiness()
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
    }

    private fun isNotificationReady(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isTouchBridgeReady(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any {
            val info = it.resolveInfo?.serviceInfo
            info?.packageName == packageName &&
                info.name == FrameGenAccessibilityService::class.java.name
        }
    }

    private fun refreshReadiness() {
        val touchBridgeOk = isTouchBridgeReady()
        val notificationOk = isNotificationReady()
        val ready = touchBridgeOk && notificationOk && captureReady && selectedGame != null

        touchBridgeStatus?.let {
            it.text = if (touchBridgeOk) {
                "● Touch Bridge: READY"
            } else {
                "○ Touch Bridge: REQUIRED"
            }
            it.setTextColor(if (touchBridgeOk) green else red)
        }

        notificationStatus?.let {
            it.text = if (notificationOk) "● Notifications: READY"
            else "○ Notifications: REQUIRED"
            it.setTextColor(if (notificationOk) green else red)
        }

        captureStatus?.let {
            it.text = if (captureReady) "● Game capture: READY"
            else "○ Game capture: REQUIRED"
            it.setTextColor(if (captureReady) green else red)
        }

        prepareCaptureButton?.isEnabled =
            selectedGame != null && touchBridgeOk && notificationOk && !captureReady
        prepareCaptureButton?.alpha =
            if (prepareCaptureButton?.isEnabled == true) 1f else 0.45f

        startButton?.isEnabled = ready
        startButton?.alpha = if (ready) 1f else 0.45f
    }

    private fun findLaunchableGames(): List<GameEntry> {
        val pm = packageManager
        val packages = linkedMapOf<String, ApplicationInfo>()

        fun collect(intent: Intent) {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
                val app = info.activityInfo.applicationInfo
                if (app.packageName != packageName) {
                    packages.putIfAbsent(app.packageName, app)
                }
            }
        }

        // Some games advertise CATEGORY_GAME only on their launcher activity.
        collect(Intent(Intent.ACTION_MAIN).apply {
            addCategory("android.intent.category.GAME")
        })

        // Other games are only discoverable as normal launcher activities but
        // mark ApplicationInfo.category as CATEGORY_GAME.
        collect(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })

        return packages.values
            .filter { app -> isGameCandidate(app) }
            .map {
                GameEntry(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun isGameCandidate(app: ApplicationInfo): Boolean {
        if (app.packageName == packageName) return false

        val pm = packageManager
        val label = pm.getApplicationLabel(app).toString()
        val haystack = (label + " " + app.packageName).lowercase()

        if (app.category == ApplicationInfo.CATEGORY_GAME) return true
        if (hasGameCategory(app.packageName)) return true
        if ((app.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true

        // Android doesn't expose a universal "installed games" API on every
        // device. These filters recover many games that forget CATEGORY_GAME
        // while excluding common communication/media/productivity apps.
        val blocked = listOf(
            "whatsapp", "instagram", "facebook", "messenger", "telegram",
            "discord", "snapchat", "tiktok", "youtube", "spotify",
            "chrome", "browser", "gmail", "outlook", "mail", "settings",
            "calculator", "calendar", "clock", "camera", "gallery",
            "photos", "maps", "drive", "files", "launcher", "play store",
            "google play", "weather", "notes", "contacts", "phone"
        )

        if (blocked.any { haystack.contains(it) }) return false

        val hints = listOf(
            "game", "racing", "race", "battle", "arena", "quest", "craft",
            "war", "legend", "heroes", "hero", "survival", "shooter",
            "fps", "rpg", "moba", "puzzle", "zombie", "fighter", "football",
            "soccer", "basket", "golf", "chess", "kart", "simulator",
            "tycoon", "idle", "adventure", "strategy", "tactics", "dungeon"
        )

        return hints.any { haystack.contains(it) }
    }

    private fun hasGameCategory(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory("android.intent.category.GAME")
            setPackage(packageName)
        }
        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL
        ).isNotEmpty()
    }

    private fun prepareGameCapture() {
        val game = selectedGame
        if (game == null) {
            Toast.makeText(this, "Choose a game first.", Toast.LENGTH_LONG).show()
            return
        }

        if (!isTouchBridgeReady() || !isNotificationReady()) {
            Toast.makeText(
                this,
                "Enable the Touch Bridge and notification permissions first.",
                Toast.LENGTH_LONG
            ).show()
            refreshReadiness()
            return
        }

        Toast.makeText(
            this,
            "Screen capture permission is next. Select " +
                game.label + " in the Android capture dialog.",
            Toast.LENGTH_LONG
        ).show()

        // IMPORTANT: do not launch the game here. This activity must remain
        // foreground so Android can display its MediaProjection consent UI.
        requestGameCapture()
    }

    private fun requestGameCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(
            mpm.createScreenCaptureIntent(),
            reqCapture
        )
    }

    private fun startFrameGen() {
        val game = selectedGame
        val data = captureData
        val code = captureCode

        if (game == null || data == null || code == null || !captureReady) {
            Toast.makeText(
                this,
                "Complete all permissions and prepare game capture first.",
                Toast.LENGTH_LONG
            ).show()
            refreshReadiness()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(game.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Could not launch " + game.label, Toast.LENGTH_LONG).show()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        val serviceIntent = Intent(this, FrameGenService::class.java)
            .putExtra("code", code)
            .putExtra("data", data)
            .putExtra("mult", mult)
            .putExtra("quality", quality)
            .putExtra("delayMs", 10_000L)
            .putExtra("gameName", game.label)

        try {
            startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "Could not start FrameGen: " + (t.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            game.label + " connected • 10-second AI warm-up",
            Toast.LENGTH_LONG
        ).show()

        captureReady = false
        captureCode = null
        captureData = null
        refreshReadiness()
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                captureCode = resultCode
                captureData = data
                captureReady = true
                Toast.makeText(
                    this,
                    "Game capture READY. START FRAMEGEN is unlocked.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                captureCode = null
                captureData = null
                captureReady = false
                Toast.makeText(
                    this,
                    "Screen capture cancelled.",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshReadiness()
        }
    }
}
