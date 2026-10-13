package gothic2.mobile.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import gothic2.mobile.core.input.TouchRouter

/**
 * Сенсорный оверлей поверх вывода игры. Сам ничего не решает: все касания уходят в
 * [TouchRouter] → арбитр. Перерисовывается только при изменениях, а не с частотой игры.
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(context: Context, private val router: TouchRouter) : View(context) {
    var opacity = 0.6f
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(0xC9, 0xA4, 0x5C)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }
    private var safeInsets = IntArray(4)

    init {
        isFocusable = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemGestures())
            safeInsets = intArrayOf(i.left, i.top, i.right, i.bottom)
            updateViewport()
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateViewport()

    private fun updateViewport() {
        if (width == 0 || height == 0) return
        val (l, t, r, b) = safeInsets.toList()
        router.viewport = TouchRouter.Viewport(
            safeLeft = l.toFloat(),
            safeTop = t.toFloat(),
            safeWidth = (width - l - r).toFloat().coerceAtLeast(1f),
            safeHeight = (height - t - b).toFloat().coerceAtLeast(1f),
            density = density,
        )
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                router.onPointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                router.onPointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> router.onPointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> router.onCancel()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val vp = router.viewport
        val alpha = (opacity * 255).toInt()
        val pressed = router.owners().values.filterIsInstance<TouchRouter.Owner.Button>().map { it.spec.id }.toSet()
        for (c in router.visibleControls()) {
            val x = vp.toPxX(c.cx)
            val y = vp.toPxY(c.cy)
            val r = c.radiusDp * router.layout.buttonScale * density
            fill.alpha = if (c.id in pressed) (alpha * 0.9f).toInt() else alpha / 2
            stroke.alpha = alpha
            text.alpha = alpha.coerceAtLeast(120)
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, stroke)
            canvas.drawText(c.label, x, y + text.textSize / 3, text)
        }
        for (o in router.owners().values) if (o is TouchRouter.Owner.Joystick) {
            val r = router.layout.joystickRadiusDp * density
            stroke.alpha = alpha
            canvas.drawCircle(o.originX, o.originY, r, stroke)
        }
    }
}
