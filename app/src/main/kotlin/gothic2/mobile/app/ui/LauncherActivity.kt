package gothic2.mobile.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.storage.DiagnosticsShare
import gothic2.mobile.app.storage.DocumentExport
import gothic2.mobile.app.storage.SafGameTree
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
 */
class LauncherActivity : AppCompatActivity() {
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
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
        if (intent.getBooleanExtra(EXTRA_REIMPORT, false).not() && isGameReady()) {
            val interrupted = SessionJournal(app.paths.sessionMarker).takeInterrupted()
            if (interrupted == null && app.settings.lastSessionFailed) {
                showFailedLaunch()
            } else if (interrupted == null) {
                startGame()
            } else {
                app.diagnostics.log("session", "interrupted: started=${interrupted.startedAt} last=${interrupted.lastHeartbeat}")
                showRecovery()
            }
            return
        }
        buildUi()
    }

    private fun isGameReady(): Boolean {
        val build = app.importedBuild() ?: return false
        if (app.selectedVariant(build) == null) return false
        val sha = app.settings.importedExeSha256 ?: return false
        // Быстрая проверка на месте: EXE не подменён и на месте после обновлений.
        val exe = PathGameTree(app.paths.gameRoot).resolve(build.exePath) ?: return false
        return if (build.isPinned) build.exeSha256 == sha && java.nio.file.Files.exists(app.paths.gameRoot.resolve(exe)) else app.settings.devMode
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        message = TextView(this).apply {
            setTextColor(0xFFE8DCC0.toInt())
            textSize = 16f
            text = getString(R.string.import_intro)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        choose = Button(this).apply {
            text = getString(R.string.import_choose)
            setOnClickListener { pickFolder.launch(null) }
        }
        start = Button(this).apply {
            text = getString(R.string.import_start)
            visibility = View.GONE
            setOnClickListener { pending?.let { (uri, r) -> import(uri, r) } }
        }
        report = Button(this).apply {
            text = getString(R.string.build_report_button)
            visibility = View.GONE
            setOnClickListener { saveReport.launch("gothic2-build-report.txt") }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(message)
            addView(progress)
            addView(choose)
            addView(start)
            addView(report)
        })
    }

    private fun verify(uri: Uri) {
        message.text = getString(R.string.import_checking)
        choose.isEnabled = false
        start.visibility = View.GONE
        app.background.execute {
            val result = runCatching { app.verifier.verify(SafGameTree(contentResolver, uri)) }
            runOnUiThread {
                choose.isEnabled = true
                result.onFailure { message.text = getString(R.string.import_failed, it.message) }
                result.onSuccess { r ->
                    var text = BuildVerifier.describe(r)
                    val allowed = r is Result.Supported || (r is Result.Unpinned && app.settings.devMode)
                    if (r is Result.Unpinned && allowed) text += "\n\n" + getString(R.string.dev_unpinned_warning)
                    message.text = text
                    pending = if (allowed) uri to r else null
                    start.visibility = if (allowed) View.VISIBLE else View.GONE
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
        progress.visibility = View.VISIBLE
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
                            progress.progress = permille
                            message.text = getString(R.string.import_copying, permille / 10)
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
                    progress.visibility = View.GONE
                    choose.isEnabled = true
                    start.isEnabled = true
                    message.text = getString(R.string.import_failed, it.message)
                }
            }
        }
    }

    /** Прошлая игра закрылась сразу после запуска: не запускать снова молча, а предложить журнал. */
    private fun showFailedLaunch() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun button(text: Int, action: () -> Unit) = Button(this).apply {
            setText(text)
            setOnClickListener { action() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@LauncherActivity).apply {
                setTextColor(0xFFE8DCC0.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                text = getString(R.string.failed_launch_message, app.settings.graphicsMode.title)
            })
            addView(button(R.string.failed_launch_send) { DiagnosticsShare.share(this@LauncherActivity) })
            addView(button(R.string.failed_launch_retry) {
                app.settings.lastSessionFailed = false
                startGame()
            })
            // Самая частая причина — графика: следующий режим из GraphicsMode.
            val next = app.settings.graphicsMode.next()
            addView(Button(this@LauncherActivity).apply {
                text = getString(R.string.failed_launch_other_graphics, next.title)
                setOnClickListener {
                    app.settings.graphicsMode = next
                    app.settings.lastSessionFailed = false
                    app.diagnostics.log("launch", "graphics mode -> ${next.id}")
                    startGame()
                }
            })
            addView(button(R.string.settings_title) { startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java)) })
        })
    }

    /** Экран восстановления после нештатного завершения (раздел 17 плана). */
    private fun showRecovery() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_title)
            .setMessage(R.string.recovery_message)
            .setPositiveButton(R.string.recovery_continue) { _, _ -> startGame() }
            .setNeutralButton(R.string.recovery_restore) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Отчёт о сборке из выбранной папки — без копирования игры в приложение. */
    private fun writeReport(source: Uri, out: Uri) {
        report.isEnabled = false
        progress.visibility = View.VISIBLE
        app.background.execute {
            val result = runCatching {
                val r = BuildFingerprint.collect(
                    SafGameTree(contentResolver, source),
                    app.verifier,
                    onProgress = { done, total, _ ->
                        val permille = if (total == 0L) 1000 else (done * 1000 / total).toInt()
                        runOnUiThread {
                            progress.progress = permille
                            message.text = getString(R.string.build_report_progress, permille / 10)
                        }
                    },
                    isCancelled = { isFinishing },
                )
                DocumentExport.write(contentResolver, out, cacheDir) { it.write(BuildFingerprint.format(r, BuildConfig.VERSION_NAME).toByteArray()) }
            }
            runOnUiThread {
                report.isEnabled = true
                progress.visibility = View.GONE
                message.text = result.fold({ getString(R.string.build_report_saved) }, { getString(R.string.import_failed, it.message) })
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
        AlertDialog.Builder(this)
            .setTitle(R.string.variant_choose_title)
            .setItems(variants.map { it.title }.toTypedArray()) { _, i ->
                app.settings.variantId = variants[i].id
                Toast.makeText(this, R.string.variant_change_hint, Toast.LENGTH_LONG).show()
                startGame()
            }
            .setCancelable(false)
            .show()
    }

    /** Подготовка среды (при первом запуске — установка Wine) с прогрессом, затем экран игры. */
    private fun startGame() {
        val build = app.importedBuild()
        val variant = build?.let { app.selectedVariant(it) }
        if (build == null || variant == null) return buildUi()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply {
            setTextColor(0xFFE8DCC0.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            text = getString(R.string.launch_preparing)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(bar)
        })
        app.background.execute {
            val result = runCatching {
                GameLauncher(app).prepare(build, variant) { step, percent ->
                    runOnUiThread {
                        status.text = if (percent == null) step else "$step $percent%"
                        bar.isIndeterminate = percent == null
                        if (percent != null) bar.progress = percent
                    }
                }
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    startActivity(GameActivity.intent(this, prepared))
                    finish()
                }
                result.onFailure { e ->
                    app.diagnostics.log("launch", "prepare failed: $e")
                    // Файлы варианта не должны остаться спрятанными, если запуск не состоялся.
                    runCatching { VariantLayout(app.paths.gameRoot).restore(build) }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.launch_failed_title)
                        .setMessage(getString(R.string.launch_failed_message, e.message ?: e.toString()))
                        .setPositiveButton(R.string.launch_retry) { _, _ -> startGame() }
                        .setNeutralButton(R.string.settings_title) { _, _ ->
                            startActivity(Intent(this, SettingsActivity::class.java))
                            finish()
                        }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_REIMPORT = "reimport"
    }
}
