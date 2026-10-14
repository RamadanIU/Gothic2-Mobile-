package gothic2.mobile.app.ui

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.winlator.XServerDisplayActivity
import com.winlator.core.ProcessHelper
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.bridge.BridgeServer
import gothic2.mobile.app.input.GamepadMapper
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.runtime.WinlatorBackend
import gothic2.mobile.app.runtime.XServerInputSink
import gothic2.mobile.core.bridge.BridgeProtocol
import gothic2.mobile.core.bridge.BridgeSession
import gothic2.mobile.core.bridge.ModeController
import gothic2.mobile.core.config.UserSettings
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
import gothic2.mobile.core.runtime.SessionController
import gothic2.mobile.core.saves.SaveBackupPolicy

/**
 * Экран игры. Wine, X-сервер, звук и вывод изображения — от Winlator (XServerDisplayActivity),
 * поверх — наше сенсорное управление Gothic. Ни рабочего стола, ни окон Winlator: игра
 * запускается сразу из контейнера, подготовленного [GameLauncher].
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

    /** Раз в минуту: отметка «жив» и сводка задержки ввода в журнал диагностики. */
    private val periodic = object : Runnable {
        override fun run() {
            app.background.execute { journal.heartbeat() }
            timedSink.keys.summary()?.let { app.diagnostics.log("latency", "keys p50=${it.p50} p95=${it.p95} max=${it.max} n=${it.count}") }
            timedSink.mouse.summary()?.let { app.diagnostics.log("latency", "mouse p50=${it.p50} p95=${it.p95} max=${it.max} n=${it.count}") }
            main.postDelayed(this, PERIODIC_MS)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { s ->
        app.diagnostics.log("thermal", "status $s")
        // Разрешение на ходу не меняем — только предлагаем снизить нагрузку (раздел 19 плана).
        if (s >= PowerManager.THERMAL_STATUS_SEVERE && !thermalWarned) {
            thermalWarned = true
            Toast.makeText(this, R.string.thermal_warning, Toast.LENGTH_LONG).show()
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
        val args = intent.getStringArrayExtra(EXTRA_ARGS).orEmpty()
        if (args.isNotEmpty()) env.put("EXTRA_EXEC_ARGS", args.joinToString(" "))
        startBridge()?.let { (socket, token) ->
            env.put(BridgeProtocol.SOCKET_ENV, socket)
            env.put(BridgeProtocol.TOKEN_ENV, token)
        }
    }

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
        // сенсорная панель-мышь Winlator игре не мешает.
        touchOverlay = TouchOverlayView(this, router).apply { opacity = settings.overlayOpacity }
        editorBar = buildEditorBar()
        findViewById<FrameLayout>(com.winlator.R.id.FLXServerDisplay).apply {
            addView(touchOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(editorBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }

        getSystemService(PowerManager::class.java).addThermalStatusListener(thermalListener)
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, main)
        updateGamepadState()
        app.background.execute { journal.begin() }
        startedAt = SystemClock.uptimeMillis()
        app.diagnostics.log("game", "started variant=${variant.id}")
        main.postDelayed(periodic, PERIODIC_MS)
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
        val anchor = View(this)
        (touchOverlay.parent as FrameLayout).addView(anchor, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.END))
        val menu = PopupMenu(this, anchor)
        val items = linkedMapOf<String, () -> Unit>(
            "Карта" to { arbiter.tap(GameKey.MAP) },
            "Персонаж" to { arbiter.tap(GameKey.CHARACTER) },
            "Журнал" to { arbiter.tap(GameKey.LOG) },
            "Подкрасться" to { arbiter.tap(GameKey.SNEAK) },
            "Вид от первого лица" to { arbiter.tap(GameKey.FIRST_PERSON) },
            "Быстрое сохранение" to { arbiter.tap(GameKey.QUICKSAVE) },
            "Быстрая загрузка…" to { confirm(R.string.quickload_confirm) { arbiter.tap(GameKey.QUICKLOAD) } },
            "Настроить кнопки" to { startEditor() },
            "Настройки" to { startActivity(Intent(this, SettingsActivity::class.java)) },
            "Выйти из игры…" to { confirm(R.string.quit_confirm) { backend.stop() } },
        )
        items.keys.forEachIndexed { i, title -> menu.menu.add(0, i, i, title) }
        menu.setOnMenuItemClickListener { item ->
            items.values.elementAt(item.itemId).invoke()
            scheduleTick()
            true
        }
        menu.setOnDismissListener { (anchor.parent as? FrameLayout)?.removeView(anchor) }
        arbiter.releaseAll() // открытие меню не должно оставлять зажатых клавиш
        menu.show()
    }

    // ---- редактор раскладки -------------------------------------------------------------

    private fun buildEditorBar(): LinearLayout {
        editorModeButton = Button(this).apply {
            setOnClickListener {
                val editable = MODE_TITLES.keys.toList()
                touchOverlay.editorMode = editable[(editable.indexOf(touchOverlay.editorMode) + 1) % editable.size]
                showEditorMode()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            addView(editorModeButton)
            addView(Button(this@GameActivity).apply {
                text = getString(R.string.editor_reset)
                setOnClickListener { touchOverlay.editor?.reset(); touchOverlay.invalidate() }
            })
            addView(Button(this@GameActivity).apply {
                text = getString(R.string.editor_done)
                setOnClickListener { finishEditor(save = true) }
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

    private fun confirm(message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> action(); scheduleTick() }
            .setNegativeButton(R.string.no, null)
            .show()
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
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
        /** Игра, закрывшаяся быстрее, считается неудачным запуском. */
        private const val QUICK_EXIT_SECONDS = 180
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
