package com.home.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Movie poster screen saver: full-screen key art from what's on Apple TV and Netflix,
 * one service at a time, slowly zooming, with the title, a short description and the time.
 * OK opens the title in its app; Left/Right flip through; any other button wakes the TV.
 */

// ------------------------------------------------------------------ data

data class Showpiece(
    val service: String,      // "apple" | "netflix"
    val title: String,
    val meta: String,         // e.g. "Drama · 2026"
    val blurb: String,
    val art: String?,         // landscape key art
    val poster: String?,      // portrait poster
    val link: String?,        // deep link into the app
) {
    fun toJson() = JSONObject().put("s", service).put("t", title).put("m", meta).put("b", blurb)
        .put("a", art ?: "").put("p", poster ?: "").put("l", link ?: "")
    companion object {
        fun from(o: JSONObject) = Showpiece(o.optString("s"), o.optString("t"), o.optString("m"), o.optString("b"),
            o.optString("a").ifBlank { null }, o.optString("p").ifBlank { null }, o.optString("l").ifBlank { null })
    }
}

object Showcase {
    const val APPLE = "com.apple.atve.androidtv.appletv"
    const val NETFLIX = "com.netflix.ninja"
    private const val NETFLIX_CHANNEL = "UCWOA1ZGywLbqmigxE4Qlvuw"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val MAX_AGE = 6 * 3600_000L

    @Volatile var apple: List<Showpiece> = emptyList(); private set
    @Volatile var netflix: List<Showpiece> = emptyList(); private set
    @Volatile private var refreshing = false
    private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "showcase.json")

    @Synchronized fun loadCache(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(file(c).readText())
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { Showpiece.from(a.getJSONObject(it)) } } ?: emptyList()
            apple = list("apple"); netflix = list("netflix")
        } catch (_: Exception) {}
    }

    private fun save(c: Context) {
        try {
            file(c).writeText(JSONObject().put("time", System.currentTimeMillis())
                .put("apple", JSONArray(apple.map { it.toJson() })).put("netflix", JSONArray(netflix.map { it.toJson() })).toString())
        } catch (_: Exception) {}
    }

    private fun age(c: Context) = try { System.currentTimeMillis() - JSONObject(file(c).readText()).optLong("time") } catch (_: Exception) { Long.MAX_VALUE }

    /** Fetch fresh titles in the background if the saved list is old (or `force`). */
    fun refreshIfStale(c: Context, force: Boolean = false) {
        val ctx = c.applicationContext
        loadCache(ctx)
        if (refreshing || (!force && age(ctx) < MAX_AGE && apple.isNotEmpty() && netflix.isNotEmpty())) return
        refreshing = true
        Thread {
            try {
                val a = try { fetchApple() } catch (e: Exception) { Diag.log("saver apple failed: $e"); emptyList() }
                if (a.isNotEmpty()) { apple = a; save(ctx) }
                val n = try { fetchNetflix() } catch (e: Exception) { Diag.log("saver netflix failed: $e"); emptyList() }
                if (n.isNotEmpty()) { netflix = n; save(ctx) }
                Diag.log("saver titles: apple=${apple.size} (${apple.count { it.art != null }} with art) netflix=${netflix.size} (${netflix.count { it.art != null }} with art)")
                apple.take(3).forEach { Diag.log("saver apple '${it.title}' art=${it.art} link=${it.link}") }
                netflix.take(3).forEach { Diag.log("saver netflix '${it.title}' art=${it.art} link=${it.link}") }
            } finally { refreshing = false }
        }.start()
    }

    // --- Apple: the top movies in the Apple TV store, with their artwork from tv.apple.com
    private fun fetchApple(): List<Showpiece> {
        val body = get("https://itunes.apple.com/us/rss/topmovies/limit=24/json")
        val entries = JSONObject(body).getJSONObject("feed").optJSONArray("entry") ?: return emptyList()
        val out = ArrayList<Showpiece>()
        for (i in 0 until entries.length()) {
            try {
                val e = entries.getJSONObject(i)
                val title = e.optJSONObject("im:name")?.optString("label") ?: continue
                val summary = e.optJSONObject("summary")?.optString("label").orEmpty()
                val genre = e.optJSONObject("category")?.optJSONObject("attributes")?.optString("label").orEmpty()
                val year = e.optJSONObject("im:releaseDate")?.optString("label")?.take(4).orEmpty()
                val imgs = e.optJSONArray("im:image")
                val poster = imgs?.let { it.getJSONObject(it.length() - 1).optString("label") }
                    ?.replace(Regex("""/\d+x\d+[a-z]*\.(png|jpg|jpeg|webp)$"""), "/800x1200bb.jpg")
                val page = e.optJSONObject("id")?.optString("label").orEmpty()
                var link: String? = null; var art: String? = null
                if (page.isNotBlank()) {
                    link = try { appleTvLink(page) } catch (_: Exception) { null }
                    if (link != null) art = try { ogImage(get(link))?.let { bigApple(it) } } catch (_: Exception) { null }
                }
                out += Showpiece("apple", title, listOf(genre, year).filter { it.isNotBlank() }.joinToString("  ·  "),
                    firstSentences(summary), art, poster, link)
            } catch (_: Exception) {}
        }
        return out
    }

    /** mzstatic images can be asked for at any size: ask for a full-HD one, same shape. */
    private fun bigApple(url: String): String {
        val m = Regex("""/(\d+)x(\d+)([a-z]*)\.(jpg|jpeg|png|webp)$""").find(url) ?: return url
        val w = m.groupValues[1].toFloat(); val h = m.groupValues[2].toFloat()
        if (w <= 0 || h <= 0 || w < h) return url
        val nh = (1920 * h / w).toInt()
        return url.substring(0, m.range.first) + "/1920x${nh}${m.groupValues[3]}.jpg"
    }

    private fun appleTvLink(storePage: String): String? {
        var url = storePage
        repeat(6) {
            if (url.contains("tv.apple.com")) return url
            val c = open(url).apply { instanceFollowRedirects = false; requestMethod = "HEAD" }
            val loc = c.getHeaderField("Location") ?: return@repeat
            url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
        }
        if (url.contains("tv.apple.com")) return url
        val body = try { get(storePage) } catch (_: Exception) { return null }
        return Regex("""https://tv\.apple\.com/[a-z]{2}/movie/[^"'\s<>]+""").find(body)?.value
    }

    // --- Netflix: the newest trailers on Netflix's channel, matched to their Netflix pages
    private fun fetchNetflix(): List<Showpiece> {
        val xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=$NETFLIX_CHANNEL")
        val vids = Regex("""<entry>.*?<yt:videoId>([^<]+)</yt:videoId>.*?<title>([^<]*)</title>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] to android.text.Html.fromHtml(it.groupValues[2]).toString() }
            .filterNot { it.second.contains("#shorts", true) }.take(15).toList()
        val out = ArrayList<Showpiece>(); val seen = HashSet<String>()
        val fallback = ArrayList<Showpiece>()
        for ((vid, vtitle) in vids) {
            try {
                val watch = get("https://www.youtube.com/watch?v=$vid&hl=en", cookie = true).replace("\\/", "/")
                val id = Regex("""netflix\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\d{5,})""").find(watch)?.groupValues?.get(1)
                if (id == null) {
                    fallback += Showpiece("netflix", cleanTrailerTitle(vtitle), "", "", "https://i.ytimg.com/vi/$vid/maxresdefault.jpg", null, null)
                    continue
                }
                if (!seen.add(id)) continue
                val link = "https://www.netflix.com/title/$id"
                val page = get(link)
                val title = (meta(page, "og:title") ?: vtitle).removePrefix("Watch ").substringBefore(" | Netflix").trim()
                val blurb = meta(page, "og:description").orEmpty()
                val art = ogImage(page)
                val year = Regex(""""(?:releaseYear|dateCreated|startDate)"\s*:\s*"?(\d{4})""").find(page)?.groupValues?.get(1).orEmpty()
                val genre = Regex(""""genre"\s*:\s*"([^"]+)"""").find(page)?.groupValues?.get(1).orEmpty()
                out += Showpiece("netflix", title, listOf(genre, year).filter { it.isNotBlank() }.joinToString("  ·  "),
                    firstSentences(blurb), art, null, link)
            } catch (e: Exception) { Diag.log("saver netflix $vid: $e") }
        }
        if (out.size < 4) out += fallback.take(6)
        return out
    }

    private fun cleanTrailerTitle(t: String) = t.substringBefore(" | ").substringBefore(" – Official").substringBefore(" - Official").trim()

    private fun firstSentences(s: String, max: Int = 220): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = cut.lastIndexOf(". ")
        return if (end > 80) cut.take(end + 1) else cut.substringBeforeLast(' ') + "…"
    }

    // --- tiny web helpers
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 12000
        setRequestProperty("User-Agent", UA); setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    }

    private fun get(url: String, cookie: Boolean = false): String {
        val c = open(url)
        if (cookie) c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        val v = Regex("""<meta[^>]+(?:property|name)="$p"[^>]*content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content="([^"]*)"[^>]*(?:property|name)="$p"""").find(html)?.groupValues?.get(1)
        return v?.let { android.text.Html.fromHtml(it).toString() }?.takeIf { it.isNotBlank() }
    }

    private fun ogImage(html: String) = meta(html, "og:image")

    /** Download (once) and decode an image to roughly screen size. */
    fun bitmap(c: Context, url: String, w: Int, h: Int): Bitmap? {
        val dir = File(c.cacheDir, "showcase").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".img")
        if (!f.exists() || f.length() == 0L) {
            try {
                val conn = open(url)
                if (conn.responseCode !in 200..299) return null
                val tmp = File(dir, f.name + ".part")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                tmp.renameTo(f)
                // keep the cache small
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
            } catch (e: Exception) { return null }
        }
        f.setLastModified(System.currentTimeMillis())
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) { f.delete(); return null }
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun open(c: Context, item: Showpiece) {
        val pkg = if (item.service == "apple") APPLE else NETFLIX
        if (item.link != null) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return
            } catch (_: Exception) {}
        }
        c.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

// ------------------------------------------------------------------ the view

class PosterView(context: Context) : FrameLayout(context) {
    private val prefs = Prefs(context)
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val screenW = resources.displayMetrics.widthPixels
    private val screenH = resources.displayMetrics.heightPixels

    private val slides = arrayOf(Slide(), Slide())
    private var front = 0
    private val overlay = FrameLayout(context)
    private val info = LinearLayout(context)
    private val badge = ImageView(context)
    private val service = TextView(context)
    private val title = TextView(context)
    private val metaText = TextView(context)
    private val blurb = TextView(context)
    private val pill = TextView(context)
    private val clock = TextView(context)
    private val date = TextView(context)
    private val clockBox = LinearLayout(context)

    private var history = ArrayList<Showpiece>()
    private var histPos = -1
    private var turn = 0
    private val pos = HashMap<String, Int>()
    private val order = HashMap<String, List<Showpiece>>()
    var current: Showpiece? = null; private set
    private var running = false
    private var loading = false
    private var generation = 0
    private var fails = 0

    private inner class Slide {
        val root = FrameLayout(context)
        val back = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val card = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        init {
            root.addView(back, LayoutParams(-1, -1))
            val ch = (screenH * 0.66f).toInt()
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18 * d) }
            }
            card.clipToOutline = true
            card.elevation = 30 * d
            root.addView(card, LayoutParams(ch * 2 / 3, ch, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = (screenW * 0.11f).toInt() })
            root.alpha = 0f
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        slides.forEach { addView(it.root, LayoutParams(-1, -1)) }

        // shading so the text always reads
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
        }, LayoutParams((screenW * 0.72f).toInt(), -1, Gravity.START))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, LayoutParams(-1, (screenH * 0.5f).toInt(), Gravity.BOTTOM))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0x00000000))
        }, LayoutParams(-1, (screenH * 0.25f).toInt(), Gravity.TOP))

        addView(overlay, LayoutParams(-1, -1))
        // shades and text always draw above the two picture layers
        for (i in 0 until childCount) getChildAt(i).let { c -> if (slides.none { it.root === c }) { c.outlineProvider = null; c.translationZ = 5f } }

        // service line
        info.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        badge.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 7 * d) }
        }
        badge.clipToOutline = true
        head.addView(badge, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(12f) })
        service.apply { textSize = 15f; typeface = Fonts.semibold(context); letterSpacing = 0.16f; setTextColor(0xD9FFFFFF.toInt()); shadow() }
        head.addView(service)
        info.addView(head)

        title.apply {
            textSize = 46f; typeface = Fonts.bold(context); setTextColor(Color.WHITE); maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; letterSpacing = -0.01f; setLineSpacing(0f, 0.95f); shadow()
        }
        info.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        metaText.apply { textSize = 17f; typeface = Fonts.medium(context); setTextColor(0xB3FFFFFF.toInt()); shadow() }
        info.addView(metaText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        blurb.apply {
            textSize = 18f; typeface = Fonts.regular(context); setTextColor(0xD9FFFFFF.toInt()); maxLines = 3
            ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f); shadow()
        }
        info.addView(blurb, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        pill.apply {
            textSize = 15f; typeface = Fonts.semibold(context); setTextColor(Color.WHITE)
            setPadding(dp(18f), dp(9f), dp(20f), dp(9f))
            background = GradientDrawable().apply { cornerRadius = 100 * d; setColor(0x33FFFFFF); setStroke(dp(1f), 0x4DFFFFFF) }
        }
        info.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22f) })
        val infoW = (screenW * 0.46f).toInt()
        title.maxWidth = infoW; blurb.maxWidth = infoW
        overlay.addView(info, LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = (screenW * 0.06f).toInt(); bottomMargin = (screenH * 0.1f).toInt()
        })

        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.gravity = Gravity.END
        clock.apply { textSize = 58f; typeface = Fonts.light(context); setTextColor(Color.WHITE); letterSpacing = -0.02f; shadow() }
        date.apply { textSize = 17f; typeface = Fonts.semibold(context); setTextColor(0xCCFFFFFF.toInt()); shadow() }
        clockBox.addView(clock); clockBox.addView(date)
        overlay.addView(clockBox, LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = (screenW * 0.05f).toInt(); topMargin = (screenH * 0.06f).toInt()
        })
        clockBox.visibility = if (prefs.saverClock) VISIBLE else GONE
        info.alpha = 0f
        isFocusable = true
    }

    private fun TextView.shadow() = setShadowLayer(12 * d, 0f, 2 * d, 0x80000000.toInt())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            main.postDelayed(this, 10_000)
        }
    }

    private val advance = Runnable { step(1) }

    fun start() {
        if (running) return
        running = true
        Showcase.loadCache(context)
        Showcase.refreshIfStale(context)
        main.post(tick)
        if (history.isEmpty()) showWallpaperFirst()
        step(1)
    }

    fun stop() {
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        slides.forEach { it.back.animate().cancel(); it.root.animate().cancel() }
    }

    /** While titles are still loading the first time, show the Home wallpaper. */
    private fun showWallpaperFirst() {
        Thread {
            val bm = try {
                prefs.wallpaperPath?.let { Targets.decodeScaled(it, screenW, screenH) }
                    ?: BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default)
            } catch (_: Throwable) { null }
            main.post { if (current == null && bm != null) present(null, bm, false) }
        }.start()
    }

    private fun sources(): List<String> = when (prefs.saverSource) { 1 -> listOf("apple"); 2 -> listOf("netflix"); else -> listOf("apple", "netflix") }

    private fun listFor(s: String): List<Showpiece> {
        val live = if (s == "apple") Showcase.apple else Showcase.netflix
        val cached = order[s]
        if (cached == null || cached.size != live.size) order[s] = live.shuffled()
        return order[s]!!
    }

    /** Next title: one from each service in turn. */
    private fun pickNext(): Showpiece? {
        val src = sources()
        repeat(src.size) {
            val s = src[turn % src.size]; turn++
            val l = listFor(s)
            if (l.isNotEmpty()) { val p = ((pos[s] ?: -1) + 1) % l.size; pos[s] = p; return l[p] }
        }
        return null
    }

    /** dir = 1 next, -1 previous. */
    fun step(dir: Int) {
        if (!running || loading) return
        main.removeCallbacks(advance)
        val item: Showpiece? = if (dir < 0) {
            if (histPos <= 0) { schedule(); return }
            history[--histPos]
        } else if (histPos < history.size - 1) history[++histPos] else pickNext()?.also { history += it; if (history.size > 40) history.removeAt(0); histPos = history.size - 1 }
        if (item == null) { main.postDelayed(advance, 4000); return }   // still loading titles
        load(item)
    }

    private fun schedule() { main.removeCallbacks(advance); main.postDelayed(advance, prefs.saverSeconds * 1000L) }

    private fun load(item: Showpiece) {
        loading = true
        val gen = generation
        Thread {
            var bm: Bitmap? = null; var portrait = false
            if (item.art != null) bm = Showcase.bitmap(context, item.art, screenW, screenH)
            if (bm == null && item.poster != null) { bm = Showcase.bitmap(context, item.poster, screenH * 2 / 3, screenH); portrait = bm != null }
            if (bm != null && bm.height > bm.width) portrait = true
            main.post {
                loading = false
                if (gen != generation || !running) return@post
                if (bm == null) {
                    // artwork missing: skip it (drop from history so Left doesn't return to it)
                    if (histPos >= 0 && histPos < history.size && history[histPos] == item) { history.removeAt(histPos); histPos-- }
                    if (++fails < 6) step(1) else { fails = 0; schedule() }
                    return@post
                }
                fails = 0
                present(item, bm, portrait)
            }
        }.start()
    }

    private fun present(item: Showpiece?, bm: Bitmap, portrait: Boolean) {
        val next = slides[1 - front]; val old = slides[front]
        if (portrait) {
            next.card.setImageBitmap(bm); next.card.visibility = VISIBLE
            next.back.setImageBitmap(blurred(bm))
        } else {
            next.card.setImageDrawable(null); next.card.visibility = GONE
            next.back.setImageBitmap(bm)
        }
        // slow Ken Burns drift
        val secs = (prefs.saverSeconds + 3) * 1000L
        val dirX = if (Math.random() < 0.5) -1 else 1
        next.back.animate().cancel()
        next.back.scaleX = 1.0f; next.back.scaleY = 1.0f; next.back.translationX = -dirX * screenW * 0.012f; next.back.translationY = 0f
        next.back.animate().scaleX(1.09f).scaleY(1.09f).translationX(dirX * screenW * 0.012f).translationY(-screenH * 0.008f)
            .setDuration(secs).setInterpolator(LinearInterpolator()).start()
        next.root.translationZ = 1f; old.root.translationZ = 0f
        next.root.animate().cancel()
        next.root.alpha = 0f
        next.root.animate().alpha(1f).setDuration(1400).withEndAction { old.root.alpha = 0f; old.back.animate().cancel() }.start()
        front = 1 - front

        current = item
        if (item == null) { info.animate().alpha(0f).setDuration(300).start(); return }
        info.animate().cancel()
        info.animate().alpha(0f).translationY(dp(10f).toFloat()).setDuration(350).withEndAction {
            fill(item)
            info.translationY = dp(14f).toFloat()
            info.animate().alpha(1f).translationY(0f).setDuration(700).setInterpolator(DecelerateInterpolator()).start()
        }.start()
        // tiny shift each time so nothing stays burned into the same pixels
        overlay.translationX = ((Math.random() * 2 - 1) * 10 * d).toFloat()
        overlay.translationY = ((Math.random() * 2 - 1) * 8 * d).toFloat()
        schedule()
    }

    private fun fill(item: Showpiece) {
        val pkg = if (item.service == "apple") Showcase.APPLE else Showcase.NETFLIX
        val app = if (item.service == "apple") "Apple TV" else "Netflix"
        try { badge.setImageDrawable(context.packageManager.getApplicationIcon(pkg)); badge.visibility = VISIBLE } catch (_: Exception) { badge.visibility = GONE }
        service.text = app.uppercase()
        title.text = item.title
        metaText.text = item.meta
        metaText.visibility = if (item.meta.isBlank()) GONE else VISIBLE
        blurb.text = item.blurb
        blurb.visibility = if (item.blurb.isBlank() || !prefs.saverInfo) GONE else VISIBLE
        pill.text = "OK   Watch on $app"
    }

    private fun blurred(src: Bitmap): Bitmap {
        val w = 96; val h = (w * screenH / screenW.toFloat()).toInt().coerceAtLeast(9)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s; val dh = src.height * s
        c.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        Glass.stackBlur(small, 8)
        c.drawColor(0x66000000)
        return small
    }

    /** Remote keys. Returns true if handled; false = wake up. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
            else -> false
        }
    }
}

private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
private fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

// ------------------------------------------------------------------ the screen saver

class PosterDream : DreamService() {
    private var view: PosterView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        view = PosterView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); view?.start() }
    override fun onDreamingStopped() { view?.stop(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { view?.stop(); super.onDetachedFromWindow() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (isVolume(e.keyCode)) return super.dispatchKeyEvent(e)
        if (isOk(e.keyCode)) {
            if (e.action == KeyEvent.ACTION_UP) {
                val item = view?.current
                if (item != null) Showcase.open(this, item)
                finish()
            }
            return true
        }
        if (view?.onKey(e) == true) return true
        finish(); return true
    }
}

/** The same screen saver as a normal screen, for "Preview" in settings. */
class PosterPreviewActivity : Activity() {
    private lateinit var view: PosterView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = PosterView(this)
        setContentView(view)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
    override fun onResume() { super.onResume(); view.start() }
    override fun onPause() { view.stop(); super.onPause() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (isVolume(e.keyCode)) return super.dispatchKeyEvent(e)
        if (isOk(e.keyCode)) {
            if (e.action == KeyEvent.ACTION_UP) { view.current?.let { Showcase.open(this, it) }; finish() }
            return true
        }
        if (view.onKey(e)) return true
        if (e.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
