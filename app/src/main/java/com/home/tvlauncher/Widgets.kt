package com.home.tvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ model

data class WidgetSpec(
    val type: String,          // "video" | "clock" | "calendar"
    var on: Boolean,
    var style: Int,
    var size: Int,             // 0 small, 1 medium, 2 large
    var x: Float,              // 0..1 across the free space
    var y: Float,              // 0..1 down the free space
    var source: Int = 0,       // video: 0 auto, 1 Netflix, 2 Apple TV, 3 YouTube
) {
    fun toJson() = JSONObject().put("type", type).put("on", on).put("style", style).put("size", size)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("source", source)

    companion object {
        fun from(o: JSONObject) = WidgetSpec(o.getString("type"), o.optBoolean("on"), o.optInt("style"), o.optInt("size", 1),
            o.optDouble("x", 0.05).toFloat(), o.optDouble("y", 0.08).toFloat(), o.optInt("source"))

        val DEFAULTS get() = listOf(
            WidgetSpec("video", true, 0, 1, 0.03f, 0.07f),
            WidgetSpec("clock", false, 0, 1, 0.97f, 0.05f),
            WidgetSpec("calendar", false, 0, 1, 0.97f, 0.42f),
        )
        val CLOCK_STYLES = listOf("Big digital", "Analogue", "Stacked", "In words", "Minimal")
        val CALENDAR_STYLES = listOf("Month", "Today", "Agenda")
        val VIDEO_SOURCES = listOf("Auto (rotate)", "Netflix", "Apple TV", "YouTube")
        val SIZES = listOf("Small", "Medium", "Large")
        val NAMES = mapOf("video" to "Video", "clock" to "Clock", "calendar" to "Calendar")
    }
}

fun Prefs.widgets(): MutableList<WidgetSpec> {
    val raw = sp.getString("widgets", null) ?: return WidgetSpec.DEFAULTS.toMutableList()
    return try {
        val a = JSONArray(raw); val list = (0 until a.length()).map { WidgetSpec.from(a.getJSONObject(it)) }.toMutableList()
        for (d in WidgetSpec.DEFAULTS) if (list.none { it.type == d.type }) list += d
        list
    } catch (e: Exception) { WidgetSpec.DEFAULTS.toMutableList() }
}

fun Prefs.saveWidgets(list: List<WidgetSpec>) {
    val a = JSONArray(); list.forEach { a.put(it.toJson()) }
    sp.edit().putString("widgets", a.toString()).apply()
}

// ------------------------------------------------------------------ TV data (previews + continue watching)

object TvData {
    data class Item(val pkg: String, val title: String, val video: String?, val poster: String?, val intent: String?, val watchNext: Boolean)

    /** Reads trailers/previews and "continue watching" that apps publish for the home screen. */
    fun load(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val diag = StringBuilder("Home launcher diagnostics ${Date()}\n")
        for ((uri, wn) in listOf(Uri.parse("content://android.media.tv/watch_next_program") to true,
                                  Uri.parse("content://android.media.tv/preview_program") to false)) {
            try {
                ctx.contentResolver.query(uri, arrayOf("package_name", "title", "preview_video_uri", "poster_art_uri", "intent_uri"),
                    null, null, null)?.use { c ->
                    diag.append("$uri -> ${c.count} rows\n")
                    while (c.moveToNext()) {
                        out += Item(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2), c.getString(3), c.getString(4), wn)
                    }
                } ?: diag.append("$uri -> null cursor\n")
            } catch (e: Exception) { diag.append("$uri -> ${e.javaClass.simpleName}: ${e.message}\n") }
        }
        diag.append("by app: ${out.groupingBy { it.pkg }.eachCount()}\n")
        diag.append("with video: ${out.count { !it.video.isNullOrBlank() }}\n")
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "launcher-diag.txt").writeText(diag.toString())
        } catch (_: Exception) {}
        return out
    }
}

// ------------------------------------------------------------------ the layer that holds and positions widgets

class WidgetLayer(private val act: android.app.Activity, private val prefs: Prefs) : FrameLayout(act) {
    private var signature = ""
    private val live = ArrayList<WidgetView>()
    var movingType: String? = null
        set(v) { field = v; live.forEach { it.setMoving(it.spec.type == v) }; invalidate() }

    /** Fixed things widgets must not cover (the settings button, the corner clock) — in this layer's coordinates. */
    var obstacles: () -> List<android.graphics.Rect> = { emptyList() }
    /** Top of the apps/HDMI rows block. */
    var rowsTop: () -> Int = { height }
    /** Called when widgets at the bottom need the rows to move aside: (space on left, space on right) in px. */
    var onRowsReserve: (Int, Int) -> Unit = { _, _ -> }

    private val dd = resources.displayMetrics.density
    // same margins as the rest of the home screen
    private val padL = 40 * dd
    private val padR = 40 * dd
    private val padT = 26 * dd
    private val padB = 26 * dd
    private val gap = 16 * dd
    private var lastReserve = -1 to -1
    private val guide = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * dd
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * dd, 8 * dd), 0f)
    }

    init { clipChildren = false; clipToPadding = false; setWillNotDraw(false) }

    fun setVideoSound(on: Boolean) = live.filterIsInstance<VideoWidget>().forEach { it.setSound(on) }

    /** Rebuild only when something about the widgets changed (so videos don't restart needlessly). */
    fun refresh(force: Boolean = false) {
        val specs = prefs.widgets()
        val sig = specs.joinToString("|") { "${it.type}${it.on}${it.style}${it.size}${it.source}" } + prefs.accent + prefs.glass
        if (!force && sig == signature) {
            // positions may have changed (e.g. restored backup)
            for (v in live) specs.firstOrNull { it.type == v.spec.type }?.let { v.spec.x = it.x; v.spec.y = it.y }
            requestLayout(); return
        }
        signature = sig
        live.forEach { it.release() }; live.clear(); removeAllViews()
        for (s in specs.filter { it.on }) {
            val v = when (s.type) {
                "video" -> VideoWidget(act, prefs, s)
                "clock" -> ClockWidget(act, prefs, s)
                else -> CalendarWidget(act, prefs, s)
            }
            live += v
            addView(v, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        movingType?.let { t -> live.forEach { it.setMoving(it.spec.type == t) } }
    }

    // ---------------------------------------------------------------- geometry

    private val areaW get() = width - padL - padR
    private val areaH get() = height - padT - padB

    private fun pxX(v: View, x: Float) = padL + x * (areaW - v.width).coerceAtLeast(0f)
    private fun pxY(v: View, y: Float) = padT + y * (areaH - v.height).coerceAtLeast(0f)
    private fun fracX(v: View, px: Float) = ((px - padL) / (areaW - v.width).coerceAtLeast(1f)).coerceIn(0f, 1f)
    private fun fracY(v: View, py: Float) = ((py - padT) / (areaH - v.height).coerceAtLeast(1f)).coerceIn(0f, 1f)

    private fun rectOf(v: View, x: Float, y: Float) = RectF(x, y, x + v.width, y + v.height)

    /** Everything a widget must keep clear of: other widgets + fixed elements. */
    private fun blockers(except: View): List<RectF> =
        live.filter { it != except }.map { rectOf(it, it.translationX, it.translationY) } +
            obstacles().map { RectF(it) }

    private fun hits(r: RectF, blockers: List<RectF>): RectF? {
        val inset = RectF(r.left - gap / 2, r.top - gap / 2, r.right + gap / 2, r.bottom + gap / 2)
        return blockers.firstOrNull { RectF.intersects(inset, it) }
    }

    private fun inBounds(v: View, x: Float, y: Float) =
        x >= padL - 1 && y >= padT - 1 && x + v.width <= width - padR + 1 && y + v.height <= height - padB + 1

    /**
     * Move by (dx, dy) pixels. If that would cover something, jump to just past it in the same
     * direction; if there's no room past it, stay put. Crossing an edge or the centre snaps to it.
     */
    fun nudgePx(type: String, dx: Float, dy: Float) {
        val v = live.firstOrNull { it.spec.type == type } ?: return
        val bl = blockers(v)
        var x = v.translationX + dx; var y = v.translationY + dy
        x = x.coerceIn(padL, (width - padR - v.width).coerceAtLeast(padL))
        y = y.coerceIn(padT, (height - padB - v.height).coerceAtLeast(padT))
        var guard = 0
        while (guard++ < 10) {
            val h = hits(rectOf(v, x, y), bl) ?: break
            when {
                dx > 0 -> x = h.right + gap
                dx < 0 -> x = h.left - gap - v.width
                dy > 0 -> y = h.bottom + gap
                dy < 0 -> y = h.top - gap - v.height
                else -> break
            }
            if (!inBounds(v, x, y)) { x = v.translationX; y = v.translationY; break }
        }
        var fx = fracX(v, x); var fy = fracY(v, y)
        fx = snap(v.spec.x, fx); fy = snap(v.spec.y, fy)
        // don't let snapping land on top of something
        if (hits(rectOf(v, pxX(v, fx), pxY(v, fy)), bl) != null) { fx = fracX(v, x); fy = fracY(v, y) }
        save(v, fx, fy)
    }

    /** Jump to a preset spot (0..1 on each axis), then slide to the nearest free place if it's taken. */
    fun place(type: String, x: Float, y: Float) {
        val v = live.firstOrNull { it.spec.type == type }
        if (v == null) { val l = prefs.widgets(); l.firstOrNull { it.type == type }?.let { it.x = x; it.y = y }; prefs.saveWidgets(l); return }
        val bl = blockers(v)
        val bx = pxX(v, x); val by = pxY(v, y)
        var best: Pair<Float, Float>? = null
        val step = 12 * dd
        loop@ for (ring in 0..80) {
            val r = ring * step
            val cands = if (ring == 0) listOf(0f to 0f) else listOf(r to 0f, -r to 0f, 0f to r, 0f to -r, r to r, -r to r, r to -r, -r to -r)
            for ((ox, oy) in cands) {
                val cx = bx + ox; val cy = by + oy
                if (!inBounds(v, cx, cy)) continue
                if (hits(rectOf(v, cx, cy), bl) == null) { best = cx to cy; break@loop }
            }
        }
        val (fx, fy) = best?.let { fracX(v, it.first) to fracY(v, it.second) } ?: (x to y)
        save(v, fx, fy)
    }

    private fun save(v: WidgetView, fx: Float, fy: Float) {
        val list = prefs.widgets()
        list.firstOrNull { it.type == v.spec.type }?.let { it.x = fx; it.y = fy }
        prefs.saveWidgets(list)
        v.spec.x = fx; v.spec.y = fy
        requestLayout(); invalidate()
    }

    /** Magnetic edges/centre: crossing 0, 0.5 or 1 stops exactly on it. */
    private fun snap(old: Float, new: Float): Float {
        for (t in floatArrayOf(0f, 0.5f, 1f)) {
            if ((old < t && new >= t) || (old > t && new <= t)) return t
        }
        return new
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        for (v in live) {
            v.translationX = pxX(v, v.spec.x)
            v.translationY = pxY(v, v.spec.y)
        }
        // widgets sitting down by the rows push the rows aside (rows then scroll in the space left)
        val top = rowsTop()
        var left = 0; var right = 0
        for (v in live) {
            val rr = rectOf(v, v.translationX, v.translationY)
            if (rr.bottom > top + 8 * dd) {
                if (rr.centerX() < width / 2f) left = maxOf(left, (rr.right + gap).toInt())
                else right = maxOf(right, (width - rr.left + gap).toInt())
            }
        }
        if (left to right != lastReserve) { lastReserve = left to right; post { onRowsReserve(left, right) } }
        Glass.invalidateAll()
    }

    /** While moving: dashed guide lines where the widget lines up with the other elements. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val v = live.firstOrNull { it.spec.type == movingType } ?: return
        guide.color = (prefs.accentColor and 0x00FFFFFF) or (0xCC shl 24)
        val x0 = v.translationX; val y0 = v.translationY
        val x1 = x0 + v.width; val y1 = y0 + v.height
        if (v.spec.x == 0f) canvas.drawLine(padL, 0f, padL, height.toFloat(), guide)
        if (v.spec.x == 1f) canvas.drawLine(width - padR, 0f, width - padR, height.toFloat(), guide)
        if (v.spec.x == 0.5f) canvas.drawLine((x0 + x1) / 2, 0f, (x0 + x1) / 2, height.toFloat(), guide)
        if (v.spec.y == 0f) canvas.drawLine(0f, padT, width.toFloat(), padT, guide)
        if (v.spec.y == 1f) canvas.drawLine(0f, height - padB, width.toFloat(), height - padB, guide)
        if (v.spec.y == 0.5f) canvas.drawLine(0f, (y0 + y1) / 2, width.toFloat(), (y0 + y1) / 2, guide)
    }

    fun tick() = live.forEach { it.tick() }
    fun pause() = live.forEach { it.pause() }
    fun resume() = live.forEach { it.resume() }
}

// ------------------------------------------------------------------ base widget

abstract class WidgetView(ctx: Context, val prefs: Prefs, val spec: WidgetSpec) : FrameLayout(ctx) {
    protected val d = resources.displayMetrics.density
    protected fun dp(v: Float) = (v * d).toInt()
    protected val scale get() = listOf(0.75f, 1f, 1.35f)[spec.size.coerceIn(0, 2)]
    private var moving = false

    init { clipChildren = false }

    fun setMoving(m: Boolean) {
        moving = m
        foreground = if (m) GradientDrawable().apply {
            cornerRadius = 24 * d; setColor(Color.TRANSPARENT); setStroke(dp(3f), prefs.accentColor, 14 * d, 8 * d)
        } else null
        animate().scaleX(if (m) 1.03f else 1f).scaleY(if (m) 1.03f else 1f).setDuration(160).start()
    }

    open fun tick() {}
    open fun pause() {}
    open fun resume() {}
    open fun release() {}

    protected fun text(size: Float, font: android.graphics.Typeface, color: Int = Color.WHITE) = TextView(context).apply {
        textSize = size * scale; typeface = font; setTextColor(color)
        setShadowLayer(10f, 0f, 2f, 0x99000000.toInt()); includeFontPadding = false
    }

    protected fun glassCard(radius: Float = 24f): GlassFrame = GlassFrame(context, radius * d).apply {
        enabledGlass = prefs.glass; elevation = 14 * d
    }
}

// ------------------------------------------------------------------ clock widget

class ClockWidget(ctx: Context, prefs: Prefs, spec: WidgetSpec) : WidgetView(ctx, prefs, spec) {
    private var time: TextView? = null
    private var sub: TextView? = null
    private var analog: AnalogClock? = null

    init {
        when (spec.style) {
            1 -> {
                val size = dp(170f * scale)
                val face = glassCard(size / d / 2f)
                analog = AnalogClock(context, prefs)
                face.addView(analog, LayoutParams(size, size))
                addView(face, LayoutParams(size, size))
            }
            else -> {
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val t = when (spec.style) {
                    2 -> text(76f, Fonts.bold(context)).apply { setLineSpacing(0f, 0.86f); letterSpacing = -0.04f }
                    3 -> text(30f, Fonts.semibold(context)).apply { maxWidth = dp(360f * scale) }
                    4 -> text(26f, Fonts.medium(context))
                    else -> text(88f, Fonts.semibold(context)).apply { letterSpacing = -0.04f }
                }
                val s = text(if (spec.style == 4) 14f else 17f, Fonts.medium(context), 0xE6FFFFFF.toInt())
                if (spec.style != 4) box.addView(s)
                box.addView(t)
                if (spec.style == 4) box.addView(s)
                time = t; sub = s
                addView(box)
            }
        }
        tick()
    }

    override fun tick() {
        val now = Date()
        val h24 = prefs.clock24
        analog?.invalidate()
        val t = time ?: return
        when (spec.style) {
            2 -> t.text = SimpleDateFormat(if (h24) "HH\nmm" else "hh\nmm", Locale.getDefault()).format(now)
            3 -> t.text = inWords(now)
            4 -> t.text = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(now)
            else -> t.text = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        }
        sub?.text = SimpleDateFormat(if (spec.style == 4) "EEEE d MMMM" else "EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private fun inWords(d: Date): String {
        val c = Calendar.getInstance().apply { time = d }
        val m = c.get(Calendar.MINUTE); var h = c.get(Calendar.HOUR)
        val names = listOf("twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven")
        val r = ((m + 2) / 5) * 5
        if (r > 30) h = (h + 1) % 12
        val hour = names[h % 12]
        val phrase = when (r) {
            0, 60 -> "$hour o'clock"; 5 -> "five past $hour"; 10 -> "ten past $hour"; 15 -> "quarter past $hour"
            20 -> "twenty past $hour"; 25 -> "twenty-five past $hour"; 30 -> "half past $hour"; 35 -> "twenty-five to $hour"
            40 -> "twenty to $hour"; 45 -> "quarter to $hour"; 50 -> "ten to $hour"; else -> "five to $hour"
        }
        return "It's " + phrase
    }
}

class AnalogClock(ctx: Context, private val prefs: Prefs) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2; val cy = height / 2f; val r = w / 2 * 0.86f
        val d = resources.displayMetrics.density
        p.style = Paint.Style.STROKE
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            p.color = if (i % 3 == 0) Color.WHITE else 0x99FFFFFF.toInt(); p.strokeWidth = (if (i % 3 == 0) 3f else 1.6f) * d
            val r1 = r * (if (i % 3 == 0) 0.82f else 0.88f)
            c.drawLine(cx + (r1 * Math.sin(a)).toFloat(), cy - (r1 * Math.cos(a)).toFloat(),
                cx + (r * Math.sin(a)).toFloat(), cy - (r * Math.cos(a)).toFloat(), p)
        }
        val cal = Calendar.getInstance()
        val mins = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val hrs = (cal.get(Calendar.HOUR) + mins / 60f)
        fun hand(angleDeg: Float, len: Float, width: Float, color: Int) {
            val a = Math.toRadians(angleDeg.toDouble()); p.color = color; p.strokeWidth = width * d
            c.drawLine(cx, cy, cx + (len * Math.sin(a)).toFloat(), cy - (len * Math.cos(a)).toFloat(), p)
        }
        hand(hrs * 30f, r * 0.5f, 5f, Color.WHITE)
        hand(mins * 6f, r * 0.74f, 3.5f, Color.WHITE)
        hand(cal.get(Calendar.SECOND) * 6f, r * 0.8f, 1.6f, prefs.accentColor.takeIf { it != Color.WHITE } ?: 0xFFFF9F0A.toInt())
        p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(cx, cy, 4 * d, p)
        postInvalidateDelayed(1000)
    }
}

// ------------------------------------------------------------------ calendar widget

class CalendarWidget(ctx: Context, prefs: Prefs, spec: WidgetSpec) : WidgetView(ctx, prefs, spec) {
    private val card = glassCard()
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var lastDay = -1

    init {
        body.setPadding(dp(18f * scale), dp(14f * scale), dp(18f * scale), dp(14f * scale))
        card.addView(body, LayoutParams(-2, -2))
        addView(card, LayoutParams(-2, -2))
        tick()
    }

    override fun tick() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today == lastDay) return
        lastDay = today
        body.removeAllViews()
        when (spec.style) {
            1 -> buildToday()
            2 -> buildAgenda()
            else -> buildMonth()
        }
    }

    private fun buildMonth() {
        val c = Calendar.getInstance()
        val todayNum = c.get(Calendar.DAY_OF_MONTH)
        body.addView(text(17f, Fonts.bold(context), prefs.accentColor.takeIf { it != Color.WHITE } ?: 0xFFFF453A.toInt()).apply {
            text = SimpleDateFormat("MMMM", Locale.getDefault()).format(c.time).uppercase(); letterSpacing = 0.04f
            setPadding(0, 0, 0, dp(8f * scale))
        })
        val cell = dp(30f * scale)
        val head = LinearLayout(context)
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { l ->
            head.addView(text(11f, Fonts.semibold(context), 0x99FFFFFF.toInt()).apply { text = l; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(cell, -2))
        }
        body.addView(head)
        c.set(Calendar.DAY_OF_MONTH, 1)
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        var day = 1 - offset
        while (day <= days) {
            val row = LinearLayout(context)
            for (i in 0 until 7) {
                val n = day
                row.addView(text(13f, Fonts.medium(context)).apply {
                    gravity = Gravity.CENTER
                    if (n in 1..days) text = n.toString()
                    if (n == todayNum) {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF453A.toInt()) }
                        typeface = Fonts.bold(context); setShadowLayer(0f, 0f, 0f, 0)
                    } else if (i >= 5) setTextColor(0x99FFFFFF.toInt())
                }, LinearLayout.LayoutParams(cell, cell))
                day++
            }
            body.addView(row)
        }
    }

    private fun buildToday() {
        val c = Calendar.getInstance()
        body.addView(text(15f, Fonts.semibold(context), 0xFFFF453A.toInt()).apply {
            text = SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time).uppercase(); letterSpacing = 0.04f
        })
        body.addView(text(64f, Fonts.light(context)).apply { text = c.get(Calendar.DAY_OF_MONTH).toString(); letterSpacing = -0.03f })
        body.addView(text(14f, Fonts.medium(context), 0xCCFFFFFF.toInt()).apply {
            text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
        })
        events(1).firstOrNull()?.let { (t, title) ->
            body.addView(text(13f, Fonts.medium(context)).apply {
                text = "$t  $title"; maxLines = 1; setPadding(0, dp(8f * scale), 0, 0); maxWidth = dp(220f * scale)
            })
        }
    }

    private fun buildAgenda() {
        body.addView(text(15f, Fonts.bold(context)).apply { text = "Up next"; setPadding(0, 0, 0, dp(6f * scale)) })
        val ev = events(4)
        if (ev.isEmpty()) {
            body.addView(text(13f, Fonts.regular(context), 0xB3FFFFFF.toInt()).apply {
                text = if (hasCalendar()) "Nothing on your calendar" else "Calendar access is off"
            })
        }
        for ((t, title) in ev) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4f * scale), 0, dp(4f * scale)) }
            row.addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = 2 * d; setColor(prefs.accentColor.takeIf { it != Color.WHITE } ?: 0xFF0A84FF.toInt()) }
            }, LinearLayout.LayoutParams(dp(4f), dp(28f * scale)).apply { marginEnd = dp(10f) })
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(13f, Fonts.semibold(context)).apply { text = title; maxLines = 1; maxWidth = dp(240f * scale) })
            col.addView(text(11f, Fonts.regular(context), 0xB3FFFFFF.toInt()).apply { text = t })
            row.addView(col)
            body.addView(row)
        }
    }

    private fun hasCalendar() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** (time label, title) of upcoming events in the next 7 days, if calendar access is granted. */
    private fun events(max: Int): List<Pair<String, String>> {
        if (!hasCalendar()) return emptyList()
        return try {
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(now.toString()).appendPath((now + 7L * 86_400_000).toString()).build()
            val out = ArrayList<Pair<String, String>>()
            context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val begin = Date(c.getLong(1))
                    val label = if (c.getInt(2) == 1) SimpleDateFormat("EEE", Locale.getDefault()).format(begin) + " · all day"
                    else SimpleDateFormat(if (prefs.clock24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()).format(begin)
                    out += label to (c.getString(0) ?: "(no title)")
                }
            }
            out
        } catch (e: Exception) { emptyList() }
    }
}

// ------------------------------------------------------------------ video widget

/** Small helper that appends a line to Download/launcher-diag.txt (for troubleshooting on the TV). */
object Diag {
    fun log(line: String) {
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "launcher-diag.txt")
                .appendText("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $line\n")
        } catch (_: Exception) {}
    }
}

/**
 * Plays previews/trailers in a rounded glass card, muted. Sources, best first:
 *  1. previews / continue-watching that apps publish to the TV (if the TV lets us read them)
 *  2. Apple's official trailer files for current top movies (played by the TV's own video player)
 *  3. Netflix's official trailers via the YouTube player
 * OK opens the matching app (or the exact title for published previews).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class VideoWidget(private val act: android.app.Activity, prefs: Prefs, spec: WidgetSpec) : WidgetView(act, prefs, spec) {
    private val handler = Handler(Looper.getMainLooper())
    private val card = glassCard(26f)
    private val media = FrameLayout(act)
    private val caption: TextView
    private val badge = ImageView(act)
    private lateinit var strip: LinearLayout
    private val captionHandler = Handler(Looper.getMainLooper())
    private val hideCaption = Runnable { if (!hasFocus()) strip.animate().alpha(0f).setDuration(600).start() }
    private var texture: android.view.TextureView? = null
    private var player: android.media.MediaPlayer? = null
    private var web: WebView? = null
    private var poster: ImageView? = null
    private var published: List<TvData.Item> = emptyList()
    private var trailers: List<Pair<String, String>> = emptyList()   // (title, url)
    private val storePages = HashMap<String, String>()               // trailer url -> store page
    private var pubIndex = -1
    private var trailerIndex = -1
    private var round = 0
    private var currentOpen: () -> Unit = {}
    private var paused = false
    private var sound = false

    /** Volume button pressed on Home: let the trailer be heard. */
    fun setSound(on: Boolean) {
        if (sound == on) return
        sound = on
        val v = if (on) 1f else 0f
        try { player?.setVolume(v, v) } catch (_: Exception) {}
        web?.evaluateJavascript(if (on) "wantSound=true;if(window.player&&player.unMute){player.unMute();player.setVolume(100);}"
            else "wantSound=false;if(window.player&&player.mute){player.mute();}", null)
        if (on) setCaption(caption.text.toString().removeSuffix("  ·  Sound on") + "  ·  Sound on")
    }

    private val NETFLIX = "com.netflix.ninja"
    private val APPLE = "com.apple.atve.androidtv.appletv"
    private val YOUTUBE = "com.google.android.youtube.tv"

    init {
        val w = dp(400f * scale); val h = dp(225f * scale)
        val inner = 22 * d
        media.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, inner) }
        }
        media.clipToOutline = true
        media.setBackgroundColor(Color.BLACK)
        card.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        card.addView(media, LayoutParams(w, h))

        strip = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(14f), dp(26f), dp(14f), dp(10f))
        }
        strip.addView(badge, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(8f) })
        caption = text(13f, Fonts.semibold(act)).apply { maxLines = 1 }
        strip.addView(caption)
        media.addView(strip, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(card, LayoutParams(-2, -2))
        setCaption("Loading trailers…")

        isFocusable = true; isClickable = true
        setOnClickListener { currentOpen() }
        setOnFocusChangeListener { _, has ->
            if (has) showCaption() else captionHandler.postDelayed(hideCaption, 5000)
            animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).translationZ(if (has) 18 * d else 0f).setDuration(200).start()
            card.foreground = if (has) GradientDrawable().apply { cornerRadius = 26 * d; setColor(Color.TRANSPARENT); setStroke(dp(3f), prefs.accentColor) } else null
        }
        load()
    }

    private fun installed(pkg: String) = try { act.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    private fun openApp(pkg: String) { act.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    /** Which sources the user wants, in rotation order. */
    private fun plan(): List<String> = when (spec.source) {
        1 -> listOf("netflix")
        2 -> listOf("apple")
        3 -> listOf("youtube")
        else -> listOfNotNull("published", "apple".takeIf { installed(APPLE) } ?: "apple", "netflix".takeIf { installed(NETFLIX) })
    }

    private fun load() {
        Thread {
            published = try { TvData.load(act) } catch (e: Exception) { emptyList() }
            trailers = try { fetchAppleTrailers() } catch (e: Exception) { Diag.log("apple trailers failed: $e"); emptyList() }
            Diag.log("published=${published.size} appleTrailers=${trailers.size}")
            act.runOnUiThread { next() }
        }.start()
    }

    /** Apple's official trailer files for the current top movies (public iTunes feed). */
    private fun fetchAppleTrailers(): List<Pair<String, String>> {
        val conn = (URL("https://itunes.apple.com/us/rss/topmovies/limit=40/json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val entries = JSONObject(body).getJSONObject("feed").optJSONArray("entry") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val title = e.optJSONObject("im:name")?.optString("label") ?: continue
            val page = e.optJSONObject("id")?.optString("label") ?: ""
            val links = e.opt("link")
            val arr = when (links) { is JSONArray -> links; is JSONObject -> JSONArray().put(links); else -> continue }
            for (j in 0 until arr.length()) {
                val a = arr.getJSONObject(j).optJSONObject("attributes") ?: continue
                val href = a.optString("href")
                if (a.optString("type").startsWith("video") && href.startsWith("http")) { out += title to href; if (page.isNotBlank()) storePages[href] = page; break }
            }
        }
        return out.shuffled()
    }

    /** Advance to the next thing to play, following the plan. */
    private fun next() {
        if (paused) return
        handler.removeCallbacksAndMessages(null)
        val p = plan()
        repeat(p.size) {
            val src = p[round % p.size]
            when (src) {
                "published" -> if (playPublished()) return
                "apple" -> if (playApple()) return
                "netflix" -> { playYouTube(NETFLIX, "Netflix", "UUWOA1ZGywLbqmigxE4Qlvuw"); round++; return }
                "youtube" -> { if (playPublishedFrom(YOUTUBE)) return; playYouTube(APPLE, "Apple TV", "UU1Myj674wRVXB9I4c6Hm5zA"); round++; return }
            }
            round++
        }
        setCaption("No trailers available")
    }

    private fun playPublished(): Boolean {
        if (published.none { !it.video.isNullOrBlank() }) return false
        return playPublishedFrom(null)
    }

    private fun playPublishedFrom(pkg: String?): Boolean {
        val list = published.filter { !it.video.isNullOrBlank() && (pkg == null || it.pkg == pkg) }
        if (list.isEmpty()) return false
        pubIndex = (pubIndex + 1) % list.size
        val it = list[pubIndex]
        showBadge(it.pkg)
        setCaption((if (it.watchNext) "Continue watching · " else "") + it.title)
        currentOpen = {
            try {
                val i = if (!it.intent.isNullOrBlank()) Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME)
                    else act.packageManager.getLeanbackLaunchIntentForPackage(it.pkg)
                i?.let { x -> act.startActivity(x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } catch (_: Exception) {}
        }
        playFile(it.video!!) { round++; next() }
        return true
    }

    private fun playApple(): Boolean {
        if (trailers.isEmpty()) {
            // no store trailers (network hiccup) -> Apple TV+ trailers instead
            playYouTube(APPLE, "Apple TV", "UU1Myj674wRVXB9I4c6Hm5zA"); round++; return true
        }
        trailerIndex = (trailerIndex + 1) % trailers.size
        val (title, url) = trailers[trailerIndex]
        showBadge(APPLE)
        setCaption("Apple TV · $title")
        // find the movie's page in the Apple TV app (store page redirects to tv.apple.com)
        val key = "apple:$url"
        currentOpen = { openLink(APPLE, links[key]) }
        storePages[url]?.let { page ->
            if (!links.containsKey(key)) Thread {
                val link = try { appleTvLink(page) } catch (e: Exception) { Diag.log("apple link failed: $e"); null }
                Diag.log("apple '$title' -> ${link ?: "no link"}")
                handler.post { links[key] = link }
            }.start()
        }
        // one trailer per service, then hand over to the next service
        playFile(url) { round++; next() }
        return true
    }

    // --- native playback through the TV's own video decoder, clipped to the rounded card
    private fun playFile(url: String, onDone: () -> Unit) {
        clearMedia()
        val tv = android.view.TextureView(act)
        texture = tv
        media.addView(tv, 0, LayoutParams(-1, -1))
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                val mp = android.media.MediaPlayer()
                player = mp
                try {
                    mp.setSurface(android.view.Surface(st))
                    mp.setDataSource(act, Uri.parse(url))
                    mp.setVolume(if (sound) 1f else 0f, if (sound) 1f else 0f)
                    mp.setOnPreparedListener { m -> centerCrop(tv, m.videoWidth, m.videoHeight); if (!paused) m.start() }
                    mp.setOnCompletionListener { onDone() }
                    mp.setOnErrorListener { _, what, extra -> Diag.log("player error $what/$extra for $url"); handler.postDelayed({ onDone() }, 1500); true }
                    mp.prepareAsync()
                } catch (e: Exception) { Diag.log("player failed: $e"); handler.postDelayed({ onDone() }, 1500) }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    private fun centerCrop(tv: android.view.TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || tv.width == 0) return
        val viewW = tv.width.toFloat(); val viewH = tv.height.toFloat()
        val s = maxOf(viewW / vw, viewH / vh)
        val m = android.graphics.Matrix()
        m.setScale(vw * s / viewW, vh * s / viewH, viewW / 2, viewH / 2)
        tv.setTransform(m)
    }

    // --- Netflix (and Apple) official trailers via the YouTube player
    // the trailer list for the channel currently playing, driven one video at a time
    private var ytList: List<Pair<String, String>> = emptyList()   // (videoId, title)
    private var ytPos = -1
    private var ytFails = 0
    private var ytPkg = ""; private var ytLabel = ""; private var ytChannel = ""
    private val ytCache = HashMap<String, List<Pair<String, String>>>()
    private val ytPosMap = HashMap<String, Int>()

    /** Latest uploads of an official channel, from YouTube's public feed: (videoId, title). */
    private fun fetchChannel(channelId: String): List<Pair<String, String>> {
        val c = (URL("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
        }
        val xml = c.inputStream.bufferedReader().use { it.readText() }
        return Regex("""<entry>.*?<yt:videoId>([^<]+)</yt:videoId>.*?<title>([^<]*)</title>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] to android.text.Html.fromHtml(it.groupValues[2]).toString() }
            .filterNot { it.second.contains("#shorts", true) }
            .toList()
    }

    private fun playYouTube(pkg: String, label: String, playlist: String) {
        showBadge(pkg)
        setCaption("$label · Trailers")
        currentOpen = { openApp(pkg) }
        clearMedia()
        ytPkg = pkg; ytLabel = label; ytFails = 0
        val channelId = "UC" + playlist.removePrefix("UU")
        ytChannel = channelId
        Thread {
            val list = ytCache[channelId] ?: try { fetchChannel(channelId) } catch (e: Exception) { Diag.log("feed failed: $e"); emptyList() }
            if (list.isNotEmpty()) ytCache[channelId] = list
            handler.post {
                if (list.isEmpty()) { round++; next(); return@post }
                ytList = list; ytPos = ytPosMap[channelId] ?: -1
                startPlayer(pkg, label)
            }
        }.start()
    }

    /** Next trailer in the list (wraps round). Too many failures in a row -> move on to the other app. */
    private fun ytNext(failed: Boolean) {
        if (failed) ytFails++ else ytFails = 0
        if (ytFails >= 8) { Diag.log("$ytLabel trailers kept failing - switching"); ytFails = 0; round++; next(); return }
        if (ytList.isEmpty()) return
        ytPos = (ytPos + 1) % ytList.size
        ytPosMap[ytChannel] = ytPos
        val (id, title) = ytList[ytPos]
        web?.evaluateJavascript("loadId('$id')", null)
        onYouTubeVideo(ytPkg, ytLabel, id, title)
    }

    private fun startPlayer(pkg: String, label: String) {
        val wv = WebView(act).apply {
            isFocusable = false; isFocusableInTouchMode = false
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView?, r: android.webkit.WebResourceRequest?, e: android.webkit.WebResourceError?) {
                    if (r?.isForMainFrame == true) Diag.log("web error ${e?.errorCode} ${e?.description}")
                }
                override fun onRenderProcessGone(v: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    Diag.log("web renderer gone (low memory?)"); handler.post { clearMedia(); round++; next() }; return true
                }
            }
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun ready() { handler.post { ytNext(false) } }
                @android.webkit.JavascriptInterface
                fun playing(id: String, title: String) { handler.post { ytFails = 0; if (title.isNotBlank()) onYouTubeVideo(pkg, label, id, title) } }
                @android.webkit.JavascriptInterface
                fun ended() { handler.post { if (spec.source == 0) next() else ytNext(false) } }
                @android.webkit.JavascriptInterface
                fun failed(code: String) { handler.post { Diag.log("$label trailer skipped ($code)"); ytNext(true) } }
            }, "Android")
            webChromeClient = object : WebChromeClient() {
                override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean { Diag.log("yt: ${m?.message()}"); return true }
            }
        }
        web = wv
        media.addView(wv, 0, LayoutParams(-1, -1))
        val origin = "https://com.home.tvlauncher"
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;height:100%;background:#000;overflow:hidden}
              #wrap,#wrap iframe{position:absolute;inset:0;width:100%;height:100%;border:0;pointer-events:none}
              /* black curtain while loading and for the first moments (when YouTube flashes its title) */
              #cover{position:absolute;inset:0;background:#000;opacity:1;transition:opacity .6s;pointer-events:none}
            </style></head>
            <body><div id="wrap"></div><div id="cover"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var player = null; var wantSound = $sound; var waited = 0; var current = ''; var lift = null;
              function curtain(on){ document.getElementById('cover').style.opacity = on ? 1 : 0; }
              // a fresh, simple embed for each trailer (the clean look), wired up so we hear about errors
              function loadId(id){
                current = id; waited = 0; curtain(true); if (lift) clearTimeout(lift);
                document.getElementById('wrap').innerHTML =
                  '<iframe id="f" src="https://www.youtube.com/embed/' + id +
                  '?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&modestbranding=1&disablekb=1&fs=0&enablejsapi=1&origin=$origin"' +
                  ' allow="autoplay; encrypted-media" referrerpolicy="strict-origin-when-cross-origin"></iframe>';
                player = new YT.Player('f', { events:{
                  onReady:function(e){ if(wantSound){e.target.unMute();e.target.setVolume(100);} e.target.playVideo(); },
                  onStateChange:function(e){
                    if (e.data == 1) {
                      waited = 0;
                      if (lift) clearTimeout(lift);
                      lift = setTimeout(function(){ if (player && player.getPlayerState() == 1) curtain(false); }, 2500);
                      try{ var d=e.target.getVideoData(); Android.playing(d.video_id||current, d.title||''); }catch(x){}
                    }
                    if (e.data == 0) { curtain(true); Android.ended(); }
                  },
                  onError:function(e){ curtain(true); Android.failed('error ' + e.data); } } });
              }
              // not playing within ~15s (blocked in this country, removed, stuck) -> next trailer
              setInterval(function(){
                if (!player || !player.getPlayerState || !current) return;
                var st = player.getPlayerState();
                if (st == 1 || st == 2) { waited = 0; return; }
                waited += 3; if (waited >= 15) { waited = 0; Android.failed('stuck'); }
              }, 3000);
              function onYouTubeIframeAPIReady(){ Android.ready(); }
            </script></body></html>"""
        wv.loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
    }

    /** Show the title bar, then fade it out after 5 seconds for a clean look. */
    private fun setCaption(t: String) { caption.text = t; showCaption() }
    private fun showCaption() {
        captionHandler.removeCallbacks(hideCaption)
        strip.animate().alpha(1f).setDuration(250).start()
        captionHandler.postDelayed(hideCaption, 5000)
    }

    private val links = HashMap<String, String?>()   // YouTube id -> deep link into the app
    private var nowId = ""

    /** A trailer started: show its name, and find the link to that show inside the app. */
    private fun onYouTubeVideo(pkg: String, label: String, id: String, title: String) {
        if (id.isBlank() || id == nowId) return
        nowId = id
        if (title.isNotBlank()) setCaption("$label · " + title.substringBefore(" | ").substringBefore(" – Official").trim())
        currentOpen = { openLink(pkg, links[id]) }
        if (links.containsKey(id)) return
        Thread {
            val link = try { findAppLink(pkg, id) } catch (e: Exception) { Diag.log("link lookup failed: $e"); null }
            Diag.log("trailer $id '$title' -> ${link ?: "no link"}")
            handler.post { links[id] = link }
        }.start()
    }

    /** Read the trailer's YouTube page and pull out the "watch on Netflix / Apple TV" link from its description. */
    private fun findAppLink(pkg: String, id: String): String? {
        val c = (URL("https://www.youtube.com/watch?v=$id&hl=en").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        }
        val page = c.inputStream.bufferedReader().use { it.readText() }.replace("\\/", "/").replace("\\u0026", "&")
        if (pkg == NETFLIX) {
            Regex("""netflix\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\d{5,})""").find(page)?.let {
                return "https://www.netflix.com/title/${it.groupValues[1]}"
            }
            return null
        }
        // Apple: a direct tv.apple.com link, or an apple.co short link that redirects to one
        Regex("""https?://tv\.apple\.com/[A-Za-z0-9_\-./]+""").find(page)?.let { return it.value }
        Regex("""https?://apple\.co/[A-Za-z0-9_\-]+""").find(page)?.let { return resolveRedirect(it.value) }
        return null
    }

    /** Apple store movie page -> its tv.apple.com page (via redirect, or a link inside the page). */
    private fun appleTvLink(storePage: String): String? {
        resolveRedirect(storePage)?.let { return it }
        val c = (URL(storePage).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        }
        if (c.url.host.contains("tv.apple.com")) return c.url.toString()
        val body = c.inputStream.bufferedReader().use { it.readText() }
        return Regex("""https://tv\.apple\.com/[a-z]{2}/movie/[^"'\s<>]+""").find(body)?.value
    }

    private fun resolveRedirect(start: String): String? {
        var url = start
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; requestMethod = "HEAD"; connectTimeout = 6000; readTimeout = 6000
            }
            val loc = c.getHeaderField("Location") ?: return url.takeIf { it.contains("tv.apple.com") }
            url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
            if (url.contains("tv.apple.com")) return url
        }
        return url.takeIf { it.contains("tv.apple.com") }
    }

    /** Open the show inside its app if we found its link; otherwise just open the app. */
    private fun openLink(pkg: String, link: String?) {
        if (link != null) {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return
            } catch (e: Exception) { Diag.log("deep link failed: $e") }
        }
        openApp(pkg)
    }

    private fun showBadge(pkg: String) {
        try { badge.setImageDrawable(act.packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { badge.setImageDrawable(null) }
    }

    private fun clearMedia() {
        handler.removeCallbacksAndMessages(null)
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }; player = null
        texture?.let { media.removeView(it) }; texture = null
        web?.let { media.removeView(it); it.destroy() }; web = null
        poster?.let { media.removeView(it) }; poster = null
    }

    override fun pause() { sound = false; try { player?.setVolume(0f, 0f) } catch (_: Exception) {}; paused = true; player?.let { if (it.isPlaying) it.pause() }; web?.onPause() }
    override fun resume() {
        val wasPaused = paused; paused = false
        web?.onResume()
        val p = player
        if (p != null) { try { p.start() } catch (_: Exception) { next() } }
        else if (wasPaused && web == null) next()
    }
    override fun release() { captionHandler.removeCallbacksAndMessages(null); clearMedia() }
}
