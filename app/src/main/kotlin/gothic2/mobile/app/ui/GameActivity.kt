package gothic2.mobile.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isNotEmpty
import com.winlator.XServerDisplayActivity
import com.winlator.core.GPUHelper
import com.winlator.core.ProcessHelper
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.bridge.BridgeServer
import gothic2.mobile.app.input.GamepadMapper
import gothic2.mobile.app.runtime.CpuInfo
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.runtime.GraphicsMode
import gothic2.mobile.app.runtime.WinlatorBackend
import gothic2.mobile.app.runtime.XServerInputSink
import gothic2.mobile.app.ui.gothic.FrameDrawable
import gothic2.mobile.app.ui.gothic.Gothic
import gothic2.mobile.app.ui.gothic.Gothic.ButtonKind
import gothic2.mobile.app.ui.gothic.GothicPanels
import gothic2.mobile.app.ui.gothic.GothicScreens
import gothic2.mobile.app.ui.gothic.OverlayHost
import gothic2.mobile.app.ui.gothic.PanelButton
import gothic2.mobile.core.bridge.BridgeProtocol
import gothic2.mobile.core.bridge.BridgeSession
import gothic2.mobile.core.bridge.ModeController
import gothic2.mobile.core.config.CpuCores
import gothic2.mobile.core.config.UserSettings
import gothic2.mobile.core.diagnostics.CpuLoad
import gothic2.mobile.core.diagnostics.FrameStats
import gothic2.mobile.core.diagnostics.SessionJournal
import gothic2.mobile.core.diagnostics.TimedInputSink
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout
import gothic2.mobile.core.input.CameraMapper
import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.LayoutEditor
import gothic2.mobile.core.input.OverlayLayout
import gothic2.mobile.core.input.TouchCommand
import gothic2.mobile.core.input.TouchRouter
import gothic2.mobile.core.runtime.BinaryFix
import gothic2.mobile.core.runtime.SessionController
import gothic2.mobile.core.saves.SaveBackupPolicy
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Экран игры. Wine, X-сервер, звук и вывод изображения — от Winlator (XServerDisplayActivity),
 * поверх — наше сенсорное управление Gothic. Ни рабочего стола, ни окон Winlator: игра
 * запускается сразу из контейнера, подготовленного [GameLauncher]. Пока не появилось окно игры,
 * виден экран загрузки в стиле игры (тот же, что в лаунчере); меню и подтверждения — панели
 * в том же стиле поверх игры.
 */
class GameActivity : XServerDisplayActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var exited = false
    private var startedAt = 0L

    private lateinit var settings: UserSettings
    private lateinit var backend: WinlatorBackend
    private lateinit var timedSink: TimedInputSink
    private lateinit var arbiter: InputArbiter
    private lateinit var router: TouchRouter
    private lateinit var touchOverlay: TouchOverlayView
    private lateinit var modes: ModeController
    private lateinit var session: SessionController
    private lateinit var gamepad: GamepadMapper
    private lateinit var editorBar: LinearLayout
    private lateinit var editorModeButton: Button
    private lateinit var panels: OverlayHost

    /** Экран загрузки поверх вывода игры; null — уже убран. */
    private var cover: View? = null

    /** Окно игры показало первый кадр (из потока X-сервера). */
    @Volatile private var gameVisible = false
    private val coverTimeout = Runnable { hideCover("timeout") }
    private lateinit var journal: SessionJournal
    private var bridge: BridgeServer? = null
    private lateinit var variant: GameVariant
    private var audioFocus: AudioFocusRequest? = null
    private var thermalWarned = false

    private val tickRunnable = Runnable { arbiter.tick(); scheduleTick() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            gamepad.onFrame()
            if (gamepad.hasStickInput) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Плавность картинки: кадры окна игры приходят из потока X-сервера. */
    private val frames = FrameStats()
    private val frameListener = object : WindowManager.OnWindowModificationListener {
        override fun onUpdateWindowContent(window: Window) {
            frames.onFrame(window.id, SystemClock.uptimeMillis())
            // Первый кадр окна программы (не рабочего стола Wine) — экран загрузки больше не нужен.
            // Так же Winlator решает, когда убрать свой: окно отображается и у него есть класс.
            if (!gameVisible && window.isRenderable && !window.isDesktopWindow && window.className.isNotEmpty()) {
                gameVisible = true
                val cls = window.className
                main.post { hideCover("window $cls") }
            }
        }
    }

    /** Загрузка процессора по потокам игры и среды: снимки в своём потоке, пока экран игры на виду. */
    private val cpuLoad = CpuLoad(CpuInfo.ticksPerSecond)
    private var cpuSampler: ScheduledExecutorService? = null
    private val cpuClusters by lazy { CpuCores.clusters(CpuInfo.maxFreqKHz()) }

    /** Раз в минуту: отметка «жив» и сводка в журнал диагностики. */
    private val periodic = object : Runnable {
        override fun run() {
            app.background.execute { journal.heartbeat() }
            logStats()
            main.postDelayed(this, PERIODIC_MS)
        }
    }

    /**
     * Сводка с прошлой записи: задержка ввода, плавность картинки, загрузка процессора, память и
     * запас до перегрева. Пишется раз в минуту, а также при уходе с экрана игры и выходе из неё —
     * иначе короткая игра перед отправкой журнала в него не попадает.
     */
    private fun logStats() {
        val diagnostics = app.diagnostics
        timedSink.keys.summary()?.let { diagnostics.log("latency", "keys p50=${it.p50} p95=${it.p95} max=${it.max} n=${it.count}") }
        timedSink.mouse.summary()?.let { diagnostics.log("latency", "mouse p50=${it.p50} p95=${it.p95} max=${it.max} n=${it.count}") }
        timedSink.keys.reset()
        timedSink.mouse.reset()
        frames.summaryAndReset()?.let {
            diagnostics.log(
                "frames",
                "fps=${"%.1f".format(Locale.ROOT, it.fps)} ms p50=${it.p50} p95=${it.p95} p99=${it.p99} max=${it.max} " +
                    "hitches=${it.hitches} pauses=${it.pauses} n=${it.frames}",
            )
        }
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        // Запас до перегрева: 1.0 — система начинает снижать частоты (Android 11+).
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(PowerManager::class.java).getThermalHeadroom(0).takeUnless { it.isNaN() }
        } else {
            null
        }
        val tail = " mem=${memory.availMem / 1_048_576}MB" + (if (memory.lowMemory) " low" else "") +
            (headroom?.let { " thermal=${"%.2f".format(Locale.ROOT, it)}" } ?: "")
        diagnostics.log("cpu", (cpuLoad.summaryAndReset()?.format(cpuClusters) ?: "n/a") + ";" + tail)
    }

    private fun startCpuSampler() {
        if (cpuSampler != null) return
        cpuLoad.restart()
        cpuSampler = Executors.newSingleThreadScheduledExecutor { Thread(it, "cpu-sampler").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(
                { runCatching { cpuLoad.onSample(SystemClock.uptimeMillis(), CpuInfo.sampleThreads(), CpuInfo.curFreqKHz()) } },
                0,
                CPU_SAMPLE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun stopCpuSampler() {
        cpuSampler?.shutdownNow()
        cpuSampler = null
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { s ->
        app.diagnostics.log("thermal", "status $s")
        // Разрешение на ходу не меняем — только предлагаем снизить нагрузку (раздел 19 плана).
        if (s >= PowerManager.THERMAL_STATUS_SEVERE && !thermalWarned) {
            thermalWarned = true
            Gothic.toast(this, R.string.thermal_warning, long = true)
        }
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateGamepadState()
        override fun onInputDeviceRemoved(deviceId: Int) = updateGamepadState()
        override fun onInputDeviceChanged(deviceId: Int) = updateGamepadState()
    }

    /**
     * Android пересоздал экран после выгрузки процесса или экран открыт не из лаунчера:
     * точное состояние игры потеряно, поэтому показываем экран восстановления, а не
     * перезапускаем игру молча.
     */
    override fun onBeforeStart(savedInstanceState: Bundle?): Boolean {
        val build = app.importedBuild()
        val variant = build?.let { app.selectedVariant(it) }
        if (savedInstanceState != null || variant == null || !intent.hasExtra(EXTRA_CONTAINER_ID)) {
            finishToLauncher()
            return false
        }
        this.variant = variant
        started = true
        WinlatorBackend.sessionActive = true
        return true
    }

    /** До запуска среды: журнал Wine, аргументы EXE и адрес моста с игровым адаптером. */
    override fun onSetupLogging() {
        val diagnostics = app.diagnostics
        diagnostics.beginWineLog()
        ProcessHelper.addDebugCallback { line -> diagnostics.wine(line) }
        val env = overrideEnvVars
        // Ошибки Wine нужны для разбора проблем, отладочный шум — нет.
        env.put("WINEDEBUG", "err+all,fixme-all")
        // ESync (события Windows на eventfd) выключен: с ним Union в GOG-сборке не создаёт свой
        // объект-событие («Can not create Event object»). Gothic лёгкая, синхронизация через
        // wineserver на скорости почти не сказывается.
        env.put("WINEESYNC", "0")
        val mode = app.settings.graphicsMode
        if (mode.isD7VK) {
            // Профиль D7VK для Gothic II привязан к Gothic2.exe; классика — Gothic2c.exe, поэтому
            // его настройку forceLegacyPresent (2D-вывод на основную поверхность — в теневую) задаём
            // сами. Strict: с Auto D7VK после Lock/Unlock, GetDC/ReleaseDC и Blt по основной
            // поверхности вне сцены сразу показывал её — а там не картинка игры, а остатки 2D:
            // белые кадры при повороте камеры. Так же сделано в профиле D7VK для Arx Fatalis.
            // Журнал D7VK пишет в вывод Wine (без файлов у игры); на уровне info — ~5000 строк о
            // перебираемых D3DX7 устройствах, поэтому только предупреждения и ошибки.
            env.put("DXVK_CONFIG", "ddraw.forceLegacyPresent = True; ddraw.legacyPresentGuard = Strict")
            env.put("D7VK_LOG_LEVEL", "warn")
        }
        if (mode.usesTurnip) {
            // Ставим сами: Winlator 11.2 теряет sysmem для Adreno 7xx (см. GraphicsMode.turnipDebug).
            val tuDebug = GraphicsMode.turnipDebug(GPUHelper.getAdrenoModelId(this).toInt())
            env.put("TU_DEBUG", tuDebug)
            diagnostics.log("launch", "TU_DEBUG=$tuDebug")
        }
        val args = intent.getStringArrayExtra(EXTRA_ARGS).orEmpty()
        if (args.isNotEmpty()) env.put("EXTRA_EXEC_ARGS", args.joinToString(" "))
        startBridge()?.let { (socket, token) ->
            env.put(BridgeProtocol.SOCKET_ENV, socket)
            env.put(BridgeProtocol.TOKEN_ENV, token)
        }
    }

    /** Файлы контейнера распакованы, Wine ещё не запущен (фоновый поток Winlator). */
    override fun onWineSystemFilesReady() {
        val ddraw = File(container.rootDir, ".wine/drive_c/windows/syswow64/ddraw.dll").toPath()
        val result = runCatching { BinaryFix.D7VK_1_11_HEL_CAPS.apply(ddraw) }
            .onFailure { app.diagnostics.log("launch", "d7vk fix failed: $it") }
            .getOrNull()
        if (result == BinaryFix.Result.FIXED) app.diagnostics.log("launch", "d7vk: fixed GetCaps (HEL)")
    }

    /** Winlator ставит свою тему (AppUtils.setActivityTheme) — вместо неё наша, её наследница. */
    override fun setTheme(resid: Int) = super.setTheme(R.style.Theme_Gothic2_Game)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!started) return
        val app = app
        settings = app.settings.userSettings()
        journal = SessionJournal(app.paths.sessionMarker)
        backend = WinlatorBackend(XServerInputSink(xServer)) { exit() }
        timedSink = TimedInputSink(backend.inputSink, SystemClock::uptimeMillis)
        arbiter = InputArbiter(
            sink = timedSink,
            camera = CameraMapper(settings.cameraSensitivity, settings.invertY),
            clock = SystemClock::uptimeMillis,
        )
        router = TouchRouter(arbiter, currentLayout(), TouchRouter.Viewport(0f, 0f, 1f, 1f, resources.displayMetrics.density), ::onUiCommand)
        router.onButtonPressed = {
            if (settings.haptics) touchOverlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        modes = ModeController(arbiter) { _, adapter ->
            router.adapterActive = adapter
            router.onModeChanged()
            touchOverlay.invalidate()
        }
        session = SessionController(arbiter, backend, ::abandonAudioFocus, ::requestAudioFocus)
        gamepad = GamepadMapper(arbiter)

        // Поверх изображения Winlator — наш оверлей: он забирает все касания, так что
        // сенсорная панель-мышь Winlator игре не мешает. До первого кадра игры его закрывает
        // экран загрузки (он тоже забирает касания).
        touchOverlay = TouchOverlayView(this, router).apply {
            opacity = settings.overlayOpacity
            visibility = View.INVISIBLE
        }
        editorBar = buildEditorBar()
        val display = findViewById<FrameLayout>(com.winlator.R.id.FLXServerDisplay)
        display.addView(touchOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        display.addView(editorBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Gothic.dp(this@GameActivity, 8f)
        })
        cover = buildCover().also {
            display.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        panels = OverlayHost(display)
        main.postDelayed(coverTimeout, COVER_TIMEOUT_MS)

        xServer.windowManager.addOnWindowModificationListener(frameListener)
        getSystemService(PowerManager::class.java).addThermalStatusListener(thermalListener)
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, main)
        updateGamepadState()
        app.background.execute { journal.begin() }
        startedAt = SystemClock.uptimeMillis()
        app.diagnostics.log("game", "started variant=${variant.id}")
        main.postDelayed(periodic, PERIODIC_MS)
    }

    /** Экран загрузки — как в лаунчере, только искры реже: процессор нужен запуску игры. */
    private fun buildCover(): View {
        val loading = GothicScreens.loading(this, VariantText.title(this, variant), getString(R.string.launch_loading_world))
        return GothicScreens.backdrop(this, emberFrameMs = 33).apply {
            addView(loading.root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            isClickable = true
        }
    }

    private fun hideCover(reason: String) {
        val c = cover ?: return
        cover = null
        main.removeCallbacks(coverTimeout)
        app.diagnostics.log("game", "loading screen hidden ($reason) after ${(SystemClock.uptimeMillis() - startedAt) / 1000}s")
        touchOverlay.visibility = View.VISIBLE
        c.animate().alpha(0f).setDuration(450).withEndAction { (c.parent as? ViewGroup)?.removeView(c) }.start()
    }

    private fun currentLayout(): OverlayLayout = editorFor().layout()

    private fun editorFor(): LayoutEditor = LayoutEditor(
        OverlayLayout.gothicDefault().copy(buttonScale = settings.buttonScale, opacity = settings.overlayOpacity),
        app.settings.layoutOverrides,
        mirrored = settings.leftHanded,
    )

    // ---- мост с игровым адаптером ------------------------------------------------------

    /** Сокет для адаптера внутри процесса игры; null — мост не запущен. */
    private fun startBridge(): Pair<String, String>? {
        val sha = app.settings.importedExeSha256 ?: return null
        val server = BridgeServer(app.paths.bridgeSocket.toFile(), sha, BridgeSession.newToken(), app.diagnostics) { s ->
            s.addListener(modes)
            s.addListener(object : BridgeSession.Listener {
                override fun onState(state: BridgeProtocol.GameState) {
                    router.slotKinds = state.quickSlots
                }

                override fun onSaveDone(slot: String) {
                    val build = app.importedBuild() ?: return
                    app.background.execute {
                        runCatching { SaveBackupPolicy(app.saveManager(build, variant)).onSaveCompleted(slot) }
                            .onFailure { app.diagnostics.log("saves", "backup after save failed: $it") }
                    }
                }

                override fun onClosed(reason: String) {
                    router.slotKinds = emptyMap()
                }
            })
        }
        return runCatching {
            server.start()
            bridge = server
            server.socketPath to server.token
        }.onFailure { app.diagnostics.log("bridge", "start failed: $it") }.getOrNull()
    }

    // ---- завершение игры ---------------------------------------------------------------

    /** Winlator вызывает при завершении процесса игры (из фонового потока) и при выходе из меню. */
    override fun exit() {
        stopEnvironment()
        runOnUiThread { onGameExited() }
    }

    private fun onGameExited() {
        if (exited) return
        exited = true
        val seconds = (SystemClock.uptimeMillis() - startedAt) / 1000
        logStats()
        stopCpuSampler()
        app.diagnostics.log("game", "exited after ${seconds}s")
        // Игра закрылась почти сразу — вероятно, ошибка: лаунчер покажет кнопку «Отправить журнал».
        app.settings.lastSessionFailed = seconds < QUICK_EXIT_SECONDS
        afterGame()
        finish()
    }

    /**
     * Игра больше не работает: завершить оставшиеся процессы Wine, вернуть файлы варианта и
     * сохранить копии изменившихся слотов (в фоне, по порядку).
     */
    private fun afterGame() {
        val app = app
        val build = app.importedBuild()
        app.background.execute {
            val killed = WinlatorBackend.killWineProcesses()
            if (killed > 0) app.diagnostics.log("game", "stopped $killed Wine processes")
            journal.end()
            if (build != null) {
                restoreVariantFiles(build)
                runCatching { SaveBackupPolicy(app.saveManager(build, variant)).onGameExited() }
                    .onFailure { app.diagnostics.log("saves", "backup failed: $it") }
            }
        }
    }

    private fun restoreVariantFiles(build: SupportedBuild) {
        runCatching { VariantLayout(app.paths.gameRoot).restore(build) }
            .onFailure { app.diagnostics.log("launch", "variant restore failed: $it") }
    }

    // ---- быстрое меню ------------------------------------------------------------------

    private fun onUiCommand(cmd: TouchCommand) {
        when (cmd) {
            is TouchCommand.SwitchMode -> modes.manual(cmd.mode)
            TouchCommand.QuickMenu -> showQuickMenu()
            else -> Unit
        }
        scheduleTick()
    }

    private fun showQuickMenu() {
        arbiter.releaseAll() // открытие меню не должно оставлять зажатых клавиш
        fun tap(title: Int, key: GameKey) = PanelButton(getString(title)) { arbiter.tap(key); scheduleTick() }
        GothicPanels.grid(
            panels,
            getString(R.string.menu_title),
            listOf(
                tap(R.string.menu_map, GameKey.MAP),
                tap(R.string.menu_character, GameKey.CHARACTER),
                tap(R.string.menu_log, GameKey.LOG),
                tap(R.string.menu_sneak, GameKey.SNEAK),
                tap(R.string.menu_first_person, GameKey.FIRST_PERSON),
                tap(R.string.menu_quicksave, GameKey.QUICKSAVE),
                PanelButton(getString(R.string.menu_quickload)) {
                    confirm(R.string.quickload_title, R.string.quickload_confirm, R.string.quickload_action, ButtonKind.PRIMARY) {
                        arbiter.tap(GameKey.QUICKLOAD)
                    }
                },
                PanelButton(getString(R.string.menu_layout)) { startEditor() },
                PanelButton(getString(R.string.settings_title)) { startActivity(Intent(this, SettingsActivity::class.java)) },
            ),
            columns = 3,
            footer = listOf(
                PanelButton(getString(R.string.menu_quit), ButtonKind.DANGER) {
                    confirm(R.string.quit_title, R.string.quit_confirm, R.string.quit_action, ButtonKind.DANGER) { backend.stop() }
                },
                PanelButton(getString(R.string.menu_resume), ButtonKind.PRIMARY),
            ),
        )
    }

    // ---- редактор раскладки -------------------------------------------------------------

    private fun buildEditorBar(): LinearLayout {
        editorModeButton = Gothic.button(this, "", textSizeSp = 16f, compact = true) {
            val editable = MODE_TITLES.keys.toList()
            touchOverlay.editorMode = editable[(editable.indexOf(touchOverlay.editorMode) + 1) % editable.size]
            showEditorMode()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = FrameDrawable(resources.displayMetrics.density, compact = true)
            visibility = View.GONE
            val gap = Gothic.dp(this@GameActivity, 8f)
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (isNotEmpty()) leftMargin = gap
            })
            add(editorModeButton)
            add(Gothic.button(this@GameActivity, getString(R.string.editor_reset), textSizeSp = 16f, compact = true) {
                touchOverlay.editor?.reset()
                touchOverlay.invalidate()
            })
            add(Gothic.button(this@GameActivity, getString(R.string.editor_done), ButtonKind.PRIMARY, textSizeSp = 16f, compact = true) {
                finishEditor(save = true)
            })
        }
    }

    private fun showEditorMode() {
        editorModeButton.text = getString(R.string.editor_mode, MODE_TITLES.getValue(touchOverlay.editorMode))
    }

    private fun startEditor() {
        router.onCancel()
        touchOverlay.editorMode = if (arbiter.mode in MODE_TITLES) arbiter.mode else ControlMode.WORLD
        touchOverlay.editor = editorFor()
        showEditorMode()
        editorBar.visibility = View.VISIBLE
    }

    private fun finishEditor(save: Boolean) {
        val ed = touchOverlay.editor ?: return
        if (save) {
            app.settings.layoutOverrides = ed.overrides()
            router.layout = ed.layout()
        }
        touchOverlay.editor = null
        editorBar.visibility = View.GONE
    }

    // ---- ввод --------------------------------------------------------------------------

    /** Подтверждение панелью поверх игры; слева — отказ, справа — действие. */
    private fun confirm(title: Int, message: Int, action: Int, kind: ButtonKind, onConfirm: () -> Unit) {
        GothicPanels.message(
            panels,
            getString(title),
            getString(message),
            listOf(
                PanelButton(getString(if (kind == ButtonKind.DANGER) R.string.stay_action else R.string.no)),
                PanelButton(getString(action), kind) { onConfirm(); scheduleTick() },
            ),
        )
    }

    private fun scheduleTick() {
        main.removeCallbacks(tickRunnable)
        val deadline = arbiter.nextDeadline() ?: return
        main.postAtTime(tickRunnable, deadline)
    }

    /** Системная «Назад» — Esc в игре (меню Gothic), а не закрытие игры и не меню Winlator. */
    @Deprecated("Совместимость с XServerDisplayActivity, переопределяющей onBackPressed")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        if (!started) return
        if (GothicPanels.handleBack(panels)) return
        if (touchOverlay.editor != null) {
            finishEditor(save = false)
            return
        }
        arbiter.tap(GameKey.ESCAPE)
        scheduleTick()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!started) return true
        timedSink.beginEvent(ev.eventTime)
        try {
            return super.dispatchTouchEvent(ev)
        } finally {
            timedSink.endEvent()
            scheduleTick()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!started) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        timedSink.beginEvent(event.eventTime)
        val handled = try { gamepad.onKey(event) } finally { timedSink.endEvent() }
        if (handled) {
            touchOverlay.minimal = true
            scheduleTick()
            return true
        }
        // Физическая клавиатура — напрямую в X-сервер Winlator.
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!started) return true
        timedSink.beginEvent(ev.eventTime)
        val handled = try { gamepad.onMotion(ev) } finally { timedSink.endEvent() }
        if (handled) {
            touchOverlay.minimal = true
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            if (gamepad.hasStickInput) Choreographer.getInstance().postFrameCallback(frameCallback)
            scheduleTick()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /** При отключении геймпада сенсорная панель возвращается. */
    private fun updateGamepadState() {
        val connected = InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }
        if (!connected) touchOverlay.minimal = false
    }

    // ---- жизненный цикл ----------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!started) return
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) session.onFocusGained() else {
            session.onFocusLost()
            router.onCancel()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!started) return
        session.onFocusLost()
        router.onCancel()
        // Процессы Wine на паузе остановлены — сводка за время игры, снимки до возврата не нужны.
        if (!exited) logStats()
        stopCpuSampler()
    }

    override fun onResume() {
        super.onResume()
        if (!started) return
        // Настройки могли измениться, пока экран был скрыт: управление применяется сразу.
        settings = app.settings.userSettings()
        arbiter.camera.sensitivity = settings.cameraSensitivity
        arbiter.camera.invertY = settings.invertY
        touchOverlay.opacity = settings.overlayOpacity
        if (touchOverlay.editor == null) router.layout = currentLayout()
        if (hasWindowFocus()) session.onFocusGained()
        if (!exited) startCpuSampler()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopCpuSampler()
        if (started) {
            getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
            getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener)
        }
        bridge?.stop()
        super.onDestroy() // Winlator останавливает Wine
        if (started) WinlatorBackend.sessionActive = false
        if (started && isFinishing && !exited) {
            exited = true
            app.diagnostics.log("game", "closed by system")
            app.settings.lastSessionFailed = (SystemClock.uptimeMillis() - startedAt) / 1000 < QUICK_EXIT_SECONDS
            afterGame()
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) session.onFocusLost()
            }
            .build()
        am.requestAudioFocus(req)
        audioFocus = req
    }

    private fun abandonAudioFocus() {
        audioFocus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    private fun finishToLauncher() {
        startActivity(Intent(this, LauncherActivity::class.java))
        finish()
    }

    companion object {
        /** Имена дополнительных данных XServerDisplayActivity. */
        private const val EXTRA_CONTAINER_ID = "container_id"
        private const val EXTRA_EXEC_PATH = "exec_path"
        private const val EXTRA_ARGS = "gothic2_args"
        private const val PERIODIC_MS = 60_000L
        /** Снимок потоков: 2 с — достаточно, чтобы заметить, на каких ядрах работают потоки. */
        private const val CPU_SAMPLE_MS = 2_000L
        /** Игра, закрывшаяся быстрее, считается неудачным запуском. */
        private const val QUICK_EXIT_SECONDS = 180
        /** Экран загрузки убирается и без окна игры: вдруг его не распознали, а игра уже идёт. */
        private const val COVER_TIMEOUT_MS = 90_000L
        private val MODE_TITLES = mapOf(
            ControlMode.WORLD to "мир",
            ControlMode.COMBAT to "бой",
            ControlMode.RANGED to "лук/магия",
            ControlMode.MENU to "навигация",
        )

        fun intent(context: Context, prepared: GameLauncher.Prepared): Intent =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_CONTAINER_ID, prepared.containerId)
                .putExtra(EXTRA_EXEC_PATH, prepared.exePath)
                .putExtra(EXTRA_ARGS, prepared.args.toTypedArray())
    }
}
