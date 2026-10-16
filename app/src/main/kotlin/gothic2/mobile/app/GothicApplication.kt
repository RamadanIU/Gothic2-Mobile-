package gothic2.mobile.app

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.winlator.xenvironment.RootFS
import gothic2.mobile.app.storage.AppPaths
import gothic2.mobile.app.storage.CrashRecorder
import gothic2.mobile.app.storage.Diagnostics
import gothic2.mobile.app.storage.LogLocation
import gothic2.mobile.app.storage.LogcatCapture
import gothic2.mobile.app.storage.SettingsStore
import gothic2.mobile.app.ui.LauncherActivity
import gothic2.mobile.core.gamedata.BuildVerifier
import gothic2.mobile.core.gamedata.GameImporter
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout
import gothic2.mobile.core.runtime.RuntimeComponents
import gothic2.mobile.core.saves.SaveManager
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.WeakHashMap
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

    private val main = Handler(Looper.getMainLooper())

    /** Живые экраны приложения (только из главного потока). */
    private val activities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    /**
     * В этом процессе уже шла игра. Winlator рассчитан на одну игру на процесс: после выхода он
     * перезапускает приложение целиком (`AppUtils.restartApplication`). Вторая игра в том же
     * процессе — X-сервер, звук, нативные библиотеки графики с состоянием от первой — вылетала
     * при загрузке («Играть снова» после выхода). Поэтому после игры процесс завершается, а если
     * лаунчер открыли раньше — он перезапускает процесс ([restartProcess]).
     */
    @Volatile
    var gameSessionUsed = false

    /**
     * Папка игры: выбранная игроком на накопителе (игра запускается прямо из неё) или копия во
     * внутреннем хранилище, если игру переносили в приложение.
     */
    fun gameRoot(): Path = settings.gameDir?.let { Paths.get(it) } ?: paths.gameRoot

    /** Импортированная сборка или null, если игра ещё не импортирована. */
    fun importedBuild(): SupportedBuild? = settings.importedBuildId?.let { id -> builds.firstOrNull { it.id == id } }

    /** Выбранный игроком вариант или вариант сборки по умолчанию. */
    fun selectedVariant(build: SupportedBuild): GameVariant? = build.variant(settings.variantId) ?: build.defaultVariant()

    /** Сохранения варианта; резервные копии разных вариантов не смешиваются. */
    fun saveManager(build: SupportedBuild, variant: GameVariant): SaveManager =
        SaveManager(gameRoot().resolve(build.savesDirOf(variant)), paths.backups.resolve(variant.id))

    override fun onCreate() {
        super.onCreate()
        paths = AppPaths(this)
        settings = SettingsStore(this)
        // Журнал — первым делом, до всего, что может упасть: пишется всегда, в видимую игроку папку.
        val logDir = LogLocation.choose(this, paths.logs)
        for (other in LogLocation.all(this, paths.logs)) Diagnostics.migrate(other, logDir)
        LogcatCapture.keepPrevious(logDir)
        diagnostics = Diagnostics(logDir, fallback = paths.logs)
        val logcat = LogcatCapture.start(logDir, fromNow = false)
        diagnostics.log("app", "start ${BuildConfig.VERSION_NAME} logs=$logDir")
        logcat.onFailure { diagnostics.log("app", "logcat capture failed: $it") }
        CrashRecorder.install(this, diagnostics)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activities += activity
            }
            override fun onActivityDestroyed(activity: Activity) {
                activities -= activity
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        // Среда Winlator: путь rootfs для распаковки должен быть известен до любых установок.
        RootFS.find(this)
        // Если процесс погиб посреди замены установки — вернуть игру и сохранения на место.
        importedBuild()?.let { build ->
            runCatching { GameImporter(paths.gameRoot).recoverInterrupted(build) }
                .onFailure { diagnostics.log("import", "recover failed: $it") }
            // Игра прошлого процесса не вернула файлы варианта (например, классики) — вернуть.
            runCatching { VariantLayout(gameRoot()).restore(build) }
                .onFailure { diagnostics.log("launch", "variant restore failed: $it") }
        }
    }

    /**
     * Перенести журналы в лучшую доступную папку — после того как игрок дал разрешение «Файлы».
     * Вызывать в [background]: перенос с внутренней памяти — копирование нескольких мегабайт.
     */
    fun updateLogLocation() {
        val best = LogLocation.choose(this, paths.logs)
        if (best == diagnostics.dir) return
        LogcatCapture.stop()
        diagnostics.relocate(best)
        LogcatCapture.start(diagnostics.dir, fromNow = true)
        diagnostics.log("app", "logs moved to ${diagnostics.dir}")
    }

    /**
     * Конец игры: уборка после неё уже сделана (вызывать последним шагом в [background]).
     * Если игрок вышел на рабочий стол — процесс завершается, следующий запуск начнётся с чистого
     * листа. Если за это время открыли лаунчер, он сам перезапустит процесс.
     */
    fun endGameProcess() {
        diagnostics.flush()
        main.post {
            if (activities.none { !it.isFinishing }) {
                diagnostics.logNow("app", "process ends after game")
                diagnostics.flush()
                LogcatCapture.stop()
                Runtime.getRuntime().exit(0)
            }
        }
    }

    /**
     * Перезапустить процесс и открыть лаунчер — после уборки прошлой игры (очередь [background]).
     * Вызывать с экрана на переднем плане: из фона Android не даёт открыть экран.
     */
    fun restartProcess() {
        background.execute {
            diagnostics.logNow("app", "restart process after game")
            diagnostics.flush()
            main.post {
                LogcatCapture.stop()
                startActivity(Intent.makeRestartActivityTask(ComponentName(this, LauncherActivity::class.java)))
                Runtime.getRuntime().exit(0)
            }
        }
    }
}

val android.content.Context.app: GothicApplication get() = applicationContext as GothicApplication
