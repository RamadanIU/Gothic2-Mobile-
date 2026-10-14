package gothic2.mobile.app

import android.app.Application
import com.winlator.xenvironment.RootFS
import gothic2.mobile.app.storage.AppPaths
import gothic2.mobile.app.storage.Diagnostics
import gothic2.mobile.app.storage.SettingsStore
import gothic2.mobile.core.gamedata.BuildVerifier
import gothic2.mobile.core.gamedata.GameImporter
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout
import gothic2.mobile.core.runtime.RuntimeComponents
import gothic2.mobile.core.saves.SaveManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Точка сборки сервисов приложения. Без DI-фреймворка: зависимостей немного. */
class GothicApplication : Application() {
    lateinit var paths: AppPaths
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var diagnostics: Diagnostics
        private set

    /** Фоновые операции, не привязанные к жизни экрана: импорт, резервирование, запуск. */
    val background: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "background") }

    val builds: List<SupportedBuild> by lazy {
        assets.open("supported_builds.ini").bufferedReader().use { SupportedBuild.parseAll(it.readText()) }
    }

    val components: RuntimeComponents by lazy {
        assets.open("runtime_components.ini").bufferedReader().use { RuntimeComponents.parse(it.readText()) }
    }

    val verifier: BuildVerifier by lazy { BuildVerifier(builds) }

    /** Импортированная сборка или null, если игра ещё не импортирована. */
    fun importedBuild(): SupportedBuild? = settings.importedBuildId?.let { id -> builds.firstOrNull { it.id == id } }

    /** Выбранный игроком вариант или вариант сборки по умолчанию. */
    fun selectedVariant(build: SupportedBuild): GameVariant? = build.variant(settings.variantId) ?: build.defaultVariant()

    /** Сохранения варианта; резервные копии разных вариантов не смешиваются. */
    fun saveManager(build: SupportedBuild, variant: GameVariant): SaveManager =
        SaveManager(paths.gameRoot.resolve(build.savesDirOf(variant)), paths.backups.resolve(variant.id))

    override fun onCreate() {
        super.onCreate()
        paths = AppPaths(this)
        settings = SettingsStore(this)
        diagnostics = Diagnostics(paths.logs)
        diagnostics.log("app", "start ${BuildConfig.VERSION_NAME}")
        // Среда Winlator: путь rootfs для распаковки должен быть известен до любых установок.
        RootFS.find(this)
        // Если процесс погиб посреди замены установки — вернуть игру и сохранения на место.
        importedBuild()?.let { build ->
            runCatching { GameImporter(paths.gameRoot).recoverInterrupted(build) }
                .onFailure { diagnostics.log("import", "recover failed: $it") }
            // Игра прошлого процесса не вернула файлы варианта (например, классики) — вернуть.
            runCatching { VariantLayout(paths.gameRoot).restore(build) }
                .onFailure { diagnostics.log("launch", "variant restore failed: $it") }
        }
    }
}

val android.content.Context.app: GothicApplication get() = applicationContext as GothicApplication
