package com.home.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mac/iPhone-style Control Center: a frosted panel that drops down from the top-right with
 * rounded modules — input buttons, round toggles, sliders, colour dots and shortcut tiles.
 */
class ControlCenter(private val act: Activity, private val prefs: Prefs, private val host: Host) : FrameLayout(act) {

    interface Host {
        fun applySettings()
        fun openPage(which: String)
        fun openTarget(target: String)
        fun pickWallpaper()
        fun onControlCenterClosed()
    }

    private val panel: GlassFrame
    private val grid: LinearLayout
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val isOpen get() = visibility == View.VISIBLE
    private var detail: String? = null

    /** Back: leave an expanded module first, then close. */
    fun back() { if (detail != null) { detail = null; build(); post { focusTag("glass")?.requestFocus() ?: firstFocusable(grid)?.requestFocus() } } else close() }

    private fun focusTag(t: String): View? = grid.findViewWithTag(t)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    init {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(0x33000000)
        grid = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipChildren = false; clipToPadding = false
        }
        panel = GlassFrame(act, dpf(30f), 0x330B0F1A).apply {
            elevation = dpf(26f)
            clipChildren = false
            addView(grid, LayoutParams(-1, -2))
        }
        addView(panel, LayoutParams(dp(350), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); marginEnd = dp(20)
        })
    }

    // ---------------------------------------------------------------- open / close

    fun open() {
        detail = null
        build()
        visibility = View.VISIBLE
        panel.enabledGlass = prefs.glass
        panel.tint = if (prefs.glass) 0x330B0F1A else 0xF0161B28.toInt()
        alpha = 0f
        panel.pivotX = dpf(350f); panel.pivotY = 0f
        panel.scaleX = 0.88f; panel.scaleY = 0.88f
        animate().alpha(1f).setDuration(150).start()
        panel.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.1f)).setDuration(280)
            .setUpdateListener { panel.invalidate() }.withEndAction { panel.invalidate() }.start()
        post { firstFocusable(grid)?.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        panel.animate().scaleX(0.9f).scaleY(0.9f).setDuration(160).setUpdateListener { panel.invalidate() }.start()
        animate().alpha(0f).setDuration(160).withEndAction {
            visibility = View.GONE
            panel.scaleX = 1f; panel.scaleY = 1f
            host.onControlCenterClosed()
        }.start()
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        grid.removeAllViews()
        if (detail == "glass") { buildGlass(); return }

        // row 1: inputs (left) + toggles (right)
        val row1 = hRow()
        val inputs = module().apply { setPadding(dp(6), dp(9), dp(6), dp(8)) }
        inputs.addView(caption("Inputs"))
        val inputGrid = grid2x2()
        val ins = Targets.hdmiInputs(act)
        ins.take(3).forEachIndexed { i, inp ->
            inputGrid[i].addView(circleButton(R.drawable.ic_input, Targets.inputLabel(act, inp, i)) {
                close(); host.openTarget("input:${inp.id}")
            })
        }
        val liveTv = act.packageManager.getLeanbackLaunchIntentForPackage("com.smartdevice.livetv")
        if (liveTv != null) inputGrid[3].addView(circleButton(R.drawable.ic_slideshow, "Live TV") {
            close(); host.openTarget("app:com.smartdevice.livetv")
        })
        inputs.addView(inputGrid.root)
        row1.addView(inputs, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(10) })

        val toggles = module().apply { setPadding(dp(6), dp(9), dp(6), dp(8)) }
        toggles.addView(caption("Quick toggles"))
        val tg = grid2x2()
        tg[0].addView(circleToggle(R.drawable.ic_speaker_off, "Mute", 0xFFFF9F0A.toInt(),
            { audio.isStreamMute(AudioManager.STREAM_MUSIC) }) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (it) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        })
        tg[1].addView(circle(R.drawable.ic_b_palette, "Glass", { if (prefs.glass) 0xFF0A84FF.toInt() else 0x40FFFFFF }) {
            detail = "glass"; build(); post { firstFocusable(grid)?.requestFocus() }
        }.also { it.findViewWithTag<View>("circle")?.tag = "glass" })
        tg[2].addView(circleToggle(R.drawable.ic_b_clock, "Clock", 0xFF5E5CE6.toInt(), { prefs.showClock }) {
            prefs.showClock = it; host.applySettings()
        })
        tg[3].addView(circleToggle(R.drawable.ic_slideshow, "Slideshow", 0xFF30D158.toInt(), { prefs.slideshowMinutes > 0 }) {
            prefs.slideshowMinutes = if (it) 15 else 0; host.applySettings()
        })
        toggles.addView(tg.root)
        row1.addView(toggles, LinearLayout.LayoutParams(0, -1, 1f))
        grid.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // sliders
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        grid.addView(slider(R.drawable.ic_speaker, "Volume",
            { audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max }, 1f / max,
            { "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}" }) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(it * max), 0)
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })
        grid.addView(slider(R.drawable.ic_brightness, "Darken wallpaper", { prefs.dim / 80f }, 1f / 40,
            { "${prefs.dim}%" }) {
            prefs.dim = (Math.round(it * 40) * 2).coerceIn(0, 80); host.applySettings()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        // accent colours
        val accents = module().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        accents.addView(caption("Accent").apply { setPadding(0, 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        Prefs.ACCENTS.forEachIndexed { i, (_, color) -> accents.addView(swatch(i, color)) }
        grid.addView(accents, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })

        // shortcut tiles
        val tiles = hRow()
        tiles.addView(shortcut(R.drawable.ic_b_photo, 0xFF32ADE6, "Wallpaper") { close(); host.pickWallpaper() }, tileLp(true))
        tiles.addView(shortcut(R.drawable.ic_b_grid, 0xFF0A84FF, "Apps") { close(); host.openPage("apps") }, tileLp(true))
        tiles.addView(shortcut(R.drawable.ic_b_widgets, 0xFFFF375F, "Widgets") { close(); host.openPage("widgets") }, tileLp(true))
        tiles.addView(shortcut(R.drawable.ic_b_gear, 0xFF636366, "All settings") { close(); host.openPage("main") }, tileLp(false))
        grid.addView(tiles, LinearLayout.LayoutParams(-1, dp(72)))
    }

    private fun buildGlass() {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(act).apply { setImageResource(R.drawable.ic_b_palette); background = SquircleDrawable(0xFF0A84FF.toInt()); setPadding(dp(7), dp(7), dp(7), dp(7)) },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(act).apply { text = "Liquid Glass"; textSize = 20f; setTextColor(Color.WHITE); typeface = Fonts.bold(act) })
        titles.addView(TextView(act).apply { text = "Press Back to return"; textSize = 12f; setTextColor(0x99EBEBF5.toInt()); typeface = Fonts.regular(act) })
        head.addView(titles)
        grid.addView(head, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(4), 0, dp(14)) })

        // on/off as a wide module with an iOS switch
        val sw = module().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; setPadding(dp(16), 0, dp(14), 0)
        }
        sw.addView(TextView(act).apply { text = "Liquid Glass"; textSize = 15f; setTextColor(Color.WHITE); typeface = Fonts.medium(act) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val track = FrameLayout(act); val knob = View(act)
        track.addView(knob, LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL))
        sw.addView(track, LinearLayout.LayoutParams(dp(50), dp(30)))
        fun paintSwitch(f: Boolean) {
            val on = prefs.glass
            sw.background = GradientDrawable().apply {
                cornerRadius = dpf(22f); setColor(if (f) 0x47FFFFFF else 0x29FFFFFF); if (f) setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            track.background = GradientDrawable().apply { cornerRadius = dpf(15f); setColor(if (on) 0xFF30D158.toInt() else 0x4D787880) }
            track.setPadding(dp(4), 0, dp(4), 0)
            knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            (knob.layoutParams as LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
            knob.requestLayout()
        }
        paintSwitch(false)
        sw.setOnFocusChangeListener { _, has -> paintSwitch(has) }
        val toggle = {
            prefs.glass = !prefs.glass; host.applySettings()
            panel.enabledGlass = prefs.glass; panel.tint = if (prefs.glass) 0x330B0F1A else 0xF0161B28.toInt(); panel.invalidate()
            paintSwitch(sw.hasFocus())
        }
        sw.setOnClickListener { toggle() }
        sw.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val wantOn = code == KeyEvent.KEYCODE_DPAD_RIGHT
                if (prefs.glass != wantOn) toggle(); true
            } else false
        }
        grid.addView(sw, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        grid.addView(slider(R.drawable.ic_lens, "Opacity", { prefs.glassOpacity / 100f }, 0.02f, { "${prefs.glassOpacity}%" }) {
            prefs.glassOpacity = (Math.round(it * 50) * 2).coerceIn(0, 100)
            Glass.opacity = prefs.glassOpacity / 100f; Glass.invalidateAll(); panel.invalidate()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        grid.addView(slider(R.drawable.ic_zoom, "Refraction", { prefs.refraction / 100f }, 0.02f, { "${prefs.refraction}%" }) {
            prefs.refraction = (Math.round(it * 50) * 2).coerceIn(0, 100)
            Glass.refraction = prefs.refraction / 100f; Glass.invalidateAll(); panel.invalidate()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        grid.addView(slider(R.drawable.ic_brightness, "Darken wallpaper", { prefs.dim / 80f }, 1f / 40, { "${prefs.dim}%" }) {
            prefs.dim = (Math.round(it * 40) * 2).coerceIn(0, 80); host.applySettings()
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        grid.addView(TextView(act).apply {
            text = "Opacity: 0% crystal clear, 100% fully frosted. Refraction: how much the glass edges bend what's behind them."
            textSize = 12f; setTextColor(0x99EBEBF5.toInt()); typeface = Fonts.regular(act)
            setPadding(dp(8), dp(2), dp(8), dp(4))
        })
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, -1, 1f).apply { if (gap) marginEnd = dp(8) }

    private fun hRow() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
    }

    private fun module() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        background = GradientDrawable().apply { cornerRadius = dpf(22f); setColor(0x29FFFFFF) }
    }

    private fun caption(t: String) = TextView(act).apply {
        text = t; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); typeface = Fonts.semibold(act)
        setPadding(dp(4), 0, 0, dp(5))
    }

    private class Grid2(val root: LinearLayout, val cells: List<FrameLayout>) { operator fun get(i: Int) = cells[i] }

    private fun grid2x2(): Grid2 {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val cells = mutableListOf<FrameLayout>()
        repeat(2) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            repeat(2) {
                val c = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
                cells += c; r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        return Grid2(root, cells)
    }

    // ---------------------------------------------------------------- controls

    /** Round icon + caption. on = filled colour, off = translucent. Focus = grow + white ring. */
    private fun circleToggle(icon: Int, label: String, onColor: Int, get: () -> Boolean, set: (Boolean) -> Unit): View =
        circle(icon, label, { if (get()) onColor else 0x40FFFFFF }) { set(!get()) }

    private fun circleButton(icon: Int, label: String, run: () -> Unit): View =
        circle(icon, label, { 0x40FFFFFF }) { run() }

    private fun circle(icon: Int, label: String, color: () -> Int, onClick: () -> Unit): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; tag = "circle"; gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false
        }
        val disc = FrameLayout(act)
        val img = ImageView(act).apply { setImageResource(icon); setColorFilter(Color.WHITE) }
        disc.addView(img, LayoutParams(dp(19), dp(19), Gravity.CENTER))
        box.addView(disc, LinearLayout.LayoutParams(dp(38), dp(38)))
        val text = TextView(act).apply {
            this.text = label; textSize = 10f; setTextColor(0xE6FFFFFF.toInt()); typeface = Fonts.medium(act)
            maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
        }
        box.addView(text, LinearLayout.LayoutParams(dp(64), -2))
        fun paint(focused: Boolean) {
            disc.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color())
                if (focused) setStroke(dp(2), Color.WHITE)
            }
        }
        paint(false)
        box.setOnFocusChangeListener { _, has ->
            paint(has)
            box.translationZ = if (has) dpf(8f) else 0f
            disc.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(140).start()
        }
        box.setOnClickListener { onClick(); paint(box.hasFocus()) }
        return FrameLayout(act).apply {
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(4), 0, dp(2))
            addView(box, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
    }

    /** macOS-style slider module: icon + title + rounded bar. Left/Right adjusts. */
    private fun slider(icon: Int, title: String, get: () -> Float, step: Float,
                       valueText: (() -> String)? = null, set: (Float) -> Unit): View {
        val m = module().apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true
            setPadding(dp(16), dp(7), dp(16), dp(7))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(caption(title), LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = TextView(act).apply {
            textSize = 12f; setTextColor(0xE6FFFFFF.toInt()); typeface = Fonts.semibold(act)
            text = valueText?.invoke() ?: ""
        }
        head.addView(valueView)
        m.addView(head)
        val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bar = SliderBar(act).apply { value = get() }
        line.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        line.addView(bar, LinearLayout.LayoutParams(0, dp(16), 1f))
        m.addView(line)
        fun paint(f: Boolean) {
            m.background = GradientDrawable().apply {
                cornerRadius = dpf(22f); setColor(if (f) 0x47FFFFFF else 0x29FFFFFF)
                if (f) setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            bar.focused = f; bar.invalidate()
        }
        paint(false)
        m.setOnFocusChangeListener { _, has -> paint(has) }
        m.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val d = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step
                    val v = (get() + d).coerceIn(0f, 1f)
                    set(v); bar.value = get(); bar.invalidate()
                    valueView.text = valueText?.invoke() ?: ""; true
                }
                else -> false
            }
        }
        return m
    }

    private inner class SliderBar(c: Context) : View(c) {
        var value = 0f
        var focused = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            r.set(0f, 0f, w, h)
            p.color = 0x33FFFFFF; canvas.drawRoundRect(r, h / 2, h / 2, p)
            r.set(0f, 0f, (w * value).coerceAtLeast(h), h)
            p.color = if (focused) Color.WHITE else 0xE6FFFFFF.toInt(); canvas.drawRoundRect(r, h / 2, h / 2, p)
        }
    }

    private fun swatch(index: Int, color: Int): View {
        val v = View(act).apply { isFocusable = true; isClickable = true }
        fun paint(f: Boolean) {
            val selected = prefs.accent == index
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
                if (selected || f) setStroke(dp(if (f) 4 else 3), if (f) Color.WHITE else 0x99FFFFFF.toInt())
            }
        }
        paint(false)
        v.setOnFocusChangeListener { _, has ->
            paint(has); v.animate().scaleX(if (has) 1.18f else 1f).scaleY(if (has) 1.18f else 1f).setDuration(120).start()
        }
        v.setOnClickListener {
            prefs.accent = index; host.applySettings()
            val parent = v.parent as LinearLayout
            for (i in 0 until parent.childCount) parent.getChildAt(i).let { c -> if (c != v) c.onFocusChangeListener?.onFocusChange(c, false) }
            paint(true)
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) }
        return v
    }

    private fun shortcut(glyph: Int, color: Long, label: String, onClick: () -> Unit): View {
        val m = module().apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(dp(6), dp(8), dp(6), dp(6))
        }
        m.addView(ImageView(act).apply {
            setImageResource(glyph); background = SquircleDrawable(color.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        m.addView(TextView(act).apply {
            text = label; textSize = 10f; setTextColor(Color.WHITE); typeface = Fonts.medium(act)
            gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(5), 0, 0)
        })
        fun paint(f: Boolean) {
            m.background = GradientDrawable().apply {
                cornerRadius = dpf(22f); setColor(if (f) 0x47FFFFFF else 0x29FFFFFF)
                if (f) setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
        }
        paint(false)
        m.setOnFocusChangeListener { _, has ->
            paint(has); m.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
        }
        m.setOnClickListener { onClick() }
        return m
    }
}
