package gothic2.mobile.app.ui.gothic

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isNotEmpty
import gothic2.mobile.app.R

/**
 * Общий облик экранов вне игры: фон с отсветом и искрами, эмблема с «G» и название игры, панели
 * в рамках. Экран загрузки одинаков в лаунчере и в начале игры — переход между ними не заметен.
 */
object GothicScreens {
    /** Полноэкранный режим, как в игре: системные панели появляются только по жесту. */
    fun immersive(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Корень экрана: фон и искры; содержимое кладётся поверх. */
    fun backdrop(ctx: Context, emberFrameMs: Long = 16): FrameLayout = FrameLayout(ctx).apply {
        background = ScreenBackgroundDrawable(resources.displayMetrics.density)
        addView(EmberView(ctx, frameMs = emberFrameMs), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Эмблема, название игры и подзаголовок (например, вариант игры). */
    fun brand(ctx: Context, subtitle: CharSequence?, compact: Boolean = isShort(ctx)): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val emblem = if (compact) 96f else 120f
        addView(EmblemView(ctx), LinearLayout.LayoutParams(Gothic.dp(ctx, emblem), Gothic.dp(ctx, emblem)))
        addView(
            GoldTextView(ctx, if (compact) 38f else 46f).apply {
                text = ctx.getString(R.string.game_title)
                gravity = Gravity.CENTER
                maxLines = 1
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Gothic.dp(ctx, 6f)
            },
        )
        addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 200f, 4f))
        if (subtitle != null) {
            addView(
                TextView(ctx).apply {
                    text = subtitle
                    typeface = GothicFonts.caps(ctx)
                    textSize = 16f
                    letterSpacing = 0.14f
                    setTextColor(GothicPalette.ASH)
                    gravity = Gravity.CENTER
                    setShadowLayer(3f, 0f, 1f, 0xC0000000.toInt())
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /**
     * Два столбца: слева эмблема и название, справа [panel] (прокручивается, если не помещается).
     */
    fun split(ctx: Context, subtitle: CharSequence?, panel: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Gothic.dp(ctx, 20f)
        setPadding(pad, pad, pad, pad)
        addView(brand(ctx, subtitle), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        addView(
            FrameLayout(ctx).apply {
                addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f).apply { leftMargin = Gothic.dp(ctx, 20f) },
        )
    }

    /** Панель с заголовком, текстом (с прокруткой) и местом под кнопки. */
    class TextPanel(val root: LinearLayout, val title: TextView, val message: TextView, val progress: GothicProgressView, val buttons: LinearLayout)

    fun textPanel(ctx: Context, title: CharSequence, message: CharSequence?): TextPanel {
        val panel = Gothic.panel(ctx)
        val heading = Gothic.heading(ctx, title, 24f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(heading, Gothic.column(ctx))
        panel.addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 220f, 4f))
        val body = Gothic.body(ctx, message, 16.5f)
        panel.addView(
            ScrollView(ctx).apply {
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(Gothic.dp(ctx, 14f))
                addView(body)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { topMargin = Gothic.dp(ctx, 4f) },
        )
        val progress = GothicProgressView(ctx).apply { visibility = View.GONE }
        panel.addView(progress, Gothic.column(ctx, 14f))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(buttons, Gothic.column(ctx, 12f))
        return TextPanel(panel, heading, body, progress, buttons)
    }

    /** Ряд кнопок равной ширины под текстом панели. */
    fun addButtons(panel: TextPanel, vararg buttons: View) {
        val ctx = panel.root.context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = Gothic.dp(ctx, 10f)
            })
        }
        panel.buttons.addView(row, Gothic.column(ctx, if (panel.buttons.isNotEmpty()) 8f else 0f))
    }

    /** Экран загрузки (без фона — его даёт [backdrop]): эмблема, название, шаг, полоса и советы внизу. */
    class Loading(val root: FrameLayout, val status: TextView, val progress: GothicProgressView, val tip: TipView) {
        /** [percent] null — без процента (бегущий отблеск). */
        fun setStep(text: CharSequence, percent: Int?) {
            status.text = text
            progress.indeterminate = percent == null
            if (percent != null) progress.progress = percent / 100f
        }
    }

    fun loading(ctx: Context, subtitle: CharSequence?, status: CharSequence): Loading {
        val root = FrameLayout(ctx)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(brand(ctx, subtitle))
        val statusView = TextView(ctx).apply {
            text = status
            typeface = GothicFonts.text(ctx)
            textSize = 17f
            setTextColor(GothicPalette.PARCHMENT)
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, 0xC0000000.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Gothic.dp(ctx, if (isShort(ctx)) 14f else 24f)
        })
        val bar = GothicProgressView(ctx)
        column.addView(bar, LinearLayout.LayoutParams(Gothic.dp(ctx, 320f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Gothic.dp(ctx, 10f)
        })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            bottomMargin = Gothic.dp(ctx, 24f)
        })
        val tip = TipView(ctx, ctx.resources.getStringArray(R.array.loading_tips))
        root.addView(tip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            val side = Gothic.dp(ctx, 48f)
            leftMargin = side
            rightMargin = side
            bottomMargin = Gothic.dp(ctx, 14f)
        })
        return Loading(root, statusView, bar, tip)
    }

    class Choice(val title: CharSequence, val description: CharSequence?, val onClick: () -> Unit)

    /** Выбор из карточек (вариант игры): заголовок золотом, ряд карточек одной высоты, пояснение. */
    fun choice(ctx: Context, title: CharSequence, hint: CharSequence?, items: List<Choice>): FrameLayout {
        val column = Gothic.panel(ctx).apply {
            background = null
            gravity = Gravity.CENTER_HORIZONTAL
            maxWidthPx = Gothic.dp(ctx, 760f)
            val pad = Gothic.dp(ctx, 24f)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(GoldTextView(ctx, 34f).apply {
            text = title
            gravity = Gravity.CENTER
        })
        column.addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 260f, 6f))
        val cards = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, item ->
            cards.addView(
                card(ctx, item.title, item.description, item.onClick),
                // Высота по самой высокой карточке: ряд одинаковых рамок.
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = Gothic.dp(ctx, 16f)
                },
            )
        }
        column.addView(cards, Gothic.column(ctx, 10f))
        if (hint != null) column.addView(Gothic.caption(ctx, hint).apply { gravity = Gravity.CENTER }, Gothic.column(ctx, 14f))
        return FrameLayout(ctx).apply {
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    /** Карточка выбора (вариант игры): рамка, заголовок, описание; нажатие подсвечивается. */
    fun card(ctx: Context, title: CharSequence, description: CharSequence?, onClick: () -> Unit): LinearLayout = Gothic.panel(ctx).apply {
        gravity = Gravity.CENTER
        addView(Gothic.heading(ctx, title, 22f).apply { gravity = Gravity.CENTER }, Gothic.column(ctx))
        addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 140f, 4f))
        if (description != null) addView(Gothic.body(ctx, description, 15.5f).apply { gravity = Gravity.CENTER }, Gothic.column(ctx))
        foreground = Gothic.pressedHighlight()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * Страница меню (настройки, лицензии): заголовок золотом со стрелкой «Назад» ([onBack]), узор
     * и колонка содержимого не шире [maxWidthDp] посередине. Возвращает колонку.
     */
    fun page(ctx: Context, root: FrameLayout, title: CharSequence, maxWidthDp: Float = 720f, framed: Boolean = false, onBack: () -> Unit): FrameLayout {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = FrameLayout(ctx)
        header.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_gothic_back)
                contentDescription = ctx.getString(R.string.back)
                background = Gothic.pressedHighlight()
                setOnClickListener { onBack() }
            },
            FrameLayout.LayoutParams(Gothic.dp(ctx, 56f), Gothic.dp(ctx, 56f), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        header.addView(
            GoldTextView(ctx, 30f).apply {
                text = title
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Gothic.dp(ctx, 60f)))
        column.addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 360f, 0f))
        // В рамке содержимое прокручивается внутри неподвижной «страницы».
        val body = BoundedFrameLayout(ctx).apply {
            maxWidthPx = Gothic.dp(ctx, maxWidthDp)
            if (framed) background = FrameDrawable(ctx.resources.displayMetrics.density, fillAlpha = 0xD0)
        }
        column.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                leftMargin = Gothic.dp(ctx, 8f)
                rightMargin = Gothic.dp(ctx, 8f)
                topMargin = Gothic.dp(ctx, if (framed) 10f else 0f)
                bottomMargin = Gothic.dp(ctx, if (framed) 12f else 0f)
            },
        )
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            val pad = Gothic.dp(ctx, 8f)
            setMargins(pad, pad, pad, 0)
        })
        return body
    }

    /** Плавная смена содержимого [container]. */
    fun swap(container: ViewGroup, content: View) {
        container.removeAllViews()
        content.alpha = 0f
        container.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.animate().alpha(1f).setDuration(220).start()
    }

    /** Невысокий экран (ландшафт ≤ 380 dp): эмблема и название поменьше. */
    fun isShort(ctx: Context): Boolean {
        val m = ctx.resources.displayMetrics
        return minOf(m.widthPixels, m.heightPixels) / m.density < 380f
    }
}

/** Эмблема с «G» и мерцающий за ней отсвет огня. */
class EmblemView(context: Context) : FrameLayout(context) {
    private val glow = View(context).apply { background = GlowDrawable() }
    private var pulse: ObjectAnimator? = null

    init {
        clipChildren = false
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            val m = -Gothic.dp(context, 28f)
            setMargins(m, m, m, m)
        })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.gothic_emblem)
            contentDescription = context.getString(R.string.game_title)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.clipChildren = false
        pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.45f, 1f).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}

/** Советы на экране загрузки: сменяются раз в несколько секунд с плавным переходом. */
@SuppressLint("ViewConstructor")
class TipView(context: Context, private val tips: Array<String>) : AppCompatTextView(context) {
    private val ticker = Handler(Looper.getMainLooper())
    private var index = (System.currentTimeMillis() / 1000 % tips.size.coerceAtLeast(1)).toInt()
    private val next = object : Runnable {
        override fun run() {
            animate().alpha(0f).setDuration(350).withEndAction {
                index = (index + 1) % tips.size
                text = tips[index]
                animate().alpha(1f).setDuration(350).start()
            }.start()
            ticker.postDelayed(this, PERIOD_MS)
        }
    }

    init {
        typeface = GothicFonts.text(context)
        textSize = 15f
        setTextColor(GothicPalette.ASH)
        gravity = Gravity.CENTER
        setShadowLayer(3f, 0f, 1f, 0xC0000000.toInt())
        maxLines = 2
        if (tips.isNotEmpty()) text = tips[index]
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (tips.size > 1) ticker.postDelayed(next, PERIOD_MS)
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(next)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PERIOD_MS = 7000L
    }
}
