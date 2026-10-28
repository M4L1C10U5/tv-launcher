package com.home.tvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/** Inter (a free font very close to Apple's SF Pro). Falls back to system fonts if not bundled. */
object Fonts {
    private val cache = HashMap<String, Typeface>()
    private fun load(c: Context, file: String, fallback: Typeface): Typeface =
        cache.getOrPut(file) { try { Typeface.createFromAsset(c.assets, "fonts/$file") } catch (e: Exception) { fallback } }

    fun regular(c: Context) = load(c, "Inter-Regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL))
    fun medium(c: Context) = load(c, "Inter-Medium.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun semibold(c: Context) = load(c, "Inter-SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun bold(c: Context) = load(c, "Inter-Bold.ttf", Typeface.create("sans-serif", Typeface.BOLD))
    fun light(c: Context) = load(c, "Inter-Light.ttf", Typeface.create("sans-serif-light", Typeface.NORMAL))
}

/** iOS-style app/settings badge: a "squircle" (continuous-curve corners) with a soft top-light gradient. */
class SquircleDrawable(private val color: Int) : Drawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        path.reset()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val a = b.width() / 2f; val bb = b.height() / 2f
        val n = 5.0 // superellipse exponent ~ Apple's continuous corner
        val steps = 96
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = Math.cos(t); val s = Math.sin(t)
            val x = cx + a * Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n)
            val y = cy + bb * Math.signum(s) * Math.pow(Math.abs(s), 2.0 / n)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        val top = blend(color, Color.WHITE, 0.22f)
        val bottom = blend(color, Color.BLACK, 0.06f)
        paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun blend(c1: Int, c2: Int, f: Float): Int = Color.rgb(
            (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt(),
            (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt(),
            (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt())
    }
}

/**
 * tvOS-style focus light: a soft sheen on the top half plus a glare band that sweeps
 * across the tile once when it's selected. No outline ring.
 */
class GlareDrawable(private val radius: Float) : Drawable() {
    private val clip = Path()
    private val rect = RectF()
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = -1f
    private var anim: ValueAnimator? = null

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        rect.set(b)
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        sheen.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.55f,
            0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    fun sweep() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; startDelay = 120; interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun stop() { anim?.cancel(); progress = -1f; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, sheen)
        if (progress in 0f..1f) {
            val w = rect.width(); val h = rect.height()
            val x = rect.left - w * 0.4f + (w * 1.8f) * progress
            val bw = w * 0.22f
            band.shader = LinearGradient(x - bw, 0f, x + bw, 0f,
                intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.rotate(18f, x, rect.centerY())
            canvas.drawRect(x - bw, rect.top - h, x + bw, rect.bottom + h, band)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
