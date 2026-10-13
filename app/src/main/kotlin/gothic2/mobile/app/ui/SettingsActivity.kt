package gothic2.mobile.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.core.config.UserSettings

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

        private val exportSaves = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val build = requireContext().app.importedBuild() ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            runIo({
                requireContext().contentResolver.openOutputStream(uri)!!.use { requireContext().app.saveManager(build).exportZip(it) }
                getString(R.string.saves_exported)
            })
        }

        private val importSaves = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val build = requireContext().app.importedBuild() ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            runIo({
                val slots = requireContext().contentResolver.openInputStream(uri)!!.use { requireContext().app.saveManager(build).importZip(it) }
                getString(R.string.saves_imported, slots.size)
            })
        }

        private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            runIo({
                requireContext().contentResolver.openOutputStream(uri)!!.use { it.write(requireContext().app.diagnostics.snapshot().toByteArray()) }
                "OK"
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
            click("reset_layout") {
                app.settings.layoutOverrides = emptyMap()
                Toast.makeText(requireContext(), R.string.layout_reset_done, Toast.LENGTH_SHORT).show()
            }
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

        private fun showBackups() {
            val app = requireContext().app
            val build = app.importedBuild() ?: return
            val saves = app.saveManager(build)
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
