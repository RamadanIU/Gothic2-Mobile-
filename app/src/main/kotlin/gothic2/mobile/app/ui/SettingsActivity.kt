package gothic2.mobile.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.storage.DiagnosticsShare
import gothic2.mobile.app.storage.DocumentExport
import gothic2.mobile.core.config.UserSettings
import gothic2.mobile.core.gamedata.BuildFingerprint
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.saves.SaveManager

/** Только понятные игровые параметры; технические настройки среды игроку не показываются. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var before: UserSettings? = null

        /** Сохранения выбранного варианта игры; null — игра ещё не импортирована. */
        private fun currentSaves(): SaveManager? {
            val app = requireContext().app
            val build = app.importedBuild() ?: return null
            val variant = app.selectedVariant(build) ?: return null
            return app.saveManager(build, variant)
        }

        private val exportSaves = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val saves = currentSaves() ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            val resolver = requireContext().contentResolver
            val cache = requireContext().cacheDir
            runIo({
                DocumentExport.write(resolver, uri, cache) { saves.exportZip(it) }
                getString(R.string.saves_exported)
            })
        }

        private val importSaves = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val saves = currentSaves() ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            val resolver = requireContext().contentResolver
            runIo({
                val slots = resolver.openInputStream(uri)!!.use { saves.importZip(it) }
                getString(R.string.saves_imported, slots.size)
            })
        }

        private val exportBuildReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = requireContext().app
            val resolver = requireContext().contentResolver
            val cache = requireContext().cacheDir
            val build = app.importedBuild()
            // Подсчёт хэшей нескольких гигабайт занимает минуты — предупреждаем, чтобы экран не закрыли.
            Toast.makeText(requireContext(), R.string.build_report_wait, Toast.LENGTH_LONG).show()
            runIo({
                val report = BuildFingerprint.collect(
                    PathGameTree(app.paths.gameRoot),
                    app.verifier,
                    savesDirs = build?.allSavesDirs() ?: listOf("Saves"),
                )
                DocumentExport.write(resolver, uri, cache) { it.write(BuildFingerprint.format(report, BuildConfig.VERSION_NAME).toByteArray()) }
                getString(R.string.build_report_saved)
            })
        }

        private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val resolver = requireContext().contentResolver
            val cache = requireContext().cacheDir
            val diagnostics = requireContext().app.diagnostics
            runIo({
                val text = diagnostics.snapshot().ifEmpty { "(журнал пуст)\n" }
                DocumentExport.write(resolver, uri, cache) { it.write(text.toByteArray()) }
                getString(R.string.diagnostics_saved)
            })
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val app = requireContext().app
            before = app.settings.userSettings()
            findPreference<SwitchPreferenceCompat>("dev_mode")?.apply {
                isVisible = BuildConfig.DEBUG
                isChecked = app.settings.devMode
            }
            click("export_saves") { exportSaves.launch("gothic2-saves.zip") }
            click("import_saves") { importSaves.launch(arrayOf("application/zip")) }
            click("restore_backup") { showBackups() }
            click("build_report") { exportBuildReport.launch("gothic2-build-report.txt") }
            setupVariantPreference()
            click("reset_layout") {
                app.settings.layoutOverrides = emptyMap()
                Toast.makeText(requireContext(), R.string.layout_reset_done, Toast.LENGTH_SHORT).show()
            }
            click("send_diagnostics") { DiagnosticsShare.share(requireActivity()) }
            click("export_diagnostics") { exportLog.launch("gothic2-diagnostics.txt") }
            click("reimport_game") {
                startActivity(Intent(requireContext(), LauncherActivity::class.java).putExtra(LauncherActivity.EXTRA_REIMPORT, true))
            }
        }

        override fun onPause() {
            super.onPause()
            val now = requireContext().app.settings.userSettings()
            if (before != null && UserSettings.requiresRestart(before!!, now)) {
                Toast.makeText(requireContext(), R.string.restart_required, Toast.LENGTH_LONG).show()
            }
            before = now
        }

        /** Список вариантов запуска берётся из манифеста сборки, а не задаётся в XML. */
        private fun setupVariantPreference() {
            val app = requireContext().app
            val pref = findPreference<ListPreference>("game_variant") ?: return
            val build = app.importedBuild()
            val variants = build?.definedVariants().orEmpty()
            if (build == null || variants.size < 2) {
                pref.isVisible = false
                return
            }
            pref.entries = variants.map { it.title }.toTypedArray()
            pref.entryValues = variants.map { it.id }.toTypedArray()
            pref.value = app.selectedVariant(build)?.id
        }

        private fun showBackups() {
            val app = requireContext().app
            val saves = currentSaves() ?: return
            val all = saves.slots().union(app.paths.backups.toFile().list().orEmpty().toList())
                .flatMap { saves.listBackups(it) }
                .sortedByDescending { it.id }
            if (all.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_backups, Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(requireContext())
                .setItems(all.map { "${it.slot} — ${it.id} (${it.reason})" }.toTypedArray()) { _, i ->
                    runIo({ saves.restore(all[i]); getString(R.string.restore_done) })
                }
                .show()
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }

        private fun runIo(work: () -> String) {
            val ctx = requireContext().applicationContext
            ctx.app.background.execute {
                val msg = runCatching(work).getOrElse { ctx.getString(R.string.saves_error, it.message) }
                activity?.runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }
}
