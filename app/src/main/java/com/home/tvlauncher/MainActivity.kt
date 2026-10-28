package com.home.tvlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.LayerDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), ControlCenter.Host {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var dimView: View
    private lateinit var topBox: LinearLayout
    private lateinit var timeBox: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var gear: ImageView
    private lateinit var bottom: LinearLayout
    private lateinit var focusLabel: TextView
    private lateinit var inputsScroll: HorizontalScrollView
    private lateinit var inputsRow: LinearLayout
    private lateinit var appsScroll: HorizontalScrollView
    private lateinit var appsRow: LinearLayout
    private lateinit var appsDock: GlassFrame
    private lateinit var panel: SidePanel
    private lateinit var cc: ControlCenter
    private lateinit var widgets: WidgetLayer

    private var apps: List<AppEntry> = emptyList()
    private var loadedWallpaper: String? = "unset"
    private var learning = false
    private var slideIndex = 0
    private var lastFocusX = 0
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() { updateClock(); widgets.tick(); handler.postDelayed(this, 10_000) }
    }
    private val slideshow = object : Runnable {
        override fun run() {
            nextSlide()
            val m = prefs.slideshowMinutes
            if (m > 0) handler.postDelayed(this, m * 60_000L)
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (!panel.isOpen) buildRows(keepFocus = true) }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { runStartupTarget() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildLayout())
        Showcase.refreshIfStale(this)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        // first start after the TV boots: open the chosen start-up app/input
        val bootStamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val lastBoot = prefs.sp.getLong("bootStamp", 0)
        if (Math.abs(bootStamp - lastBoot) > 120_000) {
            prefs.sp.edit().putLong("bootStamp", bootStamp).apply()
            if (SystemClock.elapsedRealtime() < 10 * 60_000) handler.postDelayed({ runStartupTarget() }, 1500)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { widgets.setVideoSound(true) }
    }

    override fun onResume() {
        super.onResume()
        active = this
        try { registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Exception) {}
        hideSystemUi()
        applyAll()
        handler.removeCallbacks(tick); handler.post(tick)
        restartSlideshow()
        widgets.resume()
    }

    override fun onPause() {
        active = null
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        widgets.pause()
        if (widgets.movingType != null) widgets.movingType = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(slideshow)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (panel.isOpen) panel.close()
        if (cc.isOpen) cc.close()
        appsScroll.scrollTo(0, 0)
        appsRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cc.isOpen) cc.back() else if (panel.isOpen) panel.back()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // volume buttons on Home: let the playing trailer be heard (mute button silences it)
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> widgets.setVideoSound(true)
            KeyEvent.KEYCODE_VOLUME_MUTE -> widgets.setVideoSound(false)
        }
        val moving = widgets.movingType
        if (moving != null) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val step = dp(if (e.repeatCount > 4) 36 else 12).toFloat()
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> widgets.nudgePx(moving, -step, 0f)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> widgets.nudgePx(moving, step, 0f)
                    KeyEvent.KEYCODE_DPAD_UP -> widgets.nudgePx(moving, 0f, -step)
                    KeyEvent.KEYCODE_DPAD_DOWN -> widgets.nudgePx(moving, 0f, step)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                        widgets.movingType = null
                        bottom.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        gear.isFocusable = true; widgets.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        toast("Widget position saved"); buildRows(keepFocus = false)
                    }
                }
            }
            return true
        }
        if (learning && e.keyCode !in NAV_KEYS) {
            if (e.action == KeyEvent.ACTION_UP) {
                learning = false; KeyMapService.learning = false
                panel.back()
                panel.push(buttonTargetPage(e.keyCode))
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_MENU) {
            if (cc.isOpen) cc.close()
            else if (panel.isOpen) panel.close()
            else {
                val app = currentFocus?.tag as? AppEntry
                openPanel(if (app != null) appPage(app) else mainPage())
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun buildLayout(): View {
        root = FrameLayout(this).apply { clipChildren = false }

        wallpaper = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        dimView = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dimView, FrameLayout.LayoutParams(-1, -1))

        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x55000000, 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(240), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x88000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP))

        // clock + settings
        topBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Fonts.medium(this@MainActivity); letterSpacing = -0.02f
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        }
        date = TextView(this).apply {
            setTextColor(0xDDFFFFFF.toInt()); textSize = 13f; typeface = Fonts.medium(this@MainActivity)
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
        }
        timeBox.addView(clock); timeBox.addView(date)
        gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings); setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true; isClickable = true
            background = circle(0x33FFFFFF)
            setOnClickListener { openControlCenter() }
            setOnFocusChangeListener { v, has ->
                v.background = circle(if (has) prefs.accentColor else 0x33FFFFFF)
                setColorFilter(if (has) contrastOn(prefs.accentColor) else Color.WHITE)
                v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(150).start()
                if (has) focusLabel.text = if (prefs.showLabels) "Settings" else ""
            }
        }
        widgets = WidgetLayer(this, prefs)
        root.addView(widgets, FrameLayout.LayoutParams(-1, -1))

        root.addView(topBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(26); marginEnd = dp(40); marginStart = dp(40)
        })

        // rows
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(40), 0, dp(40), dp(26))
        }
        focusLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            typeface = Fonts.semibold(this@MainActivity)
            setShadowLayer(8f, 0f, 2f, 0xAA000000.toInt())
            setPadding(dp(10), 0, 0, dp(2))
        }
        inputsRow = row(); inputsScroll = scroller(inputsRow)
        appsRow = row(); appsScroll = scroller(appsRow)
        appsDock = GlassFrame(this, dpf(30f)).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); clipChildren = false }
        inputsScroll.setOnScrollChangeListener { _, _, _, _, _ -> Glass.invalidateAll() }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // widgets line up with the rows: they stop just above them
        widgets.rowsTop = { if (bottom.height > 0) bottom.top else root.height }
        // widgets keep clear of the settings button and the corner clock
        widgets.obstacles = {
            val out = ArrayList<android.graphics.Rect>()
            for (v in listOf<View>(gear, timeBox)) {
                if (v.visibility != View.VISIBLE || v.width == 0 || !v.isShown) continue
                val a = IntArray(2); v.getLocationInWindow(a)
                val b = IntArray(2); widgets.getLocationInWindow(b)
                out += android.graphics.Rect(a[0] - b[0], a[1] - b[1], a[0] - b[0] + v.width, a[1] - b[1] + v.height)
            }
            out
        }
        // widgets at the bottom push the HDMI row and apps dock aside; the apps then scroll in the space left
        widgets.onRowsReserve = { l, r ->
            bottom.setPadding(maxOf(dp(40), l), 0, maxOf(dp(40), r), dp(26))
        }
        bottom.addOnLayoutChangeListener { _, _, t, _, _, _, ot, _, _ -> if (t != ot) widgets.requestLayout() }

        panel = SidePanel(this, prefs)
        panel.onClosed = {
            bottom.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            gear.isFocusable = true
            learning = false; KeyMapService.learning = false
            applyAll()
        }
        panel.onBackFromPage = { if (learning) { learning = false; KeyMapService.learning = false } }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        cc = ControlCenter(this, prefs, this)
        root.addView(cc, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false; clipToPadding = false
        setPadding(dp(8), dp(9), dp(40), dp(9))
    }

    private fun scroller(child: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; isFocusable = false
        clipChildren = false; clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun contrastOn(c: Int): Int {
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (lum > 0.6) 0xFF111111.toInt() else Color.WHITE
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /** Re-apply every setting to the screen. */
    private fun applyAll() {
        loadWallpaper()
        dimView.alpha = prefs.dim / 100f
        Glass.dim = prefs.dim / 100f
        Glass.opacity = prefs.glassOpacity / 100f
        Glass.refraction = prefs.refraction / 100f
        Glass.invalidateAll()

        // clock
        clock.textSize = Prefs.CLOCK_SIZES[prefs.clockSize.coerceIn(0, 2)].second
        // step aside for the widgets so time/date aren't shown twice
        val wl = prefs.widgets()
        val clockWidget = wl.any { it.type == "clock" && it.on }
        val calendarWidget = wl.any { it.type == "calendar" && it.on }
        timeBox.visibility = if (prefs.showClock && !clockWidget) View.VISIBLE else View.GONE
        date.visibility = if (prefs.showDate && !calendarWidget) View.VISIBLE else View.GONE
        timeBox.gravity = if (prefs.clockLeft) Gravity.START else Gravity.END
        topBox.removeAllViews()
        val gearLp = LinearLayout.LayoutParams(dp(40), dp(40))
        if (prefs.clockLeft) {
            topBox.addView(gear, gearLp.apply { marginEnd = dp(18) }); topBox.addView(timeBox)
        } else {
            topBox.addView(timeBox); topBox.addView(gear, gearLp.apply { marginStart = dp(18) })
        }
        (topBox.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.TOP or (if (prefs.clockLeft) Gravity.START else Gravity.END)
        topBox.requestLayout()
        updateClock()

        // row block position and order
        bottom.removeAllViews()
        (appsScroll.parent as? ViewGroup)?.removeView(appsScroll)
        val useDock = prefs.glass && prefs.dock
        val appsBlock: View = if (useDock) {
            appsDock.enabledGlass = true
            appsDock.addView(appsScroll, FrameLayout.LayoutParams(-2, -2)); appsDock
        } else appsScroll
        val appsLp = LinearLayout.LayoutParams(if (useDock) -2 else -1, -2).apply { if (useDock) topMargin = dp(4) }
        appsRow.setPadding(dp(8), dp(9), if (useDock) dp(0) else dp(40), dp(9))
        bottom.addView(focusLabel)
        if (prefs.inputsBelow) {
            bottom.addView(appsBlock, appsLp)
            bottom.addView(inputsScroll, LinearLayout.LayoutParams(-1, -2))
        } else {
            bottom.addView(inputsScroll, LinearLayout.LayoutParams(-1, -2))
            bottom.addView(appsBlock, appsLp)
        }
        val lp = bottom.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (prefs.rowPosition == 1) Gravity.CENTER_VERTICAL else Gravity.BOTTOM
        lp.topMargin = if (prefs.rowPosition == 1) dp(60) else 0
        bottom.layoutParams = lp

        widgets.refresh()
        buildRows(keepFocus = true)
    }

    // ---------------------------------------------------------------- tiles

    private fun makeTile(
        widthDp: Int, heightDp: Int, label: String,
        banner: Drawable?, icon: Drawable?,
        onClick: () -> Unit, onLong: (() -> Unit)?,
    ): FrameLayout {
        val radius = dpf(Prefs.CORNERS[prefs.corners.coerceIn(0, 2)].second.toFloat())
        val zoom = Prefs.ZOOMS[prefs.zoom.coerceIn(0, 3)].second
        val accent = prefs.accentColor
        val glassTile = prefs.glass && banner == null
        var labelView: TextView? = null
        val tile: FrameLayout = (if (glassTile) GlassFrame(this, radius) else FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = radius; setColor(0x99202634.toInt()) }
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { marginEnd = dp(14) }
            isFocusable = true; isClickable = true
            clipToOutline = true
        }
        if (banner != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(banner); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (icon != null) box.addView(ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(heightDp / 2), dp(heightDp / 2)))
            box.addView(TextView(this).also { labelView = it }.apply {
                text = label; textSize = if (icon == null) 14f else 11f; setTextColor(Color.WHITE)
                typeface = Fonts.semibold(this@MainActivity)
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(dp(4), if (icon == null) 0 else dp(4), dp(4), 0)
            })
            tile.addView(box, FrameLayout.LayoutParams(-1, -1))
        }
        val ring = GradientDrawable().apply { cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(2), accent) }
        val focusLook = if (prefs.glass) LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF))
                .apply { cornerRadius = radius },
            GradientDrawable().apply { cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(2), (accent and 0x00FFFFFF) or (0xE6 shl 24)) },
        )) else ring
        val glare = GlareDrawable(radius)
        tile.cameraDistance = 9000 * resources.displayMetrics.density
        tile.setOnFocusChangeListener { v, has ->
            if (!prefs.glass) {
                v.animate().scaleX(if (has) zoom else 1f).scaleY(if (has) zoom else 1f)
                    .translationZ(if (has) dp(14).toFloat() else 0f).setDuration(180).start()
                tile.foreground = if (has) focusLook else null
            } else {
                // Apple TV style: lift + shadow + slight tilt toward where focus came from + glare sweep
                if (has) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    v.rotationY = if (loc[0] >= lastFocusX) -7f else 7f
                    lastFocusX = loc[0]
                    tile.foreground = glare; glare.sweep()
                } else { glare.stop(); tile.foreground = null }
                v.animate().scaleX(if (has) zoom else 1f).scaleY(if (has) zoom else 1f)
                    .rotationY(0f)
                    .translationZ(if (has) dp(22).toFloat() else 0f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                    .setDuration(if (has) 420 else 200)
                    .setUpdateListener { if (glassTile) v.invalidate() }.start()
                if (glassTile) {
                    (tile as GlassFrame).tint = if (has) 0xF2FFFFFF.toInt() else 0x26FFFFFF
                    labelView?.setTextColor(if (has) 0xFF111111.toInt() else Color.WHITE)
                    tile.invalidate()
                }
            }
            if (has) focusLabel.text = if (prefs.showLabels) label else ""
        }
        tile.setOnClickListener { onClick() }
        if (onLong != null) tile.setOnLongClickListener { onLong(); true }
        return tile
    }

    private fun buildRows(keepFocus: Boolean) {
        val focusedPkg = (currentFocus?.tag as? AppEntry)?.pkg
        val inputsHadFocus = currentFocus?.parent == inputsRow
        val (w, h) = Prefs.TILE_SIZES[prefs.tileSize.coerceIn(0, 2)].second

        inputsRow.removeAllViews()
        val inputs = if (prefs.showInputs) Targets.hdmiInputs(this) else emptyList()
        inputs.forEachIndexed { idx, inp ->
            val label = Targets.inputLabel(this, inp, idx)
            inputsRow.addView(makeTile((w * 0.78).toInt(), (h * 0.62).toInt(), label, null, null,
                onClick = { Targets.open(this, "input:${inp.id}") }, onLong = null).apply { tag = "input:${inp.id}" })
        }
        inputsScroll.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE

        apps = AppRepo.visibleApps(this, prefs)
        appsRow.removeAllViews()
        for (app in apps) {
            appsRow.addView(makeTile(w, h, app.label, app.banner, app.icon,
                onClick = { launch(app) }, onLong = { openPanel(appPage(app)) }).apply { tag = app })
        }

        if (panel.isOpen || (::cc.isInitialized && cc.isOpen)) return
        appsRow.post {
            val target = when {
                keepFocus && focusedPkg != null -> (0 until appsRow.childCount).map { appsRow.getChildAt(it) }
                    .firstOrNull { (it.tag as? AppEntry)?.pkg == focusedPkg }
                keepFocus && inputsHadFocus -> inputsRow.getChildAt(0)
                else -> null
            } ?: appsRow.getChildAt(0) ?: inputsRow.getChildAt(0) ?: gear
            target.requestFocus()
        }
    }

    private fun launch(app: AppEntry) {
        try { startActivity(app.launch) } catch (e: Exception) { toast("Couldn't open ${app.label}") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun runStartupTarget() {
        val t = prefs.startupTarget
        if (t.isNotBlank() && t != "home") Targets.open(this, t)
    }

    // ---------------------------------------------------------------- clock & wallpaper

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private fun loadWallpaper() {
        if (prefs.slideshowMinutes > 0 && loadedWallpaper?.startsWith("slide:") == true) return
        val path = prefs.wallpaperPath
        if (path == loadedWallpaper) return
        loadedWallpaper = path
        showWallpaperFile(path, fade = false)
    }

    private fun showWallpaperFile(path: String?, fade: Boolean) {
        val dm = resources.displayMetrics
        Thread {
            val bmp: Bitmap? = try {
                if (path != null && File(path).exists()) Targets.decodeScaled(path, dm.widthPixels, dm.heightPixels) else null
            } catch (e: Throwable) { null }
            val forGlass = bmp ?: try {
                BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default, BitmapFactory.Options().apply { inSampleSize = 2 })
            } catch (e: Throwable) { null }
            if (forGlass != null) Glass.update(forGlass, dm.widthPixels, dm.heightPixels)
            runOnUiThread {
                val set = { if (bmp != null) wallpaper.setImageBitmap(bmp) else wallpaper.setImageResource(R.drawable.wallpaper_default) }
                if (fade) wallpaper.animate().alpha(0f).setDuration(500).withEndAction {
                    set(); wallpaper.animate().alpha(1f).setDuration(700).start()
                }.start() else set()
            }
        }.start()
    }

    private fun restartSlideshow() {
        handler.removeCallbacks(slideshow)
        val m = prefs.slideshowMinutes
        if (m > 0) handler.postDelayed(slideshow, m * 60_000L)
        else if (loadedWallpaper?.startsWith("slide:") == true) { loadedWallpaper = "unset"; loadWallpaper() }
    }

    private fun nextSlide() {
        Thread {
            val files = try { Targets.findImages() } catch (e: Exception) { emptyList() }
            if (files.isEmpty()) return@Thread
            slideIndex = (slideIndex + 1) % files.size
            val f = files[slideIndex]
            runOnUiThread { loadedWallpaper = "slide:${f.absolutePath}"; showWallpaperFile(f.absolutePath, fade = true) }
        }.start()
    }

    // ---------------------------------------------------------------- settings panel

    // ---------------------------------------------------------------- control center

    private fun openControlCenter() {
        bottom.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gear.isFocusable = false
        cc.open()
    }

    override fun applySettings() { live() }
    override fun openPage(which: String) {
        openPanel(when (which) { "apps" -> appsPage(); "buttons" -> buttonsPage(); "widgets" -> widgetsPage(); else -> mainPage() })
    }
    override fun openTarget(target: String) = Targets.open(this, target)
    override fun pickWallpaper() { startActivity(Intent(this, WallpaperPickerActivity::class.java)) }
    override fun onControlCenterClosed() {
        if (!panel.isOpen) {
            bottom.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            gear.isFocusable = true
            applyAll()
        }
    }

    private fun openPanel(page: Page) {
        bottom.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gear.isFocusable = false
        panel.open(page)
    }

    /** Apply a setting to the home screen while the panel stays open. */
    private fun live() {
        applyAll()
        bottom.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gear.isFocusable = false
    }

    private fun mainPage() = Page("Settings", "Your home screen, your way") {
        listOf(
            NavItem("Appearance", R.drawable.ic_palette) { appearancePage() }.badged(0xFF5E5CE6, R.drawable.ic_b_palette),
            NavItem("Wallpaper", R.drawable.ic_image) { wallpaperPage() }.badged(0xFF32ADE6, R.drawable.ic_b_photo),
            NavItem("Clock", R.drawable.ic_schedule) { clockPage() }.badged(0xFFFF9F0A, R.drawable.ic_b_clock),
            GapItem(),
            NavItem("Apps", R.drawable.ic_apps, { "${apps.size} shown" }) { appsPage() }.badged(0xFF0A84FF, R.drawable.ic_b_grid),
            NavItem("Remote buttons", R.drawable.ic_remote, { "${prefs.keyMap.size} set" }) { buttonsPage() }.badged(0xFF8E8E93, R.drawable.ic_b_remote),
            NavItem("When the TV turns on", R.drawable.ic_power, { if (prefs.startupTarget.isBlank()) "Home" else Targets.label(this, prefs.startupTarget) }) { startupPage() }.badged(0xFF30D158, R.drawable.ic_b_power),
            GapItem(),
            NavItem("Widgets", R.drawable.ic_apps, { "${prefs.widgets().count { it.on }} on" }) { widgetsPage() }.badged(0xFFFF375F, R.drawable.ic_b_widgets),
            NavItem("Screen saver", R.drawable.ic_slideshow, { "Movie posters" }) { saverPage() }.badged(0xFF64D2FF, R.drawable.ic_b_tv),
            NavItem("Backup & restore", R.drawable.ic_save) { backupPage() }.badged(0xFFBF5AF2, R.drawable.ic_b_download),
            ActionItem("Android settings", R.drawable.ic_tune) {
                panel.close(); startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.badged(0xFF636366, R.drawable.ic_b_gear),
            InfoItem("Home launcher 8.0 • no ads, no tracking"),
        )
    }

    private fun appearancePage() = Page("Appearance") {
        listOf(
            ChoiceItem("Style", R.drawable.ic_palette, listOf("Glass", "Classic"), { if (prefs.glass) 0 else 1 }) { prefs.glass = it == 0; live(); panel.refresh() },
            ChoiceItem("Glass opacity", R.drawable.ic_lens, (0..10).map { "${it * 10}%" }, { (prefs.glassOpacity / 10).coerceIn(0, 10) }) { prefs.glassOpacity = it * 10; live() },
            ChoiceItem("Refraction", R.drawable.ic_zoom, (0..10).map { "${it * 10}%" }, { (prefs.refraction / 10).coerceIn(0, 10) }) { prefs.refraction = it * 10; live() },
            ToggleItem("Glass dock behind apps", R.drawable.ic_apps, { prefs.dock }) { prefs.dock = it; live() },
            GapItem(),
            ChoiceItem("Tile size", R.drawable.ic_crop169, Prefs.TILE_SIZES.map { it.first }, { prefs.tileSize }) { prefs.tileSize = it; live() },
            ChoiceItem("Row position", R.drawable.ic_valign, listOf("Bottom", "Middle"), { prefs.rowPosition }) { prefs.rowPosition = it; live() },
            ChoiceItem("Accent colour", R.drawable.ic_lens, Prefs.ACCENTS.map { it.first }, { prefs.accent }) { prefs.accent = it; live(); panel.refresh() },
            ChoiceItem("Corners", R.drawable.ic_corner, Prefs.CORNERS.map { it.first }, { prefs.corners }) { prefs.corners = it; live() },
            ChoiceItem("Zoom when selected", R.drawable.ic_zoom, Prefs.ZOOMS.map { it.first }, { prefs.zoom }) { prefs.zoom = it; live() },
            ToggleItem("App names", R.drawable.ic_title, { prefs.showLabels }) { prefs.showLabels = it; focusLabel.text = "" },
            GapItem(),
            ToggleItem("HDMI inputs row", R.drawable.ic_input, { prefs.showInputs }) { prefs.showInputs = it; live() },
            ChoiceItem("HDMI row", R.drawable.ic_swapvert, listOf("Above apps", "Below apps"), { if (prefs.inputsBelow) 1 else 0 }) { prefs.inputsBelow = it == 1; live() },
        )
    }

    private fun wallpaperPage() = Page("Wallpaper") {
        listOf(
            ActionItem("Choose an image", R.drawable.ic_image, { "Pictures, Download" }) {
                panel.close(); startActivity(Intent(this, WallpaperPickerActivity::class.java))
            },
            ChoiceItem("Darken", R.drawable.ic_brightness, (0..16).map { "${it * 5}%" }, { (prefs.dim / 5).coerceIn(0, 16) }) { prefs.dim = it * 5; live() },
            ChoiceItem("Slideshow", R.drawable.ic_slideshow, Prefs.SLIDESHOW.map { if (it == 0) "Off" else "Every $it min" },
                { Prefs.SLIDESHOW.indexOf(prefs.slideshowMinutes).coerceAtLeast(0) }) {
                prefs.slideshowMinutes = Prefs.SLIDESHOW[it]; restartSlideshow()
            },
            InfoItem("Slideshow rotates through the images in the TV's Pictures and Download folders."),
            ActionItem("Use the built-in wallpaper", R.drawable.ic_undo) {
                prefs.wallpaperPath = null; prefs.slideshowMinutes = 0; loadedWallpaper = "unset"; loadWallpaper(); panel.refresh()
            },
        )
    }

    private fun clockPage() = Page("Clock") {
        listOf(
            ToggleItem("Show clock", R.drawable.ic_schedule, { prefs.showClock }) { prefs.showClock = it; live() },
            ChoiceItem("Format", R.drawable.ic_schedule, listOf("12-hour", "24-hour"), { if (prefs.clock24) 1 else 0 }) { prefs.clock24 = it == 1; updateClock() },
            ToggleItem("Show date", R.drawable.ic_event, { prefs.showDate }) { prefs.showDate = it; live() },
            ChoiceItem("Size", R.drawable.ic_formatsize, Prefs.CLOCK_SIZES.map { it.first }, { prefs.clockSize }) { prefs.clockSize = it; live() },
            ChoiceItem("Position", R.drawable.ic_swaphoriz, listOf("Top right", "Top left"), { if (prefs.clockLeft) 1 else 0 }) { prefs.clockLeft = it == 1; live() },
        )
    }

    private fun appsPage(): Page = Page("Apps", "Select an app to show, hide, move or rename it") {
        val hidden = prefs.hidden
        val all = AppRepo.sort(AppRepo.allApps(this, prefs), prefs.order)
        all.map { a ->
            NavItem(a.label, if (a.pkg in hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility, { if (a.pkg in prefs.hidden) "Hidden" else "Shown" }) { appPage(a) } as Item
        } + ActionItem("Reset app order", R.drawable.ic_undo) { prefs.order = emptyList(); live(); toast("Order reset") }
    }

    private fun appPage(app: AppEntry): Page = Page(app.label, app.pkg) {
        val shown = app.pkg !in prefs.hidden
        val items = mutableListOf<Item>(
            ActionItem("Open", R.drawable.ic_play) { panel.close(); launch(app) },
            ToggleItem("Show on Home", R.drawable.ic_visibility, { app.pkg !in prefs.hidden }) { show ->
                prefs.hidden = if (show) prefs.hidden - app.pkg else prefs.hidden + app.pkg; live()
            },
        )
        if (shown) {
            items += ActionItem("Move left", R.drawable.ic_back) { moveApp(app.pkg, -1) }
            items += ActionItem("Move right", R.drawable.ic_forward) { moveApp(app.pkg, +1) }
            items += ActionItem("Move to start", R.drawable.ic_firstpage) { moveApp(app.pkg, -999) }
        }
        items += InputItem("Rename", R.drawable.ic_edit, app.label) { name ->
            prefs.setCustomName(app.pkg, if (name.isBlank() || name == app.originalLabel) null else name)
            live(); toast("Renamed"); panel.back()
        }
        if (prefs.customName(app.pkg) != null) {
            items += ActionItem("Restore original name", R.drawable.ic_undo) { prefs.setCustomName(app.pkg, null); live(); panel.back() }
        }
        items += ActionItem("App info", R.drawable.ic_info) {
            panel.close()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val isSystem = try {
            (packageManager.getApplicationInfo(app.pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) { true }
        if (!isSystem) items += ActionItem("Uninstall", R.drawable.ic_delete) {
            panel.close()
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        items
    }

    // ---------------------------------------------------------------- widgets settings

    private fun widgetsPage(): Page = Page("Widgets", "Clock, calendar and a video player you can place anywhere") {
        prefs.widgets().map { w ->
            val icon = when (w.type) { "video" -> R.drawable.ic_slideshow; "clock" -> R.drawable.ic_schedule; else -> R.drawable.ic_event }
            NavItem(WidgetSpec.NAMES[w.type] ?: w.type, icon, { if (prefs.widgets().first { it.type == w.type }.on) "On" else "Off" }) { widgetPage(w.type) } as Item
        } + InfoItem("Tip: choose a widget, then Move to place it with the arrow keys.")
    }

    private fun updateWidget(type: String, f: (WidgetSpec) -> Unit) {
        val list = prefs.widgets(); list.firstOrNull { it.type == type }?.let(f); prefs.saveWidgets(list); live()
    }

    private fun widgetPage(type: String): Page = Page(WidgetSpec.NAMES[type] ?: type) {
        fun cur() = prefs.widgets().first { it.type == type }
        val items = mutableListOf<Item>(
            ToggleItem("Show widget", R.drawable.ic_visibility, { cur().on }) { v -> updateWidget(type) { it.on = v } },
        )
        when (type) {
            "clock" -> items += ChoiceItem("Style", R.drawable.ic_schedule, WidgetSpec.CLOCK_STYLES, { cur().style }) { v -> updateWidget(type) { it.style = v } }
            "calendar" -> items += ChoiceItem("Style", R.drawable.ic_event, WidgetSpec.CALENDAR_STYLES, { cur().style }) { v -> updateWidget(type) { it.style = v } }
            "video" -> items += ChoiceItem("Plays from", R.drawable.ic_play, WidgetSpec.VIDEO_SOURCES, { cur().source }) { v -> updateWidget(type) { it.source = v } }
        }
        items += ChoiceItem("Size", R.drawable.ic_crop169, WidgetSpec.SIZES, { cur().size }) { v -> updateWidget(type) { it.size = v } }
        val spots = listOf(null, 0f to 0f, 0.5f to 0f, 1f to 0f, 0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f, 0f to 1f, 1f to 1f)
        items += ChoiceItem("Quick position", R.drawable.ic_valign,
            listOf("Custom", "Top left", "Top centre", "Top right", "Middle left", "Centre", "Middle right", "Bottom left", "Bottom right"),
            { val c = cur(); spots.indexOfFirst { it != null && it.first == c.x && it.second == c.y }.coerceAtLeast(0) }) { v ->
            spots[v]?.let { (x, y) -> widgets.place(type, x, y) }
        }
        items += GapItem()
        items += ActionItem("Move", R.drawable.ic_swaphoriz, { "arrows, then OK" }) {
            if (!cur().on) updateWidget(type) { it.on = true }
            panel.close()
            handler.postDelayed({
                bottom.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; widgets.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                widgets.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                gear.isFocusable = false
                widgets.movingType = type
                toast("Move with the arrow keys, OK to finish")
            }, 260)
        }
        if (type == "clock") items += InfoItem("You can hide the clock in the top corner under Settings > Clock.")
        if (type == "calendar") items += InfoItem("Agenda shows events from Google Calendar if it syncs to this TV.")
        if (type == "video") items += InfoItem("Plays the previews and continue-watching your apps publish when available, otherwise official Netflix and Apple TV trailers. Muted. Select it and press OK to open.")
        items
    }

    private fun moveApp(pkg: String, delta: Int) {
        val list = apps.map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.size - 1)
        list.removeAt(i); list.add(j, pkg)
        prefs.order = list + prefs.order.filter { it !in list }
        live()
        toast("Moved to position ${j + 1}")
    }

    private fun buttonsPage(): Page = Page("Remote buttons", "Make any remote button open an app, an HDMI input or Home") {
        val items = mutableListOf<Item>()
        if (!KeyMapService.isEnabled(this)) {
            items += InfoItem("Button control is switched off. Run the install script on your Mac again to switch it on.")
        }
        for ((code, target) in prefs.keyMap) {
            items += NavItem(keyName(code), R.drawable.ic_remote, { Targets.label(this, target) }) { buttonTargetPage(code) }
        }
        items += ActionItem("Add a button", R.drawable.ic_add) {
            learning = true; KeyMapService.learning = true
            panel.push(Page("Press a button", "Press the remote button you want to set up, e.g. Prime Video. Back cancels.") {
                listOf(InfoItem("Waiting for a button…"))
            })
        }
        items
    }

    private fun buttonTargetPage(code: Int): Page = Page(keyName(code), "What should this button open?") {
        val current = prefs.keyMap[code]
        val items = mutableListOf<Item>()
        for ((t, label) in Targets.choices(this)) {
            items += ActionItem(label, if (t == current) R.drawable.ic_check else 0) {
                prefs.keyMap = prefs.keyMap + (code to t)
                toast("${keyName(code)} → $label"); panel.back()
            }
        }
        if (current != null) items += ActionItem("Remove this button", R.drawable.ic_delete) {
            prefs.keyMap = prefs.keyMap - code; panel.back()
        }
        items
    }

    private fun keyName(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").split('_')
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            .replace("Primevideo", "Prime Video")

    private fun startupPage(): Page = Page("When the TV turns on", "Open this automatically after the TV starts") {
        val current = prefs.startupTarget
        val items = mutableListOf<Item>(
            ActionItem("Stay on Home", if (current.isBlank() || current == "home") R.drawable.ic_check else 0) { prefs.startupTarget = ""; panel.refresh() }
        )
        for ((t, label) in Targets.choices(this).filter { it.first != "home" }) {
            items += ActionItem(label, if (t == current) R.drawable.ic_check else 0) { prefs.startupTarget = t; panel.refresh() }
        }
        items
    }

    private fun saverPage(): Page = Page("Screen saver", "Movie posters from Apple TV and Netflix while the TV is idle") {
        listOf(
            ActionItem("Preview", R.drawable.ic_play) { panel.close(); startActivity(Intent(this, PosterPreviewActivity::class.java)) },
            GapItem(),
            ChoiceItem("Shows", R.drawable.ic_slideshow, listOf("Apple TV & Netflix", "Apple TV only", "Netflix only"), { prefs.saverSource }) { prefs.saverSource = it },
            ChoiceItem("Change every", R.drawable.ic_schedule, Prefs.SAVER_SECONDS.map { if (it < 60) "$it seconds" else "1 minute" },
                { Prefs.SAVER_SECONDS.indexOf(prefs.saverSeconds).coerceAtLeast(0) }) { prefs.saverSeconds = Prefs.SAVER_SECONDS[it] },
            ToggleItem("Show time", R.drawable.ic_schedule, { prefs.saverClock }) { prefs.saverClock = it },
            ToggleItem("Show descriptions", R.drawable.ic_title, { prefs.saverInfo }) { prefs.saverInfo = it },
            GapItem(),
            ActionItem("Get the latest titles", R.drawable.ic_download, {
                "${Showcase.apple.size + Showcase.netflix.size} titles"
            }) { Showcase.refreshIfStale(this, force = true); toast("Fetching the latest titles…"); handler.postDelayed({ panel.refresh() }, 20_000) },
            ActionItem("Android screen saver settings", R.drawable.ic_tune) {
                panel.close()
                try { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            InfoItem("While it's showing: OK opens the title in its app, Left/Right flip through, any other button wakes the TV."),
        )
    }

    private fun backupFile() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "home-launcher-backup.json")

    private fun backupPage(): Page = Page("Backup & restore", "Saved as Download/home-launcher-backup.json on the TV") {
        val f = backupFile()
        listOf(
            ActionItem("Save backup", R.drawable.ic_save, {
                if (f.exists()) "Last: " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(f.lastModified())) else "None yet"
            }) {
                try { f.parentFile?.mkdirs(); prefs.exportTo(f); toast("Backup saved") } catch (e: Exception) { toast("Couldn't save: ${e.message}") }
                panel.refresh()
            },
            ActionItem("Restore backup", R.drawable.ic_download) {
                if (!f.exists()) { toast("No backup found") } else {
                    try { prefs.importFrom(f); loadedWallpaper = "unset"; live(); toast("Settings restored") } catch (e: Exception) { toast("Couldn't restore: ${e.message}") }
                    panel.refresh()
                }
            },
            ActionItem("Reset everything", R.drawable.ic_warning) {
                prefs.sp.edit().clear().apply(); loadedWallpaper = "unset"; live(); toast("Back to defaults"); panel.refresh()
            },
        )
    }

    companion object {
        /** The Home screen while it's in front (used by the button service for volume keys). */
        @Volatile var active: MainActivity? = null
        fun onVolumeKey() { active?.let { a -> a.runOnUiThread { a.widgets.setVideoSound(true) } } }

        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        )
    }
}
