package gothic2.mobile.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.storage.DiagnosticsShare
import gothic2.mobile.app.storage.DocumentExport
import gothic2.mobile.app.storage.SafGameTree
import gothic2.mobile.app.ui.gothic.Gothic
import gothic2.mobile.app.ui.gothic.Gothic.ButtonKind
import gothic2.mobile.app.ui.gothic.GothicPanels
import gothic2.mobile.app.ui.gothic.GothicScreens
import gothic2.mobile.app.ui.gothic.OverlayHost
import gothic2.mobile.app.ui.gothic.PanelButton
import gothic2.mobile.core.diagnostics.SessionJournal
import gothic2.mobile.core.gamedata.BuildFingerprint
import gothic2.mobile.core.gamedata.BuildVerifier
import gothic2.mobile.core.gamedata.BuildVerifier.Result
import gothic2.mobile.core.gamedata.GameImporter
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout

/**
 * Первый запуск: импорт легальной копии игры. При каждом следующем запуске экран
 * сразу передаёт управление [GameActivity] — пользователь видит только игру.
 *
 * Все состояния (перенос игры, выбор варианта, подготовка, сбой) — на одном фоне с искрами:
 * меняется только содержимое [content].
 */
class LauncherActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var panels: OverlayHost
    private lateinit var importPanel: GothicScreens.TextPanel
    private lateinit var choose: Button
    private lateinit var start: Button
    private lateinit var report: Button
    private var pending: Pair<Uri, Result>? = null
    private var verifiedUri: Uri? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) verify(uri)
    }

    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { out ->
        val source = verifiedUri
        if (out != null && source != null) writeReport(source, out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GothicScreens.immersive(this)
        val root = GothicScreens.backdrop(this)
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        panels = OverlayHost(root)
        onBackPressedDispatcher.addCallback(this, panels.backCallback)

        if (intent.getBooleanExtra(EXTRA_REIMPORT, false).not() && isGameReady()) {
            val interrupted = SessionJournal(app.paths.sessionMarker).takeInterrupted()
            if (interrupted != null) {
                // Процесс приложения погиб во время игры (вылет, нехватка памяти): вместе с ним погибла и игра.
                app.diagnostics.log("session", "interrupted: started=${interrupted.startedAt} last=${interrupted.lastHeartbeat}")
                showFailedLaunch(R.string.recovery_title, R.string.recovery_message)
            } else if (app.settings.lastSessionFailed) {
                showFailedLaunch(R.string.failed_launch_title, R.string.failed_launch_message)
            } else {
                startGame()
            }
            return
        }
        buildUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) GothicScreens.immersive(this)
    }

    private fun isGameReady(): Boolean {
        val build = app.importedBuild() ?: return false
        if (app.selectedVariant(build) == null) return false
        val sha = app.settings.importedExeSha256 ?: return false
        // Быстрая проверка на месте: EXE не подменён и на месте после обновлений.
        val exe = PathGameTree(app.paths.gameRoot).resolve(build.exePath) ?: return false
        return if (build.isPinned) build.exeSha256 == sha && java.nio.file.Files.exists(app.paths.gameRoot.resolve(exe)) else app.settings.devMode
    }

    /** Перенос игры: панель с пояснением справа, эмблема и название слева. */
    private fun buildUi() {
        importPanel = GothicScreens.textPanel(this, getString(R.string.import_title), getString(R.string.import_intro))
        choose = Gothic.button(this, getString(R.string.import_choose), ButtonKind.PRIMARY) { pickFolder.launch(null) }
        start = Gothic.button(this, getString(R.string.import_start), ButtonKind.PRIMARY) {
            pending?.let { (uri, r) -> import(uri, r) }
        }.apply { visibility = View.GONE }
        report = Gothic.button(this, getString(R.string.build_report_button)) {
            saveReport.launch("gothic2-build-report.txt")
        }.apply { visibility = View.GONE }
        GothicScreens.addButtons(importPanel, start)
        GothicScreens.addButtons(importPanel, choose, report)
        GothicScreens.swap(content, GothicScreens.split(this, null, importPanel.root))
    }

    private fun setMessage(text: CharSequence) {
        importPanel.message.text = text
    }

    private fun verify(uri: Uri) {
        setMessage(getString(R.string.import_checking))
        choose.isEnabled = false
        start.visibility = View.GONE
        app.background.execute {
            val result = runCatching { app.verifier.verify(SafGameTree(contentResolver, uri)) }
            runOnUiThread {
                choose.isEnabled = true
                result.onFailure { setMessage(getString(R.string.import_failed, it.message)) }
                result.onSuccess { r ->
                    var text = BuildVerifier.describe(r)
                    val allowed = r is Result.Supported || (r is Result.Unpinned && app.settings.devMode)
                    if (r is Result.Unpinned && allowed) text += "\n\n" + getString(R.string.dev_unpinned_warning)
                    importPanel.title.text = getString(if (allowed) R.string.import_found_title else R.string.import_unknown_title)
                    setMessage(text)
                    pending = if (allowed) uri to r else null
                    start.visibility = if (allowed) View.VISIBLE else View.GONE
                    // Найдена игра — главная кнопка теперь «Перенести», выбор папки — второстепенный.
                    choose.text = getString(if (allowed) R.string.import_choose_other else R.string.import_choose)
                    Gothic.restyle(choose, if (allowed) ButtonKind.NORMAL else ButtonKind.PRIMARY)
                    // Отчёт полезен и для неопознанной копии: по нему сборку можно добавить.
                    verifiedUri = uri.takeIf { r != Result.NotGothic2 }
                    report.visibility = if (verifiedUri != null) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun import(uri: Uri, result: Result) {
        val (build, sha) = when (result) {
            is Result.Supported -> result.build to result.exeSha256
            is Result.Unpinned -> result.build to result.exeSha256
            else -> return
        }
        choose.isEnabled = false
        start.isEnabled = false
        report.isEnabled = false
        importPanel.progress.visibility = View.VISIBLE
        importPanel.progress.indeterminate = false
        app.diagnostics.log("import", "start build=${build.id}")
        app.background.execute {
            val outcome = runCatching {
                GameImporter(app.paths.gameRoot).import(
                    SafGameTree(contentResolver, uri),
                    build,
                    sha,
                    progress = { copied, total, _ ->
                        val permille = if (total == 0L) 1000 else (copied * 1000 / total).toInt()
                        runOnUiThread {
                            importPanel.progress.progress = permille / 1000f
                            setMessage(getString(R.string.import_copying, permille / 10))
                        }
                    },
                    isCancelled = { isFinishing },
                )
            }
            runOnUiThread {
                outcome.onSuccess {
                    app.settings.importedBuildId = build.id
                    app.settings.importedExeSha256 = sha
                    app.diagnostics.log("import", "done")
                    chooseVariantThenStart(build)
                }
                outcome.onFailure {
                    app.diagnostics.log("import", "failed: $it")
                    importPanel.progress.visibility = View.GONE
                    choose.isEnabled = true
                    start.isEnabled = true
                    report.isEnabled = true
                    setMessage(getString(R.string.import_failed, it.message))
                }
            }
        }
    }

    /**
     * Прошлая игра закрылась сразу после запуска или была прервана: не запускать снова молча, а
     * предложить журнал, другой режим графики и резервные копии сохранений (раздел 17 плана).
     */
    private fun showFailedLaunch(title: Int, message: Int) {
        val panel = GothicScreens.textPanel(
            this,
            getString(title),
            getString(message) + "\n\n" + getString(R.string.failed_launch_graphics, app.settings.graphicsMode.title),
        )
        GothicScreens.addButtons(
            panel,
            Gothic.button(this, getString(R.string.failed_launch_retry), ButtonKind.PRIMARY) {
                app.settings.lastSessionFailed = false
                startGame()
            },
            // Самая частая причина — графика: следующий режим из GraphicsMode.
            Gothic.button(this, getString(R.string.failed_launch_other_graphics)) {
                val next = app.settings.graphicsMode.next()
                app.settings.graphicsMode = next
                app.settings.lastSessionFailed = false
                app.diagnostics.log("launch", "graphics mode -> ${next.id}")
                startGame()
            },
        )
        GothicScreens.addButtons(
            panel,
            Gothic.button(this, getString(R.string.failed_launch_send)) { DiagnosticsShare.share(this) },
            Gothic.button(this, getString(R.string.settings_title)) { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        GothicScreens.swap(content, GothicScreens.split(this, currentVariantTitle(), panel.root))
    }

    /** Отчёт о сборке из выбранной папки — без копирования игры в приложение. */
    private fun writeReport(source: Uri, out: Uri) {
        report.isEnabled = false
        importPanel.progress.visibility = View.VISIBLE
        importPanel.progress.indeterminate = false
        app.background.execute {
            val result = runCatching {
                val r = BuildFingerprint.collect(
                    SafGameTree(contentResolver, source),
                    app.verifier,
                    onProgress = { done, total, _ ->
                        val permille = if (total == 0L) 1000 else (done * 1000 / total).toInt()
                        runOnUiThread {
                            importPanel.progress.progress = permille / 1000f
                            setMessage(getString(R.string.build_report_progress, permille / 10))
                        }
                    },
                    isCancelled = { isFinishing },
                )
                DocumentExport.write(contentResolver, out, cacheDir) { it.write(BuildFingerprint.format(r, BuildConfig.VERSION_NAME).toByteArray()) }
            }
            runOnUiThread {
                report.isEnabled = true
                importPanel.progress.visibility = View.GONE
                setMessage(result.fold({ getString(R.string.build_report_saved) }, { getString(R.string.import_failed, it.message) }))
            }
        }
    }

    /** Если в сборке несколько подтверждённых вариантов (Gold: «Ночь Ворона» и классика) — спросить. */
    private fun chooseVariantThenStart(build: SupportedBuild) {
        val variants = build.definedVariants()
        if (variants.size < 2) {
            app.settings.variantId = variants.firstOrNull()?.id
            startGame()
            return
        }
        val choice = GothicScreens.choice(
            this,
            getString(R.string.variant_choose_title),
            getString(R.string.variant_change_hint),
            variants.map { v ->
                GothicScreens.Choice(VariantText.title(this, v), VariantText.description(this, v)) {
                    app.settings.variantId = v.id
                    startGame()
                }
            },
        )
        GothicScreens.swap(content, choice)
    }

    private fun currentVariantTitle(): String? {
        val build = app.importedBuild() ?: return null
        return app.selectedVariant(build)?.let { VariantText.title(this, it) }
    }

    /** Подготовка среды (при первом запуске — загрузка и установка) с прогрессом, затем экран игры. */
    private fun startGame() {
        val build = app.importedBuild()
        val variant = build?.let { app.selectedVariant(it) }
        if (build == null || variant == null) return buildUi()
        val loading = GothicScreens.loading(this, VariantText.title(this, variant), getString(R.string.launch_preparing))
        GothicScreens.swap(content, loading.root)
        app.background.execute {
            val result = runCatching {
                GameLauncher(app).prepare(build, variant) { step, percent ->
                    runOnUiThread { loading.setStep(step, percent) }
                }
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    // Экран загрузки игры выглядит так же — без анимации перехода смена незаметна.
                    startActivity(GameActivity.intent(this, prepared))
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
                result.onFailure { e ->
                    app.diagnostics.log("launch", "prepare failed: $e")
                    // Файлы варианта не должны остаться спрятанными, если запуск не состоялся.
                    runCatching { VariantLayout(app.paths.gameRoot).restore(build) }
                    GothicPanels.message(
                        panels,
                        getString(R.string.launch_failed_title),
                        getString(R.string.launch_failed_message, e.message ?: e.toString()),
                        listOf(
                            PanelButton(getString(R.string.settings_title)) {
                                startActivity(Intent(this, SettingsActivity::class.java))
                                finish()
                            },
                            PanelButton(getString(R.string.launch_retry), ButtonKind.PRIMARY) { startGame() },
                        ),
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_REIMPORT = "reimport"
    }
}
