package gothic2.mobile.app.ui.gothic

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback

/** Где показывается панель. */
interface PanelHost {
    val context: Context

    /** Показывает [scrim] (затемнение с панелью); возвращает закрытие. */
    fun open(scrim: View, onBack: () -> Unit): () -> Unit
}

/**
 * Панель слоем поверх экрана: отдельное окно-диалог вывело бы экран из полноэкранного режима.
 * Пока панель открыта, «Назад» закрывает её: [backCallback] для обычных экранов,
 * [GothicPanels.handleBack] — для экрана игры, у которого своя обработка «Назад».
 */
class OverlayHost(private val parent: FrameLayout) : PanelHost {
    override val context: Context get() = parent.context
    var onBack: (() -> Unit)? = null
        private set

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onBack?.invoke()
        }
    }

    override fun open(scrim: View, onBack: () -> Unit): () -> Unit {
        parent.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        this.onBack = onBack
        backCallback.isEnabled = true
        return {
            parent.removeView(scrim)
            if (this.onBack === onBack) {
                this.onBack = null
                backCallback.isEnabled = false
            }
        }
    }

    val isOpen: Boolean get() = onBack != null
}

/** Кнопка панели. */
class PanelButton(
    val text: CharSequence,
    val kind: Gothic.ButtonKind = Gothic.ButtonKind.NORMAL,
    val action: () -> Unit = {},
)

/**
 * Окна в стиле игры: сообщение с кнопками, выбор из списка, сетка действий, длинный текст.
 * Каждая функция возвращает закрытие панели.
 */
object GothicPanels {
    /** Сообщение: заголовок, узор, текст и кнопки в ряд. Нажатие кнопки закрывает панель. */
    fun message(
        host: PanelHost,
        title: CharSequence?,
        message: CharSequence?,
        buttons: List<PanelButton>,
        cancelable: Boolean = true,
        onCancel: () -> Unit = {},
    ): () -> Unit {
        val ctx = host.context
        var close: () -> Unit = {}
        val body = message?.let { Gothic.body(ctx, it, 17f).apply { gravity = Gravity.CENTER_HORIZONTAL } }
        val row = buttonRow(ctx, buttons) { close() }
        close = show(host, frame(ctx, title, body, row), cancelable, onCancel)
        return close
    }

    /** Выбор одного пункта; текущий отмечен ромбом. */
    fun choose(
        host: PanelHost,
        title: CharSequence?,
        items: List<CharSequence>,
        checked: Int = -1,
        onChoose: (Int) -> Unit,
    ): () -> Unit {
        val ctx = host.context
        var close: () -> Unit = {}
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, item ->
            if (i > 0) list.addView(divider(ctx))
            list.addView(row(ctx, item, i == checked) { close(); onChoose(i) })
        }
        close = show(host, frame(ctx, title, list, null), cancelable = true, onCancel = {})
        return close
    }

    /**
     * Сетка действий (быстрое меню игры): кнопки по [columns] в ряд, под ними — ряд [footer]
     * (у первой кнопки вес 1, у остальных 2). Мельче обычных, чтобы меню помещалось без прокрутки.
     */
    fun grid(host: PanelHost, title: CharSequence?, buttons: List<PanelButton>, columns: Int, footer: List<PanelButton>, onCancel: () -> Unit = {}): () -> Unit {
        val ctx = host.context
        var close: () -> Unit = {}
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun addRow(items: List<PanelButton?>, weights: List<Float>, top: Float) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { c, b ->
                val view = if (b == null) View(ctx) else Gothic.button(ctx, b.text, b.kind, textSizeSp = 16f, compact = true) { close(); b.action() }
                line.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[c]).apply {
                    if (c > 0) leftMargin = Gothic.dp(ctx, 8f)
                })
            }
            grid.addView(line, Gothic.column(ctx, top))
        }
        buttons.chunked(columns).forEachIndexed { r, chunk ->
            addRow(List(columns) { chunk.getOrNull(it) }, List(columns) { 1f }, if (r > 0) 8f else 0f)
        }
        if (footer.isNotEmpty()) addRow(footer, footer.indices.map { if (it == 0) 1f else 2f }, 14f)
        close = show(host, frame(ctx, title, grid, null, wide = true), cancelable = true, onCancel = onCancel)
        return close
    }

    /** Длинный текст (лицензии) с прокруткой и кнопкой «Закрыть». */
    fun text(host: PanelHost, title: CharSequence?, text: CharSequence, close: CharSequence): () -> Unit {
        val ctx = host.context
        var closeFn: () -> Unit = {}
        val body = Gothic.body(ctx, text, 13f).apply { setTextIsSelectable(true) }
        val row = buttonRow(ctx, listOf(PanelButton(close))) { closeFn() }
        closeFn = show(host, frame(ctx, title, body, row, wide = true), cancelable = true, onCancel = {})
        return closeFn
    }

    /** Пункт списка: ромб-метка и текст; нажатие подсвечивается золотом. */
    fun row(ctx: Context, text: CharSequence, checked: Boolean, onClick: () -> Unit): TextView =
        Gothic.body(ctx, text, 18f).apply {
            val d = resources.displayMetrics.density
            setTextColor(if (checked) GothicPalette.GOLD_LIGHT else GothicPalette.PARCHMENT)
            typeface = if (checked) GothicFonts.textMedium(ctx) else GothicFonts.text(ctx)
            setCompoundDrawablesRelativeWithIntrinsicBounds(DiamondDrawable(d, checked), null, null, null)
            compoundDrawablePadding = Gothic.dp(ctx, 12f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = Gothic.dp(ctx, 48f)
            setPadding(Gothic.dp(ctx, 8f), Gothic.dp(ctx, 8f), Gothic.dp(ctx, 8f), Gothic.dp(ctx, 8f))
            background = Gothic.pressedHighlight()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundResource(gothic2.mobile.app.R.drawable.gothic_divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Gothic.dp(ctx, 1f))
    }

    /** Нажатие «Назад» в экране игры: закрыть открытую панель. true — нажатие обработано. */
    fun handleBack(host: OverlayHost): Boolean {
        val back = host.onBack ?: return false
        back()
        return true
    }

    private fun buttonRow(ctx: Context, buttons: List<PanelButton>, close: () -> Unit): LinearLayout? {
        if (buttons.isEmpty()) return null
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { i, b ->
                addView(
                    Gothic.button(ctx, b.text, b.kind) { close(); b.action() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) leftMargin = Gothic.dp(ctx, 12f)
                    },
                )
            }
        }
    }

    /** Рамка: заголовок, узор, прокручиваемое содержимое, кнопки. */
    private fun frame(ctx: Context, title: CharSequence?, content: View?, buttons: View?, wide: Boolean = false): PanelLayout {
        val panel = Gothic.panel(ctx)
        if (title != null) {
            panel.addView(Gothic.heading(ctx, title, 22f).apply { gravity = Gravity.CENTER_HORIZONTAL }, Gothic.column(ctx))
            panel.addView(Gothic.ornament(ctx), Gothic.ornamentParams(ctx, 200f, 4f))
        }
        if (content != null) {
            panel.addView(
                ScrollView(ctx).apply {
                    isFillViewport = false
                    isVerticalFadingEdgeEnabled = true
                    setFadingEdgeLength(Gothic.dp(ctx, 16f))
                    addView(content)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { topMargin = Gothic.dp(ctx, 4f) },
            )
        }
        if (buttons != null) panel.addView(buttons, Gothic.column(ctx, 16f))
        panel.maxWidthPx = Gothic.dp(ctx, if (wide) 660f else 540f)
        panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val h = Gothic.dp(ctx, 24f)
            val v = Gothic.dp(ctx, 12f)
            setMargins(h, v, h, v)
        }
        return panel
    }

    /** Затемнение с панелью посередине; касание мимо панели — отмена. */
    private fun show(host: PanelHost, panel: ViewGroup, cancelable: Boolean, onCancel: () -> Unit): () -> Unit {
        val ctx = host.context
        var close: () -> Unit = {}
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0xB8000000.toInt())
            isClickable = true
            isFocusable = true
            addView(panel)
        }
        panel.isClickable = true // касания по панели не доходят до затемнения
        val cancel = {
            if (cancelable) {
                close()
                onCancel()
            }
        }
        scrim.setOnClickListener { cancel() }
        close = host.open(scrim) { cancel() }
        scrim.alpha = 0f
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        scrim.animate().alpha(1f).setDuration(160).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        return close
    }
}
