package com.home.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * "Liquid glass" for Android 10: the wallpaper is blurred once (small and fast), then every
 * glass surface draws the matching part of that blurred copy behind itself, plus a light tint
 * and a bright edge. Android 10 has no built-in blur, so this is done by hand.
 */
object Glass {
    @Volatile var blurred: Bitmap? = null
    /** half-size, unblurred copy — what you see through clear glass */
    @Volatile var sharp: Bitmap? = null
    var screenW = 1; var screenH = 1
    var dim = 0f
    /** 0..1 — how frosted/opaque glass surfaces are */
    var opacity = 0.3f
    /** 0..1 — how strongly glass edges bend what is behind them */
    var refraction = 0.5f
    private val views = ArrayList<WeakReference<View>>()
    private val main = Handler(Looper.getMainLooper())

    fun register(v: View) { views.add(WeakReference(v)) }

    fun invalidateAll() {
        views.removeAll { it.get() == null }
        views.forEach { it.get()?.invalidate() }
    }

    /** Call with the full-size wallpaper bitmap (any size). Runs the blur off the UI thread. */
    fun update(src: Bitmap, w: Int, h: Int) {
        screenW = w; screenH = h
        Thread {
            val out = try { makeBlur(src, w, h) } catch (e: Throwable) { null }
            val clear = try { crop(src, w / 2, h / 2) } catch (e: Throwable) { null }
            main.post { blurred = out; sharp = clear; invalidateAll() }
        }.start()
    }

    private fun crop(src: Bitmap, sw: Int, sh: Int): Bitmap {
        val out = Bitmap.createBitmap(sw.coerceAtLeast(16), sh.coerceAtLeast(9), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = maxOf(out.width.toFloat() / src.width, out.height.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        c.drawBitmap(src, null, RectF((out.width - dw) / 2, (out.height - dh) / 2, (out.width + dw) / 2, (out.height + dh) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun makeBlur(src: Bitmap, w: Int, h: Int): Bitmap {
        // centre-crop to the screen shape at 1/8 size, then stack-blur
        val sw = (w / 8).coerceAtLeast(16); val sh = (h / 8).coerceAtLeast(9)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val scale = maxOf(sw.toFloat() / src.width, sh.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        val dst = RectF((sw - dw) / 2, (sh - dh) / 2, (sw + dw) / 2, (sh + dh) / 2)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        stackBlur(small, 9)
        return small
    }

    /** Classic stack blur (Mario Klingemann), in place. */
    fun stackBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width; val h = bmp.height
        val pix = IntArray(w * h); bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val wm = w - 1; val hm = h - 1; val wh = w * h; val div = radius + radius + 1
        val r = IntArray(wh); val g = IntArray(wh); val b = IntArray(wh)
        val vmin = IntArray(maxOf(w, h))
        val divsum = ((div + 1) shr 1).let { it * it }
        val dv = IntArray(256 * divsum) { it / divsum }
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1
        var yi = 0; var yw = 0
        for (y in 0 until h) {
            var rinsum = 0; var ginsum = 0; var binsum = 0; var routsum = 0; var goutsum = 0; var boutsum = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            for (i in -radius..radius) {
                val p = pix[yi + minOf(wm, maxOf(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xff; sir[1] = (p shr 8) and 0xff; sir[2] = p and 0xff
                val rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] } else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
            }
            var stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                var sir = stack[(stackpointer - radius + div) % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
                val p = pix[yw + vmin[x]]
                sir[0] = (p shr 16) and 0xff; sir[1] = (p shr 8) and 0xff; sir[2] = p and 0xff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
            yw += w
        }
        for (x in 0 until w) {
            var rinsum = 0; var ginsum = 0; var binsum = 0; var routsum = 0; var goutsum = 0; var boutsum = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                val rbs = r1 - Math.abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] } else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                var sir = stack[(stackpointer - radius + div) % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = minOf(y + r1, hm) * w
                val p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
    }
}

/** A frosted-glass container. Draws the blurred wallpaper behind its children, a tint and a light edge. */
class GlassFrame(context: Context, var radius: Float, var tint: Int = 0x26FFFFFF) : FrameLayout(context) {
    private val loc = IntArray(2)
    private val path = Path()
    private val rect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val src = Rect()
    private val dst = RectF()
    var enabledGlass = true

    init {
        setWillNotDraw(false)
        Glass.register(this)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        rect.set(0f, 0f, w, h)
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(path)
        val b = Glass.blurred
        val o = Glass.opacity.coerceIn(0f, 1f)
        if (enabledGlass && b != null) {
            getLocationOnScreen(loc)
            val sxv = if (scaleX != 0f) scaleX else 1f
            val syv = if (scaleY != 0f) scaleY else 1f
            dst.set(0f, 0f, w, h)
            // 1) what's behind, sharp (clear glass)
            Glass.sharp?.let { sb -> drawRefracted(canvas, sb, w, h, sxv, syv, 255) }
            // 2) blur fades in as opacity rises (fully blurred from ~60%)
            drawRefracted(canvas, b, w, h, sxv, syv,
                if (Glass.sharp == null) 255 else (minOf(1f, o * 1.7f) * 255).toInt())
            if (Glass.dim > 0f) { fill.shader = null; fill.color = Color.argb((Glass.dim * 255).toInt(), 0, 0, 0); canvas.drawRect(rect, fill) }
            // 3) frost: a light slate + milky haze that builds up to solid
            if (o > 0f) {
                fill.shader = null; fill.color = Color.argb((o * 0.82f * 255).toInt(), 62, 68, 88); canvas.drawRect(rect, fill)
                fill.color = Color.argb((o * 0.12f * 255).toInt(), 255, 255, 255); canvas.drawRect(rect, fill)
            }
        } else {
            fill.shader = null; fill.color = 0xCC161B28.toInt(); canvas.drawRect(rect, fill)
        }
        // own tint scales with opacity (nearly gone when clear)
        val ta = (Color.alpha(tint) * (0.15f + 1.4f * o)).toInt().coerceIn(0, 255)
        fill.shader = null; fill.color = (tint and 0x00FFFFFF) or (ta shl 24); canvas.drawRect(rect, fill)
        val la = ((0.25f + o) * 0x1F).toInt().coerceIn(0, 255)
        fill.shader = LinearGradient(0f, 0f, 0f, h, (la shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * 0.5f, fill)
        canvas.restore()
        // bright rim, stronger at the top (light catching the edge)
        val sw = resources.displayMetrics.density * 1.2f
        edge.strokeWidth = sw
        edge.shader = LinearGradient(0f, 0f, 0f, h, 0x8CFFFFFF.toInt(), 0x1AFFFFFF, Shader.TileMode.CLAMP)
        rect.inset(sw / 2, sw / 2)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    private var verts = FloatArray(0)

    /**
     * Draw the screen-shaped bitmap behind this view through a warped mesh: near the rim the
     * the rim gathers in a squeezed sliver of what lies just beyond the edge, like light bending round curved glass.
     */
    private fun drawRefracted(canvas: Canvas, bm: Bitmap, w: Float, h: Float, sxv: Float, syv: Float, alpha: Int) {
        if (alpha <= 0) return
        bmpPaint.alpha = alpha
        val k = Glass.refraction.coerceIn(0f, 1f)
        if (k < 0.01f) {
            mapSrc(bm, w * sxv, h * syv); canvas.drawBitmap(bm, src, dst, bmpPaint); bmpPaint.alpha = 255; return
        }
        val cols = 96; val rows = 54
        val n = (cols + 1) * (rows + 1) * 2
        if (verts.size != n) verts = FloatArray(n)
        val cellW = Glass.screenW.toFloat() / cols; val cellH = Glass.screenH.toFloat() / rows
        val d = resources.displayMetrics.density
        // thin rim where light bends; the middle stays 1:1
        val band = minOf(26f * d, minOf(w, h) * 0.3f)
        // how much of the world *outside* the edge gets gathered into the rim
        val reach = band * 0.95f * k
        // rim mapping: source distance s from the edge (negative = outside) -> drawn distance g(s)
        // g(-reach) = 0 (rim), g(band) = band with slope 1 (seamless with the flat middle)
        val a = 1f - reach / band
        fun g(s: Float): Float {
            if (s >= band) return s
            if (s <= -reach) return s + reach
            val u = (s + reach) / (band + reach)
            return band * (a * u + (1f - a) * u * u)
        }
        var i = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                val lx = (c * cellW - loc[0]) / sxv
                val ly = (r * cellH - loc[1]) / syv
                var x = lx; var y = ly
                if (lx < w / 2) { if (lx < band) x = g(lx) } else { val dr = w - lx; if (dr < band) x = w - g(dr) }
                if (ly < h / 2) { if (ly < band) y = g(ly) } else { val db = h - ly; if (db < band) y = h - g(db) }
                verts[i++] = x; verts[i++] = y
            }
        }
        canvas.drawBitmapMesh(bm, cols, rows, verts, 0, null, 0, bmpPaint)
        bmpPaint.alpha = 255
    }

    /** Map this view's on-screen area onto a screen-shaped bitmap. */
    private fun mapSrc(bm: Bitmap, vw: Float, vh: Float) {
        val sx = bm.width.toFloat() / Glass.screenW; val sy = bm.height.toFloat() / Glass.screenH
        val l = (loc[0] * sx).toInt(); val t = (loc[1] * sy).toInt()
        src.set(l, t, ((loc[0] + vw) * sx).toInt().coerceAtLeast(l + 1), ((loc[1] + vh) * sy).toInt().coerceAtLeast(t + 1))
    }
}
