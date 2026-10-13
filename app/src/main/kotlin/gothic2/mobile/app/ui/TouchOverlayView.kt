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
import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.ControlSpec
import gothic2.mobile.core.input.LayoutEditor
import gothic2.mobile.core.input.TouchRouter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Сенсорный оверлей поверх вывода игры. Сам ничего не решает: касания уходят в
 * [TouchRouter] → арбитр, а в режиме настройки — в [LayoutEditor].
 * Перерисовывается только при изменениях, а не с частотой игры.
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(context: Context, private val router: TouchRouter) : View(context) {
    var opacity = 0.6f
        set(v) { field = v; invalidate() }

    /** Подключён геймпад: кнопки не рисуются, пока игрок снова не коснётся экрана. */
    var minimal = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Режим настройки раскладки; null — обычная игра. */
    var editor: LayoutEditor? = null
        set(v) { field = v; invalidate() }

    /** Режим, кнопки которого показываются в редакторе. */
    var editorMode: ControlMode = ControlMode.WORLD
        set(v) { field = v; invalidate() }

    /** Вызывается после каждого перемещения кнопки в редакторе. */
    var onEditorChanged: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = GOLD
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD }
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
        editor?.let { return onEditorTouch(it, e) }
        minimal = false
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

    private fun onEditorTouch(ed: LayoutEditor, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> ed.begin(e.x, e.y, router.viewport, editorMode)
            MotionEvent.ACTION_MOVE -> if (ed.move(e.x, e.y, router.viewport)) onEditorChanged()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ed.end()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val ed = editor
        if (ed != null) {
            val l = ed.layout()
            canvas.drawColor(Color.argb(120, 0, 0, 0))
            for (c in l.forMode(editorMode, adapterActive = false)) drawButton(canvas, c, l.buttonScale, 255, c.id == ed.draggingId)
            return
        }
        if (minimal) return
        val alpha = (opacity * 255).toInt()
        val owners = router.owners().values
        val pressed = owners.mapNotNull {
            (it as? TouchRouter.Owner.Button)?.spec?.id ?: (it as? TouchRouter.Owner.Radial)?.spec?.id
        }.toSet()
        for (c in router.visibleControls()) drawButton(canvas, c, router.layout.buttonScale, alpha, c.id in pressed)
        for (o in owners) {
            when (o) {
                is TouchRouter.Owner.Joystick -> {
                    stroke.alpha = alpha
                    canvas.drawCircle(o.originX, o.originY, router.layout.joystickRadiusDp * density, stroke)
                }
                is TouchRouter.Owner.Radial -> drawRadial(canvas, o)
                else -> Unit
            }
        }
    }

    private fun drawButton(canvas: Canvas, c: ControlSpec, scale: Float, alpha: Int, active: Boolean) {
        val vp = router.viewport
        val x = vp.toPxX(c.cx)
        val y = vp.toPxY(c.cy)
        val r = c.radiusDp * scale * density
        fill.alpha = if (active) (alpha * 0.9f).toInt() else alpha / 2
        stroke.alpha = alpha
        text.alpha = alpha.coerceAtLeast(120)
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, stroke)
        canvas.drawText(c.label, x, y + text.textSize / 3, text)
    }

    /** Колесо слотов рисуется в центре экрана; выбор определяется направлением пальца. */
    private fun drawRadial(canvas: Canvas, o: TouchRouter.Owner.Radial) {
        val menu = router.radialMenu()
        val selected = menu.select(o.dx, o.dy)
        val cx = width / 2f
        val cy = height / 2f
        val ring = RADIAL_RADIUS_DP * density
        val itemR = 26f * density
        fill.alpha = 200
        canvas.drawCircle(cx, cy, ring + itemR * 1.4f, fill)
        menu.items.forEachIndexed { i, item ->
            val a = menu.angleOf(i)
            val x = cx + (ring * sin(a)).toFloat()
            val y = cy - (ring * cos(a)).toFloat()
            if (i == selected) canvas.drawCircle(x, y, itemR, highlight)
            stroke.alpha = 255
            canvas.drawCircle(x, y, itemR, stroke)
            text.alpha = 255
            canvas.drawText(item.label, x, y + text.textSize / 3, text)
        }
    }

    private companion object {
        val GOLD = Color.rgb(0xC9, 0xA4, 0x5C)
        const val RADIAL_RADIUS_DP = 110f
    }
}
