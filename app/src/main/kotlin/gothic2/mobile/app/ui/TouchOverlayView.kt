package gothic2.mobile.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import gothic2.mobile.app.ui.gothic.GothicFonts
import gothic2.mobile.app.ui.gothic.GothicPalette
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

    // Кнопки — тёмные диски с золотым кольцом и подписью капителью, как в меню игры.
    private val disc = RadialGradient(
        0f, -0.25f, 1.15f,
        intArrayOf(0xFF3A2818.toInt(), 0xFF1C130C.toInt(), 0xFF0E0906.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
    )
    private val discPressed = RadialGradient(
        0f, -0.2f, 1.1f,
        intArrayOf(0xFFC08C34.toInt(), 0xFF6A4520.toInt(), 0xFF2A1A0E.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
    )
    private val shaderMatrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = GothicPalette.GOLD
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GothicPalette.PARCHMENT
        textAlign = Paint.Align.CENTER
        typeface = GothicFonts.caps(context)
        setShadowLayer(3f * density, 0f, 1f * density, 0xE0000000.toInt())
    }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GothicPalette.GOLD }
    private val gemEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = GothicPalette.INK
    }
    private val path = Path()
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
            canvas.drawColor(0x99000000.toInt())
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
                is TouchRouter.Owner.Joystick -> drawJoystick(canvas, o.originX, o.originY, router.layout.joystickRadiusDp * density, alpha)
                is TouchRouter.Owner.Radial -> drawRadial(canvas, o)
                else -> Unit
            }
        }
    }

    private fun drawButton(canvas: Canvas, c: ControlSpec, scale: Float, alpha: Int, active: Boolean) {
        val vp = router.viewport
        drawDisc(canvas, vp.toPxX(c.cx), vp.toPxY(c.cy), c.radiusDp * scale * density, alpha, active, c.label)
    }

    /** Диск кнопки: тёмная кожа (нажатие — золото), кольцо, ромб сверху, подпись по размеру. */
    private fun drawDisc(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Int, active: Boolean, label: String) {
        shaderMatrix.setScale(r, r)
        shaderMatrix.postTranslate(x, y)
        val shader = if (active) discPressed else disc
        shader.setLocalMatrix(shaderMatrix)
        fill.shader = shader
        fill.alpha = if (active) alpha.coerceAtLeast(210) else (alpha * 0.85f).toInt()
        canvas.drawCircle(x, y, r, fill)
        ring.color = if (active) GothicPalette.GOLD_LIGHT else GothicPalette.GOLD_DEEP
        ring.alpha = alpha.coerceAtLeast(if (active) 230 else 0)
        canvas.drawCircle(x, y, r, ring)
        innerRing.alpha = (alpha * 0.45f).toInt()
        canvas.drawCircle(x, y, r - 3.5f * density, innerRing)
        gem.alpha = ring.alpha
        gemEdge.alpha = ring.alpha
        diamond(x, y - r, 2.6f * density, 3.6f * density)
        canvas.drawPath(path, gem)
        canvas.drawPath(path, gemEdge)
        text.alpha = alpha.coerceAtLeast(160)
        if (drawArrow(canvas, x, y, r, label)) return
        // Подпись капителью; длинная уменьшается, чтобы поместиться в диск.
        text.textSize = 13f * density * (r / (30f * density)).coerceIn(0.85f, 1.25f)
        val maxWidth = r * 1.62f
        val w = text.measureText(label)
        if (w > maxWidth) text.textSize = (text.textSize * maxWidth / w).coerceAtLeast(8f * density)
        canvas.drawText(label, x, y + text.textSize / 3, text)
    }

    /** Стрелки навигации — золотые треугольники вместо символов шрифта. */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, r: Float, label: String): Boolean {
        val (dx, dy) = when (label) {
            "▲" -> 0f to -1f
            "▼" -> 0f to 1f
            "◀" -> -1f to 0f
            "▶" -> 1f to 0f
            else -> return false
        }
        val s = r * 0.38f
        path.reset()
        path.moveTo(x + dx * s, y + dy * s)
        path.lineTo(x - dx * s * 0.6f - dy * s * 0.85f, y - dy * s * 0.6f + dx * s * 0.85f)
        path.lineTo(x - dx * s * 0.6f + dy * s * 0.85f, y - dy * s * 0.6f - dx * s * 0.85f)
        path.close()
        gem.alpha = text.alpha
        canvas.drawPath(path, gem)
        return true
    }

    /** Джойстик ходьбы: кольцо с ромбами по сторонам света и точка в центре касания. */
    private fun drawJoystick(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        shaderMatrix.setScale(r, r)
        shaderMatrix.postTranslate(x, y)
        disc.setLocalMatrix(shaderMatrix)
        fill.shader = disc
        fill.alpha = (alpha * 0.45f).toInt()
        canvas.drawCircle(x, y, r, fill)
        ring.color = GothicPalette.GOLD
        ring.alpha = alpha
        canvas.drawCircle(x, y, r, ring)
        innerRing.alpha = (alpha * 0.4f).toInt()
        canvas.drawCircle(x, y, r * 0.35f, innerRing)
        gem.alpha = alpha
        gemEdge.alpha = alpha
        for (i in 0..3) {
            val gx = COMPASS_X[i]
            val gy = COMPASS_Y[i]
            diamond(x + gx * r, y + gy * r, 3.2f * density, 3.2f * density)
            canvas.drawPath(path, gem)
            canvas.drawPath(path, gemEdge)
        }
    }

    private fun diamond(cx: Float, cy: Float, halfW: Float, halfH: Float) {
        path.reset()
        path.moveTo(cx, cy - halfH)
        path.lineTo(cx + halfW, cy)
        path.lineTo(cx, cy + halfH)
        path.lineTo(cx - halfW, cy)
        path.close()
    }

    /** Колесо слотов рисуется в центре экрана; выбор определяется направлением пальца. */
    private fun drawRadial(canvas: Canvas, o: TouchRouter.Owner.Radial) {
        val menu = router.radialMenu()
        val selected = menu.select(o.dx, o.dy)
        val cx = width / 2f
        val cy = height / 2f
        val orbit = RADIAL_RADIUS_DP * density
        val itemR = 26f * density
        val outer = orbit + itemR * 1.4f
        // Подложка колеса — большой диск в том же стиле.
        shaderMatrix.setScale(outer, outer)
        shaderMatrix.postTranslate(cx, cy)
        disc.setLocalMatrix(shaderMatrix)
        fill.shader = disc
        fill.alpha = 225
        canvas.drawCircle(cx, cy, outer, fill)
        ring.color = GothicPalette.GOLD_DEEP
        ring.alpha = 255
        canvas.drawCircle(cx, cy, outer, ring)
        innerRing.alpha = 110
        canvas.drawCircle(cx, cy, outer - 5f * density, innerRing)
        menu.items.forEachIndexed { i, item ->
            val a = menu.angleOf(i)
            drawDisc(canvas, cx + (orbit * sin(a)).toFloat(), cy - (orbit * cos(a)).toFloat(), itemR, 255, i == selected, item.label)
        }
        if (selected != null) {
            text.alpha = 255
            text.textSize = 20f * density
            text.color = GothicPalette.GOLD_LIGHT
            canvas.drawText(menu.items[selected].label, cx, cy + text.textSize / 3, text)
            text.color = GothicPalette.PARCHMENT
        }
    }

    private companion object {
        const val RADIAL_RADIUS_DP = 110f

        /** Стороны света для ромбов на кольце джойстика: вверх, вправо, вниз, влево. */
        val COMPASS_X = floatArrayOf(0f, 1f, 0f, -1f)
        val COMPASS_Y = floatArrayOf(-1f, 0f, 1f, 0f)
    }
}
