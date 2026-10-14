package gothic2.mobile.app.ui.gothic

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import gothic2.mobile.app.R

/**
 * Палитра «Готики»: тьма, тёмная кожа, бронза, золото, пергамент. Цвета, нужные темам и
 * разметке, повторены в res/values/colors.xml.
 */
object GothicPalette {
    const val INK = 0xFF0B0705.toInt()
    const val NIGHT = 0xFF150E09.toInt()
    const val LEATHER = 0xFF2A1C12.toInt()
    const val LEATHER_DARK = 0xFF160E09.toInt()
    const val BRONZE = 0xFF7D5C33.toInt()
    const val BRONZE_DARK = 0xFF3F2D19.toInt()
    const val GOLD = 0xFFD9B566.toInt()
    const val GOLD_LIGHT = 0xFFF4DE9E.toInt()
    const val GOLD_DEEP = 0xFF9C7533.toInt()
    const val PARCHMENT = 0xFFE8D9B8.toInt()
    const val ASH = 0xFFA6957C.toInt()
    const val BLOOD = 0xFFA23426.toInt()
    const val EMBER = 0xFFE9893B.toInt()
}

/** Шрифты: Alegreya для текста, капитель Alegreya SC для заголовков и кнопок (OFL 1.1). */
object GothicFonts {
    private val cache = HashMap<Int, Typeface>()

    private fun font(context: Context, id: Int): Typeface =
        cache.getOrPut(id) { ResourcesCompat.getFont(context.applicationContext, id) ?: Typeface.SERIF }

    fun text(context: Context): Typeface = font(context, R.font.alegreya_regular)
    fun textMedium(context: Context): Typeface = font(context, R.font.alegreya_medium)
    fun caps(context: Context): Typeface = font(context, R.font.alegreya_sc_medium)
    fun capsBold(context: Context): Typeface = font(context, R.font.alegreya_sc_bold)
}

/** Панель, которая не шире [maxWidthPx]: на широком экране — колонка посередине, на узком — во всю ширину. */
class PanelLayout(context: Context) : LinearLayout(context) {
    var maxWidthPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec = if (mode != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthMeasureSpec) > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, mode)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/** То же для FrameLayout: колонка страницы (настройки, лицензии) не шире [maxWidthPx]. */
class BoundedFrameLayout(context: Context) : FrameLayout(context) {
    var maxWidthPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec = if (mode != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthMeasureSpec) > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/** Готовые элементы интерфейса в едином стиле — экраны собираются из них в коде. */
object Gothic {
    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Обычный текст на пергаменте. */
    fun body(context: Context, text: CharSequence? = null, sizeSp: Float = 16f): TextView = TextView(context).apply {
        typeface = GothicFonts.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(GothicPalette.PARCHMENT)
        setLineSpacing(0f, 1.12f)
        setShadowLayer(3f, 0f, 1f, 0xB0000000.toInt())
        this.text = text
    }

    /** Пояснение мельче и тише основного текста. */
    fun caption(context: Context, text: CharSequence? = null, sizeSp: Float = 14f): TextView =
        body(context, text, sizeSp).apply { setTextColor(GothicPalette.ASH) }

    /** Заголовок капителью: золото с разрядкой. */
    fun heading(context: Context, text: CharSequence? = null, sizeSp: Float = 22f): TextView = TextView(context).apply {
        typeface = GothicFonts.capsBold(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(GothicPalette.GOLD)
        letterSpacing = 0.06f
        setShadowLayer(4f, 0f, 1.5f, 0xD0000000.toInt())
        this.text = text
    }

    enum class ButtonKind { NORMAL, PRIMARY, DANGER }

    /** Кнопка в кованой рамке. Капитель, золото на тёмной коже. */
    fun button(
        context: Context,
        text: CharSequence,
        kind: ButtonKind = ButtonKind.NORMAL,
        textSizeSp: Float = 17f,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            typeface = GothicFonts.caps(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(3f, 0f, 1.2f, 0xE0000000.toInt())
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(context, 48f)
            minimumHeight = dp(context, 48f)
            restyle(this, kind)
            if (compact) setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
            setOnClickListener { onClick() }
        }

    /** Сменить вид кнопки (например, главная кнопка экрана стала второстепенной). */
    fun restyle(button: Button, kind: ButtonKind) {
        val normal = if (kind == ButtonKind.DANGER) 0xFFF0B9A6.toInt() else GothicPalette.GOLD_LIGHT
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(0x80A6957C.toInt(), 0xFFFFF6DC.toInt(), normal),
            ),
        )
        button.background = ButtonDrawable(button.resources.displayMetrics.density, kind)
    }

    /** Подсветка нажатия для пунктов списка, карточек и ссылок: прозрачное золото. */
    fun pressedHighlight(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), 0x33D9B566.toDrawable())
        addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
    }

    /** Узор-разделитель: линия, гаснущая к краям, с ромбом посередине. */
    fun ornament(context: Context): View = View(context).apply {
        background = OrnamentDrawable(resources.displayMetrics.density)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun ornamentParams(context: Context, widthDp: Float? = null, verticalMarginDp: Float = 6f) =
        LinearLayout.LayoutParams(widthDp?.let { dp(context, it) } ?: LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 14f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(context, verticalMarginDp)
            bottomMargin = dp(context, verticalMarginDp)
        }

    /** Вертикальная панель в рамке — для текста и кнопок. */
    fun panel(context: Context): PanelLayout = PanelLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = FrameDrawable(resources.displayMetrics.density)
    }

    /** Параметры для элемента в вертикальном столбце с отступом сверху. */
    fun column(context: Context, topDp: Float = 0f, width: Int = LinearLayout.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, topDp) }

    /** Всплывающее сообщение в рамке вместо системного серого. */
    fun toast(context: Context, text: CharSequence, long: Boolean = false) {
        val app = context.applicationContext
        val view = body(app, text, 15f).apply {
            background = FrameDrawable(resources.displayMetrics.density, compact = true)
            gravity = Gravity.CENTER
            maxWidth = dp(app, 420f)
        }
        @Suppress("DEPRECATION") // свой вид всплывающего сообщения: приложение на переднем плане
        Toast(app).apply {
            duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            this.view = view
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(app, 36f))
        }.show()
    }

    fun toast(context: Context, textId: Int, long: Boolean = false) = toast(context, context.getString(textId), long)
}
