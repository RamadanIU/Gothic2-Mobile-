package gothic2.mobile.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
import gothic2.mobile.app.ui.gothic.Gothic
import gothic2.mobile.app.ui.gothic.GothicPanels
import gothic2.mobile.app.ui.gothic.GothicScreens
import gothic2.mobile.app.ui.gothic.OverlayHost
import gothic2.mobile.core.config.UserSettings
import gothic2.mobile.core.gamedata.BuildFingerprint
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.saves.SaveManager

/** Только понятные игровые параметры; технические настройки среды игроку не показываются. */
class SettingsActivity : AppCompatActivity() {
    lateinit var panels: OverlayHost
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GothicScreens.immersive(this)
        val root = GothicScreens.backdrop(this)
        val body = GothicScreens.page(this, root, getString(R.string.settings_title), framed = true) { onBackPressedDispatcher.onBackPressed() }
        body.id = R.id.settings_container
        setContentView(root)
        panels = OverlayHost(root)
        onBackPressedDispatcher.addCallback(this, panels.backCallback)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) GothicScreens.immersive(this)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var before: UserSettings? = null

        private val panels: OverlayHost get() = (requireActivity() as SettingsActivity).panels

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
            Gothic.toast(requireContext(), R.string.build_report_wait, long = true)
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
            setupGraphicsPreference()
            click("reset_layout") {
                app.settings.layoutOverrides = emptyMap()
                Gothic.toast(requireContext(), R.string.layout_reset_done)
            }
            click("send_diagnostics") { DiagnosticsShare.share(requireActivity()) }
            click("export_diagnostics") { exportLog.launch("gothic2-diagnostics.txt") }
            click("reimport_game") {
                startActivity(Intent(requireContext(), LauncherActivity::class.java).putExtra(LauncherActivity.EXTRA_REIMPORT, true))
            }
            click("licenses") { startActivity(Intent(requireContext(), LicensesActivity::class.java)) }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.apply {
                clipToPadding = false
                setPadding(0, 0, 0, Gothic.dp(context, 16f))
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(Gothic.dp(context, 24f))
            }
        }

        /** Списки выбора — в панели в стиле игры, а не в системном диалоге. */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference !is ListPreference) return super.onDisplayPreferenceDialog(preference)
            val values = preference.entryValues ?: return
            // Режим графики, пока игрок его не выбирал, — действующий по видеокарте.
            val current = preference.value ?: requireContext().app.settings.graphicsMode.id.takeIf { preference.key == "graphics_mode" }
            GothicPanels.choose(
                panels,
                preference.title,
                preference.entries.toList(),
                preference.findIndexOfValue(current),
            ) { i ->
                val value = values[i].toString()
                if (preference.callChangeListener(value)) preference.value = value
            }
        }

        override fun onPause() {
            super.onPause()
            val now = requireContext().app.settings.userSettings()
            if (before != null && UserSettings.requiresRestart(before!!, now)) {
                Gothic.toast(requireContext(), R.string.restart_required, long = true)
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
            pref.entries = variants.map { VariantText.title(requireContext(), it) }.toTypedArray()
            pref.entryValues = variants.map { it.id }.toTypedArray()
            pref.value = app.selectedVariant(build)?.id
        }

        /** Пока игрок не выбрал режим сам, показывается режим по видеокарте — без записи в настройки. */
        private fun setupGraphicsPreference() {
            val pref = findPreference<ListPreference>("graphics_mode") ?: return
            pref.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
                p.entry ?: requireContext().app.settings.graphicsMode.title
            }
        }

        private fun showBackups() {
            val app = requireContext().app
            val saves = currentSaves() ?: return
            val all = saves.slots().union(app.paths.backups.toFile().list().orEmpty().toList())
                .flatMap { saves.listBackups(it) }
                .sortedByDescending { it.id }
            if (all.isEmpty()) {
                Gothic.toast(requireContext(), R.string.no_backups)
                return
            }
            GothicPanels.choose(panels, getString(R.string.restore_title), all.map { "${it.slot} — ${it.id} (${it.reason})" }) { i ->
                runIo({ saves.restore(all[i]); getString(R.string.restore_done) })
            }
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }

        private fun runIo(work: () -> String) {
            val ctx = requireContext().applicationContext
            ctx.app.background.execute {
                val msg = runCatching(work).getOrElse { ctx.getString(R.string.saves_error, it.message) }
                activity?.runOnUiThread { Gothic.toast(ctx, msg, long = true) }
            }
        }
    }
}
