package com.home.tvlauncher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ------------------------------------------------------------------ menu model

sealed class Item(val title: String, val icon: Int) {
    /** iOS-style coloured squircle badge behind the icon (0 = none) */
    var badge: Int = 0
    /** solid glyph used inside the badge (falls back to icon) */
    var badgeIcon: Int = 0
}
fun <T : Item> T.badged(color: Long, glyph: Int = 0): T { badge = color.toInt(); badgeIcon = glyph; return this }
class NavItem(title: String, icon: Int, val value: (() -> String)? = null, val page: () -> Page) : Item(title, icon)
class ActionItem(title: String, icon: Int, val value: (() -> String)? = null, val run: () -> Unit) : Item(title, icon)
class ChoiceItem(title: String, icon: Int, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit) : Item(title, icon)
class ToggleItem(title: String, icon: Int, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item(title, icon)
class InputItem(title: String, icon: Int, val initial: String, val onDone: (String) -> Unit) : Item(title, icon)
/** Text between groups (like iOS section headers/footers). Also splits the list into groups. */
class InfoItem(text: String) : Item(text, 0)
/** Starts a new rounded group without any text. */
class GapItem : Item("", 0)

class Page(val title: String, val subtitle: String? = null, val build: () -> List<Item>) {
    var focusIndex = 0
}

/**
 * Apple-style settings panel: floating glass card, grouped rows with hairline dividers,
 * squircle badges. Up/Down move, Left/Right change a value, OK selects, Back goes back.
 */
class SidePanel(context: Context, private val prefs: Prefs) : FrameLayout(context) {

    private val stack = ArrayList<Page>()
    private val card: LinearLayout
    private val glassCard: GlassFrame
    private val header: TextView
    private val sub: TextView
    private val list: LinearLayout
    private val scroll: ScrollView
    private var items: List<Item> = emptyList()
    private var rowViews: MutableList<View?> = mutableListOf()
    var onClosed: (() -> Unit)? = null
    var onBackFromPage: ((Page) -> Unit)? = null

    val isOpen get() = visibility == View.VISIBLE
    val currentPage: Page? get() = stack.lastOrNull()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    init {
        visibility = View.GONE
        isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00000000, 0x66000000))

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(14))
        }
        header = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 26f
            typeface = Fonts.bold(context); letterSpacing = -0.02f
            setPadding(dp(8), 0, 0, dp(2))
        }
        sub = TextView(context).apply {
            setTextColor(0x99EBEBF5.toInt()); textSize = 12.5f
            typeface = Fonts.regular(context)
            setPadding(dp(8), 0, dp(8), dp(14))
        }
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false
            clipChildren = false; clipToPadding = false
            addView(list)
        }
        card.addView(header); card.addView(sub)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        glassCard = GlassFrame(context, dp(30).toFloat(), 0x4D0B0F1A).apply {
            elevation = dp(24).toFloat()
            addView(card, LayoutParams(-1, -1))
        }
        addView(glassCard, LayoutParams(dp(380), -1, Gravity.END).apply {
            topMargin = dp(22); bottomMargin = dp(22); marginEnd = dp(22)
        })
    }

    // ---------------------------------------------------------------- navigation

    fun open(page: Page) {
        stack.clear(); stack.add(page)
        visibility = View.VISIBLE
        glassCard.enabledGlass = prefs.glass
        glassCard.tint = if (prefs.glass) 0x4D0B0F1A else 0x00000000
        glassCard.translationX = dp(420).toFloat(); alpha = 0f
        animate().alpha(1f).setDuration(160).start()
        glassCard.animate().translationX(0f).setDuration(240)
            .setUpdateListener { glassCard.invalidate() }
            .withEndAction { glassCard.invalidate() }.start()
        render()
    }

    fun push(page: Page) {
        stack.lastOrNull()?.focusIndex = focusedIndex()
        stack.add(page); render()
    }

    fun back() {
        val leaving = stack.lastOrNull()
        if (leaving != null) onBackFromPage?.invoke(leaving)
        if (stack.size > 1) { stack.removeAt(stack.size - 1); render() } else close()
    }

    fun close() {
        if (!isOpen) return
        glassCard.animate().translationX(dp(420).toFloat()).setDuration(180)
            .setUpdateListener { glassCard.invalidate() }.start()
        animate().alpha(0f).setDuration(180).withEndAction {
            visibility = View.GONE; stack.clear(); onClosed?.invoke()
        }.start()
    }

    fun refresh() {
        stack.lastOrNull()?.focusIndex = focusedIndex()
        render()
    }

    private fun focusedIndex(): Int {
        val f = findFocus() ?: return 0
        rowViews.forEachIndexed { i, v ->
            if (v != null && (v == f || isAncestor(v, f))) return i
        }
        return 0
    }

    private fun isAncestor(parent: View, child: View): Boolean {
        var p = child.parent
        while (p != null) { if (p == parent) return true; p = p.parent }
        return false
    }

    // ---------------------------------------------------------------- drawing

    private fun render() {
        val page = stack.lastOrNull() ?: return
        header.text = page.title
        sub.text = page.subtitle ?: ""
        sub.visibility = if (page.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        items = page.build()
        list.removeAllViews()
        rowViews = MutableList(items.size) { null }

        // consecutive rows share one rounded group; Info/Gap items sit between groups
        var group: LinearLayout? = null
        items.forEachIndexed { i, item ->
            when (item) {
                is InfoItem -> { group = null; list.addView(infoView(item)) }
                is GapItem -> { group = null }
                else -> {
                    val g = group ?: newGroup().also { group = it; list.addView(it) }
                    if (g.childCount > 0) g.addView(divider(item))
                    val v = if (item is InputItem) makeInputRow(item) else makeRow(item, i)
                    rowViews[i] = v
                    g.addView(v)
                }
            }
        }
        val focusables = items.indices.filter { items[it] !is InfoItem && items[it] !is GapItem }
        val target = page.focusIndex.takeIf { it in focusables } ?: focusables.firstOrNull()
        list.post { target?.let { rowViews[it]?.requestFocus() } }
    }

    private fun newGroup() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        background = GradientDrawable().apply { cornerRadius = dpf(18f); setColor(0x1FFFFFFF) }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun divider(next: Item) = View(context).apply {
        setBackgroundColor(0x24FFFFFF)
        val inset = if (next.icon != 0 || next.badge != 0) dp(58) else dp(16)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2 + 1)).apply { marginStart = inset; marginEnd = dp(12) }
    }

    private fun infoView(item: InfoItem) = TextView(context).apply {
        text = item.title; textSize = 12.5f; setTextColor(0x99EBEBF5.toInt())
        typeface = Fonts.regular(context)
        setPadding(dp(12), dp(0), dp(12), dp(12))
    }

    private fun accentText(): Int {
        val c = prefs.accentColor
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (lum > 0.6) 0xFF111111.toInt() else Color.WHITE
    }

    private fun valueText(item: Item): String = when (item) {
        is NavItem -> (item.value?.invoke()?.let { "$it  " } ?: "") + "›"
        is ActionItem -> item.value?.invoke() ?: ""
        is ChoiceItem -> "‹  ${item.options.getOrElse(item.get()) { "" }}  ›"
        else -> ""
    }

    private fun makeRow(item: Item, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(14), 0)
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (item.badge != 0) {
            icon.setImageResource(if (item.badgeIcon != 0) item.badgeIcon else item.icon)
            icon.background = SquircleDrawable(item.badge)
            icon.setPadding(dp(6), dp(6), dp(6), dp(6))
            row.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(4) })
        } else if (item.icon != 0) {
            icon.setImageResource(item.icon)
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(5); marginEnd = dp(9) })
        } else {
            row.addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
        }
        val title = TextView(context).apply {
            text = item.title; textSize = 15.5f; maxLines = 1
            typeface = Fonts.regular(context); letterSpacing = -0.01f
        }
        val value = TextView(context).apply { text = valueText(item); textSize = 14f; maxLines = 1; typeface = Fonts.regular(context) }
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        row.addView(value, LinearLayout.LayoutParams(-2, -2))

        // iOS-style switch for toggles
        val track = if (item is ToggleItem) FrameLayout(context) else null
        val knob = if (item is ToggleItem) View(context) else null
        if (track != null && knob != null) {
            track.addView(knob, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER_VERTICAL))
            row.addView(track, LinearLayout.LayoutParams(dp(40), dp(24)).apply { marginStart = dp(10) })
        }

        fun paint(focused: Boolean) {
            val fg = if (focused) accentText() else Color.WHITE
            row.background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(if (focused) prefs.accentColor else Color.TRANSPARENT)
            }
            title.setTextColor(fg)
            if (item.badge == 0) icon.setColorFilter(fg) else icon.setColorFilter(Color.WHITE)
            value.setTextColor(if (focused) (fg and 0x00FFFFFF) or (0xB3 shl 24) else 0x8CEBEBF5.toInt())
            if (track != null && knob != null && item is ToggleItem) {
                val on = item.get()
                track.background = GradientDrawable().apply {
                    cornerRadius = dpf(12f); setColor(if (on) 0xFF30D158.toInt() else 0x4D787880)
                }
                track.setPadding(dp(3), 0, dp(3), 0)
                knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                (knob.layoutParams as FrameLayout.LayoutParams).gravity =
                    Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
                knob.requestLayout()
            }
        }
        paint(false)
        row.setOnFocusChangeListener { _, has ->
            paint(has)
            row.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f)
                .translationZ(if (has) dpf(6f) else 0f).setDuration(140).start()
        }
        fun update() { value.text = valueText(item); paint(row.hasFocus()) }

        row.setOnClickListener {
            when (item) {
                is NavItem -> push(item.page())
                is ActionItem -> { item.run(); if (isOpen && stack.isNotEmpty()) update() }
                is ChoiceItem -> { item.set((item.get() + 1) % item.options.size); update() }
                is ToggleItem -> { item.set(!item.get()); update() }
                else -> {}
            }
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (item is ChoiceItem) { item.set((item.get() - 1 + item.options.size) % item.options.size); update() }
                    else if (item is ToggleItem) { item.set(!item.get()); update() }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (item) {
                        is ChoiceItem -> { item.set((item.get() + 1) % item.options.size); update() }
                        is ToggleItem -> { item.set(!item.get()); update() }
                        is NavItem -> push(item.page())
                        else -> {}
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> index == firstFocusable()
                KeyEvent.KEYCODE_DPAD_DOWN -> index == lastFocusable()
                else -> false
            }
        }
        return row
    }

    private fun firstFocusable() = items.indexOfFirst { it !is InfoItem && it !is GapItem }
    private fun lastFocusable() = items.indexOfLast { it !is InfoItem && it !is GapItem }

    private fun makeInputRow(item: InputItem): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(TextView(context).apply {
            text = item.title; textSize = 12.5f; setTextColor(0x99EBEBF5.toInt())
            typeface = Fonts.medium(context)
            setPadding(dp(4), 0, 0, dp(6))
        })
        val edit = EditText(context).apply {
            setText(item.initial); setSelection(item.initial.length)
            setTextColor(Color.WHITE); textSize = 16f; typeface = Fonts.regular(context)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dpf(12f); setColor(0x22FFFFFF); setStroke(dp(2), 0x33FFFFFF) }
            setOnFocusChangeListener { v, has ->
                (v.background as GradientDrawable).setStroke(dp(2), if (has) prefs.accentColor else 0x33FFFFFF)
            }
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                    item.onDone(text.toString().trim()); true
                } else false
            }
        }
        box.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return box
    }
}
