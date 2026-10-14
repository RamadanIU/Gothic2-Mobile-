package gothic2.mobile.app.ui.gothic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Random
import kotlin.math.hypot
import kotlin.math.sqrt

/** Общее для отрисовщиков: непрозрачность через setAlpha не используется. */
abstract class GothicDrawable : Drawable() {
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Устарело в Android, но обязательно для Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    protected fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Ромб с центром ([cx], [cy]) — главный мотив украшений. */
    protected fun diamond(path: Path, cx: Float, cy: Float, halfW: Float, halfH: Float): Path = path.apply {
        reset()
        moveTo(cx, cy - halfH)
        lineTo(cx + halfW, cy)
        lineTo(cx, cy + halfH)
        lineTo(cx - halfW, cy)
        close()
    }
}

/**
 * Фон экранов вне игры: тьма с тёплым отсветом посередине, отблеск огня снизу, зерно старого
 * пергамента и затемнение к краям.
 */
class ScreenBackgroundDrawable(private val density: Float) : GothicDrawable() {
    private val warm = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fire = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(density * 0.75f, density * 0.75f) })
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val diag = hypot(w, h)
        val cx = bounds.left + w / 2
        warm.shader = RadialGradient(
            cx, bounds.top + h * 0.42f, diag * 0.55f,
            intArrayOf(0xFF31200F.toInt(), 0x8030200F.toInt(), 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        fire.shader = RadialGradient(
            cx, bounds.top + h * 1.12f, diag * 0.55f,
            intArrayOf(0x55A0461A, 0x226A2A0E, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        vignette.shader = RadialGradient(
            cx, bounds.top + h / 2, diag * 0.62f,
            intArrayOf(0x00000000, 0x00000000, 0xE6000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(GothicPalette.NIGHT)
        canvas.drawRect(b, warm)
        canvas.drawRect(b, fire)
        canvas.drawRect(b, grain)
        canvas.drawRect(b, vignette)
    }

    private companion object {
        /**
         * Бесшовное «зерно»: случайные значения, дважды размытые с переносом через край. Светлые
         * пятна — тёплый пергамент, тёмные — копоть. Один раз на процесс, 128×128.
         */
        val noise: Bitmap by lazy {
            val n = 128
            val rnd = Random(7)
            var v = FloatArray(n * n) { rnd.nextFloat() * 2f - 1f }
            repeat(2) {
                val out = FloatArray(n * n)
                for (y in 0 until n) for (x in 0 until n) {
                    var s = 0f
                    for (dy in -1..1) for (dx in -1..1) s += v[((y + dy + n) % n) * n + (x + dx + n) % n]
                    out[y * n + x] = s / 9f
                }
                v = out
            }
            val sd = sqrt(v.fold(0f) { a, x -> a + x * x } / v.size).coerceAtLeast(1e-6f)
            val pixels = IntArray(n * n) { i ->
                val t = (v[i] / sd / 2.5f).coerceIn(-1f, 1f)
                if (t > 0) ((t * 20).toInt() shl 24) or 0xE8D0A8 else ((-t * 44).toInt() shl 24)
            }
            Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
        }
    }
}

/**
 * Рамка панели: тёмная кожа, бронзовая кайма, тонкая золотая линия внутри, накладки на углах и
 * ромбы посередине верхней и нижней кромки. [compact] — без накладок, для мелких сообщений.
 */
class FrameDrawable(private val density: Float, private val compact: Boolean = false, private val fillAlpha: Int = 0xF2) : GothicDrawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = GothicPalette.BRONZE
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xC0000000.toInt()
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = withAlpha(GothicPalette.GOLD, 0x70)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.SQUARE
        color = GothicPalette.GOLD_DEEP
    }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GothicPalette.GOLD }
    private val gemEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = GothicPalette.INK
    }
    private val rect = RectF()
    private val innerRect = RectF()
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        val o = 1.5f * density
        rect.set(bounds.left + o, bounds.top + o, bounds.right - o, bounds.bottom - o)
        val i = (if (compact) 3.5f else 5f) * density
        innerRect.set(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i)
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            withAlpha(GothicPalette.LEATHER, fillAlpha), withAlpha(GothicPalette.LEATHER_DARK, fillAlpha), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRect(rect, fill)
        canvas.drawRect(rect.left - density, rect.top - density, rect.right + density, rect.bottom + density, shade)
        canvas.drawRect(rect, outer)
        canvas.drawRect(innerRect, inner)
        if (compact) return
        // Кованые накладки на углах: по отрезку вдоль каждой кромки.
        val a = 14f * density
        for (c in 0..3) {
            val x = if (c % 2 == 0) rect.left else rect.right
            val y = if (c < 2) rect.top else rect.bottom
            val sx = if (c % 2 == 0) 1f else -1f
            val sy = if (c < 2) 1f else -1f
            canvas.drawLine(x, y, x + sx * a, y, accent)
            canvas.drawLine(x, y, x, y + sy * a, accent)
            val gx = if (c % 2 == 0) innerRect.left else innerRect.right
            val gy = if (c < 2) innerRect.top else innerRect.bottom
            canvas.drawPath(diamond(path, gx, gy, 2.6f * density, 2.6f * density), gem)
        }
        for (y in floatArrayOf(rect.top, rect.bottom)) {
            diamond(path, rect.centerX(), y, 5f * density, 3.6f * density)
            canvas.drawPath(path, gem)
            canvas.drawPath(path, gemEdge)
        }
    }

    override fun getPadding(padding: Rect): Boolean {
        val h = ((if (compact) 16f else 22f) * density).toInt()
        val v = ((if (compact) 10f else 20f) * density).toInt()
        padding.set(h, v, h, v)
        return true
    }

}

/** Кнопка: тёмная кожа в бронзовой рамке с ромбами по бокам; нажатие — золото и отсвет. */
class ButtonDrawable(private val density: Float, private val kind: Gothic.ButtonKind) : GothicDrawable() {
    private var pressed = false
    private var enabled = true
    private var focused = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = 0x30FFF0C0
    }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gemEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = GothicPalette.INK
    }
    private val rect = RectF()
    private val layer = RectF()
    private val path = Path()

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        val e = android.R.attr.state_enabled in state
        val f = android.R.attr.state_focused in state
        if (p == pressed && e == enabled && f == focused) return false
        pressed = p
        enabled = e
        focused = f
        updateShaders()
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds.left + 4f * density, bounds.top + 1.5f * density, bounds.right - 4f * density, bounds.bottom - 1.5f * density)
        layer.set(bounds)
        updateShaders()
    }

    private fun updateShaders() {
        if (rect.isEmpty) return
        val (top, bottom) = when {
            pressed && kind == Gothic.ButtonKind.DANGER -> 0xFF6E2A1E.toInt() to 0xFF331310.toInt()
            pressed -> 0xFF6A4826.toInt() to 0xFF33200F.toInt()
            kind == Gothic.ButtonKind.PRIMARY -> 0xFF4A321D.toInt() to 0xFF24170D.toInt()
            kind == Gothic.ButtonKind.DANGER -> 0xFF3E1A14.toInt() to 0xFF1E0D0A.toInt()
            else -> 0xFF33231A.toInt() to 0xFF1B120C.toInt()
        }
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(
            rect.centerX(), rect.centerY(), rect.width().coerceAtLeast(1f) / 2,
            intArrayOf(0x55E9893B, 0x00E9893B), null, Shader.TileMode.CLAMP,
        )
        val edge = when {
            pressed || focused -> GothicPalette.GOLD_LIGHT
            kind == Gothic.ButtonKind.PRIMARY -> GothicPalette.GOLD
            kind == Gothic.ButtonKind.DANGER -> 0xFFB0503A.toInt()
            else -> GothicPalette.BRONZE
        }
        border.color = edge
        gem.color = edge
        hairline.color = withAlpha(GothicPalette.GOLD, if (kind == Gothic.ButtonKind.PRIMARY || pressed) 0x70 else 0x40)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val save = if (!enabled) canvas.saveLayerAlpha(layer, 0x70) else canvas.save()
        val r = 3f * density
        canvas.drawRoundRect(rect, r, r, fill)
        if (pressed) canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawLine(rect.left + r, rect.top + 1.6f * density, rect.right - r, rect.top + 1.6f * density, bevel)
        val i = 3.2f * density
        canvas.drawRect(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i, hairline)
        canvas.drawRoundRect(rect, r, r, border)
        for (x in floatArrayOf(rect.left, rect.right)) {
            diamond(path, x, rect.centerY(), 3.2f * density, 4.6f * density)
            canvas.drawPath(path, gem)
            canvas.drawPath(path, gemEdge)
        }
        canvas.restoreToCount(save)
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set((22 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), (8 * density).toInt())
        return true
    }
}

/** Узор-разделитель: золотая линия, гаснущая к краям, ромб и две точки посередине. */
class OrnamentDrawable(private val density: Float) : GothicDrawable() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * density }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GothicPalette.GOLD }
    private val gemEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = GothicPalette.INK
    }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        line.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            intArrayOf(withAlpha(GothicPalette.GOLD, 0), withAlpha(GothicPalette.GOLD, 0xB0), withAlpha(GothicPalette.GOLD, 0)),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        canvas.drawLine(b.left.toFloat(), cy, cx - 17f * density, cy, line)
        canvas.drawLine(cx + 17f * density, cy, b.right.toFloat(), cy, line)
        canvas.drawCircle(cx - 11.5f * density, cy, 1.5f * density, gem)
        canvas.drawCircle(cx + 11.5f * density, cy, 1.5f * density, gem)
        diamond(path, cx, cy, 5.5f * density, 4.5f * density)
        canvas.drawPath(path, gem)
        canvas.drawPath(path, gemEdge)
    }
}

/** Маленький ромб — метка выбранного пункта в списке. */
class DiamondDrawable(private val density: Float, private val filled: Boolean) : GothicDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GothicPalette.GOLD
        style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val path = Path()

    override fun getIntrinsicWidth(): Int = (14 * density).toInt()
    override fun getIntrinsicHeight(): Int = (14 * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawPath(diamond(path, b.exactCenterX(), b.exactCenterY(), 4.2f * density, 5.2f * density), paint)
    }
}

/** Отсвет огня за эмблемой. */
class GlowDrawable : GothicDrawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        paint.shader = RadialGradient(
            bounds.exactCenterX(), bounds.exactCenterY(), (minOf(bounds.width(), bounds.height()) / 2f).coerceAtLeast(1f),
            intArrayOf(0x70E9893B, 0x30B4561E, 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
}
