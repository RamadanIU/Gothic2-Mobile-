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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.storage.SafGameTree
import gothic2.mobile.core.gamedata.BuildVerifier
import gothic2.mobile.core.gamedata.BuildVerifier.Result
import gothic2.mobile.core.gamedata.GameImporter
import gothic2.mobile.core.gamedata.PathGameTree

/**
 * Первый запуск: импорт легальной копии игры. При каждом следующем запуске экран
 * сразу передаёт управление [GameActivity] — пользователь видит только игру.
 */
class LauncherActivity : AppCompatActivity() {
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var choose: Button
    private lateinit var start: Button
    private var pending: Pair<Uri, Result>? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) verify(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_REIMPORT, false).not() && isGameReady()) {
            startGame()
            return
        }
        buildUi()
    }

    private fun isGameReady(): Boolean {
        val build = app.importedBuild() ?: return false
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(message)
            addView(progress)
            addView(choose)
            addView(start)
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
                    startGame()
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

    private fun startGame() {
        startActivity(Intent(this, GameActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_REIMPORT = "reimport"
    }
}
