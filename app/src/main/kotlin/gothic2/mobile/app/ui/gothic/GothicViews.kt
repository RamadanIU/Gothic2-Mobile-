package gothic2.mobile.app.ui.gothic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Искры костра, поднимающиеся снизу экрана. Анимируется, пока вид на экране;
 * [frameMs] — шаг кадров (на загрузке игры реже, чтобы не отнимать процессор у неё).
 */
@SuppressLint("ViewConstructor")
class EmberView(context: Context, private val count: Int = 26, private val frameMs: Long = 16) : View(context) {
    private val density = resources.displayMetrics.density
    private val rnd = Random()
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val speed = FloatArray(count)
    private val sway = FloatArray(count)
    private val swayHz = FloatArray(count)
    private val phase = FloatArray(count)
    private val size = FloatArray(count)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private var last = 0L
    private var time = 0f
    private var running = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun spawn(i: Int, w: Float, h: Float, anywhere: Boolean) {
        x[i] = rnd.nextFloat() * w
        y[i] = if (anywhere) rnd.nextFloat() * h else h + rnd.nextFloat() * 24f * density
        speed[i] = (14f + rnd.nextFloat() * 40f) * density
        sway[i] = (4f + rnd.nextFloat() * 16f) * density
        swayHz[i] = 0.2f + rnd.nextFloat() * 0.6f
        phase[i] = rnd.nextFloat() * 2f * PI.toFloat()
        size[i] = (1.1f + rnd.nextFloat() * 2.2f) * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        for (i in 0 until count) spawn(i, w.toFloat(), h.toFloat(), anywhere = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        last = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last).coerceIn(0, 100)) / 1000f
        last = now
        time += dt
        for (i in 0 until count) {
            y[i] -= speed[i] * dt
            if (y[i] < h * 0.15f) spawn(i, w, h, anywhere = false)
            val px = x[i] + sway[i] * sin(time * swayHz[i] * 2f * PI.toFloat() + phase[i])
            // Ярче у земли, гаснут к верху; лёгкое мерцание.
            val height = ((y[i] - h * 0.15f) / (h * 0.85f)).coerceIn(0f, 1f)
            val flicker = 0.75f + 0.25f * sin(time * 9f + phase[i] * 3f)
            paint.alpha = (255 * height * flicker).toInt().coerceIn(0, 255)
            val r = size[i] * 4f
            dst.set(px - r, y[i] - r, px + r, y[i] + r)
            canvas.drawBitmap(sprite, null, dst, paint)
        }
        if (running && isVisible) postInvalidateDelayed(frameMs)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && running) {
            last = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    private companion object {
        /** Искра: яркое ядро и мягкий оранжевый ореол. */
        val sprite: Bitmap by lazy {
            val n = 32
            createBitmap(n, n).also { bmp ->
                Canvas(bmp).drawCircle(
                    n / 2f, n / 2f, n / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            n / 2f, n / 2f, n / 2f,
                            intArrayOf(0xFFFFE7B0.toInt(), 0xFFFFA24A.toInt(), 0x66D4501A, 0x00000000),
                            floatArrayOf(0f, 0.18f, 0.42f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Полоса прогресса в кованой оправе: золото на тёмной коже. Без процента ([indeterminate]) по
 * полосе бежит золотой отблеск.
 */
class GothicProgressView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    var progress: Float = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (field != c) { field = c; invalidate() }
        }
    var indeterminate: Boolean = true
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF120B07.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = GothicPalette.BRONZE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x50FFF6DC }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GothicPalette.GOLD }
    private val gemEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = GothicPalette.INK
    }
    private val outer = RectF()
    private val bar = RectF()
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), (16 * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gemW = 7f * density
        outer.set(gemW, 3f * density, w - gemW, h - 3f * density)
        fill.shader = LinearGradient(
            outer.left, 0f, outer.right, 0f,
            intArrayOf(GothicPalette.GOLD_DEEP, GothicPalette.GOLD, GothicPalette.GOLD_LIGHT),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (outer.isEmpty) return
        val r = 2f * density
        canvas.drawRoundRect(outer, r, r, track)
        val i = 2.5f * density
        val inner = outer.width() - 2 * i
        if (indeterminate) {
            // Отблеск: отрезок в четверть полосы туда и обратно за 1,8 с.
            val t = (SystemClock.uptimeMillis() % 1800) / 1800f
            val pos = (1 - cos(t * 2 * PI.toFloat())) / 2
            val len = inner * 0.28f
            val start = outer.left + i + pos * (inner - len)
            bar.set(start, outer.top + i, start + len, outer.bottom - i)
            canvas.drawRect(bar, fill)
            postInvalidateOnAnimation()
        } else if (progress > 0f) {
            bar.set(outer.left + i, outer.top + i, outer.left + i + inner * progress, outer.bottom - i)
            canvas.drawRect(bar, fill)
        }
        if (!bar.isEmpty && (indeterminate || progress > 0f)) {
            canvas.drawRect(bar.left, bar.top, bar.right, bar.top + 1.2f * density, shine)
        }
        canvas.drawRoundRect(outer, r, r, border)
        for (cx in floatArrayOf(outer.left, outer.right)) {
            path.reset()
            val cy = outer.centerY()
            path.moveTo(cx, cy - 6f * density)
            path.lineTo(cx + 4.5f * density, cy)
            path.lineTo(cx, cy + 6f * density)
            path.lineTo(cx - 4.5f * density, cy)
            path.close()
            canvas.drawPath(path, gem)
            canvas.drawPath(path, gemEdge)
        }
    }
}

/** Текст, залитый золотом сверху вниз — для названия игры и крупных заголовков. */
@SuppressLint("ViewConstructor")
class GoldTextView(context: Context, sizeSp: Float) : AppCompatTextView(context) {
    init {
        typeface = GothicFonts.capsBold(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(GothicPalette.GOLD)
        letterSpacing = 0.08f
        setShadowLayer(10f, 0f, 3f, 0xF0000000.toInt())
        includeFontPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fm = paint.fontMetrics
        val base = baseline.toFloat()
        paint.shader = LinearGradient(
            0f, base + fm.ascent * 0.8f, 0f, base + fm.descent * 0.4f,
            intArrayOf(0xFFFFF6D2.toInt(), 0xFFEECF7A.toInt(), 0xFFB98731.toInt(), 0xFF8C5F1F.toInt(), 0xFFE0B65E.toInt()),
            floatArrayOf(0f, 0.3f, 0.6f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
