package gothic2.mobile.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import gothic2.mobile.app.R
import gothic2.mobile.app.ui.gothic.Gothic
import gothic2.mobile.app.ui.gothic.GothicFonts
import gothic2.mobile.app.ui.gothic.GothicPalette
import gothic2.mobile.app.ui.gothic.GothicPanels
import gothic2.mobile.app.ui.gothic.GothicScreens
import gothic2.mobile.app.ui.gothic.OverlayHost

/**
 * Авторы и лицензии шрифтов и компонентов (LGPL, OFL, MIT, zlib и Apache требуют уведомлений и
 * текстов лицензий). Единственное место в приложении, где они названы, — в глубине настроек.
 */
class LicensesActivity : AppCompatActivity() {
    private lateinit var panels: OverlayHost

    private class Entry(val name: String, val detail: String, val licenseFile: String?)

    private val entries = listOf(
        Entry("Шрифты Alegreya и Alegreya SC", "© 2011 The Alegreya Project Authors · SIL Open Font License 1.1", "OFL-1.1.txt"),
        Entry(
            "Буква «G» значка",
            "Нарисована по шрифту UnifrakturMaguntia: © 2010 j. 'mach' wust, © 2009 Peter Wiegel · SIL Open Font License 1.1",
            "OFL-1.1.txt",
        ),
        Entry(
            "Winlator 11.2",
            "© brunodev85 и участники · GNU LGPL 2.1. Исходный код: github.com/brunodev85/winlator-app; наши изменения — " +
                "third_party/winlator.patch в исходниках приложения",
            "LGPL-2.1.txt",
        ),
        Entry("Wine", "© авторы проекта Wine · GNU LGPL 2.1 или новее. Исходный код: gitlab.winehq.org/wine/wine", "LGPL-2.1.txt"),
        Entry("Box64", "© Sebastien Chevalier (ptitSeb) и участники · MIT", "MIT.txt"),
        Entry(
            "DXVK и D7VK",
            "© Philip Rebohle, Joshua Ashton и участники; D7VK © WinterSnowfall · zlib. D7VK 1.11 изменён: исправлена ошибка GetCaps",
            "Zlib.txt",
        ),
        Entry("Mesa (Turnip, Zink)", "© авторы Mesa · MIT", "MIT.txt"),
        Entry("AndroidX, Material Components", "© The Android Open Source Project · Apache License 2.0", "Apache-2.0.txt"),
        Entry("Kotlin", "© JetBrains s.r.o. и участники · Apache License 2.0", "Apache-2.0.txt"),
        Entry("Apache Commons Compress, zstd-jni, XZ for Java", "Apache License 2.0 · BSD · общественное достояние", "Apache-2.0.txt"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GothicScreens.immersive(this)
        val root = GothicScreens.backdrop(this)
        val body = GothicScreens.page(this, root, getString(R.string.licenses_title)) { onBackPressedDispatcher.onBackPressed() }
        setContentView(root)
        panels = OverlayHost(root)
        onBackPressedDispatcher.addCallback(this, panels.backCallback)

        val panel = Gothic.panel(this)
        panel.addView(Gothic.body(this, getString(R.string.licenses_intro), 16f), Gothic.column(this))
        for (e in entries) {
            panel.addView(GothicPanels.divider(this).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, Gothic.dp(context, 14f), 0, Gothic.dp(context, 10f))
            })
            panel.addView(Gothic.heading(this, e.name, 18f), Gothic.column(this))
            panel.addView(Gothic.caption(this, e.detail, 14f), Gothic.column(this, 2f))
            e.licenseFile?.let { file -> panel.addView(link(getString(R.string.licenses_full_text)) { showLicense(e.name, file) }, Gothic.column(this, 2f, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        }
        body.addView(
            ScrollView(this).apply {
                clipToPadding = false
                setPadding(0, Gothic.dp(context, 12f), 0, Gothic.dp(context, 24f))
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(Gothic.dp(context, 24f))
                addView(panel)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) GothicScreens.immersive(this)
    }

    /** Ссылка внутри панели: капитель золотом, нажатие подсвечивается. */
    private fun link(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        typeface = GothicFonts.caps(context)
        textSize = 15f
        setTextColor(GothicPalette.GOLD)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = Gothic.dp(context, 40f)
        setPadding(0, 0, Gothic.dp(context, 12f), 0)
        background = Gothic.pressedHighlight()
        setOnClickListener { onClick() }
    }

    private fun showLicense(title: String, file: String) {
        val text = runCatching { assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrElse { it.toString() }
        GothicPanels.text(panels, title, text, getString(R.string.close))
    }
}
