package gothic2.mobile.app.ui

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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.bridge.BridgeServer
import gothic2.mobile.app.input.GamepadMapper
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.runtime.GpuInfo
import gothic2.mobile.app.runtime.RuntimePreparer
import gothic2.mobile.app.runtime.StubRuntimeBackend
import gothic2.mobile.core.bridge.BridgeProtocol
import gothic2.mobile.core.bridge.BridgeSession
import gothic2.mobile.core.bridge.ModeController
import gothic2.mobile.core.config.DeviceProfiles
import gothic2.mobile.core.config.GraphicsPreset
import gothic2.mobile.core.config.UserSettings
import gothic2.mobile.core.config.ViewportFit
import gothic2.mobile.core.diagnostics.SessionJournal
import gothic2.mobile.core.diagnostics.TimedInputSink
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.input.CameraMapper
import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.LayoutEditor
import gothic2.mobile.core.input.OverlayLayout
import gothic2.mobile.core.input.TouchCommand
import gothic2.mobile.core.input.TouchRouter
import gothic2.mobile.core.runtime.BridgeEndpoint
import gothic2.mobile.core.runtime.RuntimeState
import gothic2.mobile.core.runtime.SessionController
import gothic2.mobile.core.saves.SaveBackupPolicy

/**
 * Экран игры: полноэкранный вывод + сенсорный оверлей. Ни рабочего стола, ни технических
 * окон: при ошибке показывается собственное сообщение с понятным действием.
 */
class GameActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var exited = false

    private lateinit var settings: UserSettings
    private lateinit var backend: StubRuntimeBackend
    private lateinit var timedSink: TimedInputSink
    private lateinit var arbiter: InputArbiter
    private lateinit var router: TouchRouter
    private lateinit var touchOverlay: TouchOverlayView
    private lateinit var surface: SurfaceView
    private lateinit var modes: ModeController
    private lateinit var session: SessionController
    private lateinit var gamepad: GamepadMapper
    private lateinit var status: TextView
    private lateinit var editorBar: LinearLayout
    private lateinit var editorModeButton: Button
    private lateinit var journal: SessionJournal
    private var bridge: BridgeServer? = null
    private lateinit var variant: GameVariant
    private var graphics: GraphicsPreset? = null
    private var audioFocus: AudioFocusRequest? = null
    private var thermalWarned = false
    private val echo = ArrayDeque<String>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = app
        // Android пересоздал экран после выгрузки процесса: точное состояние игры потеряно,
        // поэтому показываем экран восстановления, а не делаем вид, что ничего не случилось.
        if (savedInstanceState != null) return finishToLauncher()
        val build = app.importedBuild() ?: return finishToLauncher()
        val variant = app.selectedVariant(build) ?: return finishToLauncher()
        this.variant = variant
        settings = app.settings.userSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        journal = SessionJournal(app.paths.sessionMarker)
        backend = StubRuntimeBackend(
            app.diagnostics,
            RuntimePreparer(assets, app.paths.components, app.diagnostics, allowUnpinned = app.settings.devMode),
        )
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

        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                backend.attachSurface(holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = backend.detachSurface()
        })
        touchOverlay = TouchOverlayView(this, router).apply { opacity = settings.overlayOpacity }
        status = TextView(this).apply {
            setTextColor(0xFFC9A45C.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            text = getString(R.string.runtime_stub)
        }
        editorBar = buildEditorBar()
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(touchOverlay)
            addView(editorBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyViewport() }
        setContentView(root)

        backend.onInputEcho = { line ->
            main.post {
                echo.addLast(line)
                while (echo.size > 5) echo.removeFirst()
                val lat = timedSink.keys.summary()?.let { getString(R.string.latency_line, it.p50, it.p95) }.orEmpty()
                status.text = getString(R.string.runtime_stub_echo, getString(R.string.runtime_stub), echo.joinToString("\n") + "\n" + lat)
            }
        }
        backend.addListener(::onRuntimeState)

        // Системная «Назад» открывает игровое меню, а не закрывает игру.
        onBackPressedDispatcher.addCallback(this) {
            if (touchOverlay.editor != null) {
                finishEditor(save = false)
                return@addCallback
            }
            arbiter.tap(GameKey.ESCAPE)
            scheduleTick()
        }

        val endpoint = startBridge()
        getSystemService(PowerManager::class.java).addThermalStatusListener(thermalListener)
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, main)
        updateGamepadState()

        app.background.execute {
            journal.begin()
            val profile = DeviceProfiles.select(GpuInfo.rendererName())
            app.diagnostics.log("game", "profile ${profile.id} verified=${profile.verified}")
            runCatching { GameLauncher(app, backend).prepareAndLaunch(build, variant, profile, endpoint) }
                .onSuccess { launched -> main.post { graphics = launched.graphics; applyViewport() } }
                .onFailure { e -> main.post { showFatal(e.message ?: e.toString()) } }
        }
        main.postDelayed(periodic, PERIODIC_MS)
    }

    private fun currentLayout(): OverlayLayout = editorFor().layout()

    private fun editorFor(): LayoutEditor = LayoutEditor(
        OverlayLayout.gothicDefault().copy(buttonScale = settings.buttonScale, opacity = settings.overlayOpacity),
        app.settings.layoutOverrides,
        mirrored = settings.leftHanded,
    )

    // ---- мост с игровым адаптером ------------------------------------------------------

    private fun startBridge(): BridgeEndpoint? {
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
            BridgeEndpoint(server.socketPath, server.token)
        }.onFailure { app.diagnostics.log("bridge", "start failed: $it") }.getOrNull()
    }

    // ---- изображение -------------------------------------------------------------------

    /** Картинка игры не растягивается: 4:3 на широком экране выводится с полями. */
    private fun applyViewport() {
        val g = graphics ?: return
        val root = surface.parent as? View ?: return
        if (root.width == 0 || root.height == 0) return
        val fit = ViewportFit.fit(root.width, root.height, g.width, g.height)
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != fit.game.width || lp.height != fit.game.height) {
            lp.width = fit.game.width
            lp.height = fit.game.height
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
            // Внутреннее разрешение игры; масштабирование до размера экрана делает композитор.
            surface.holder.setFixedSize(g.width, g.height)
        }
    }

    // ---- состояние среды ---------------------------------------------------------------

    private fun onRuntimeState(state: RuntimeState) {
        when (state) {
            is RuntimeState.Exited -> {
                if (exited) return
                exited = true
                app.background.execute { journal.end() }
                // Запись сохранений гарантированно окончена — резервируем изменившиеся слоты.
                val build = app.importedBuild()
                if (build != null) app.background.execute {
                    runCatching { SaveBackupPolicy(app.saveManager(build, variant)).onGameExited() }
                        .onFailure { app.diagnostics.log("saves", "backup failed: $it") }
                }
                finish()
            }
            is RuntimeState.Failed -> showFatal("${state.reason}: ${state.details}")
            else -> Unit
        }
    }

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
            "Посмотреть назад" to { arbiter.tap(GameKey.LOOK_BACK) },
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::timedSink.isInitialized) return super.dispatchTouchEvent(ev)
        timedSink.beginEvent(ev.eventTime)
        try {
            return super.dispatchTouchEvent(ev)
        } finally {
            timedSink.endEvent()
            scheduleTick()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::gamepad.isInitialized) {
            timedSink.beginEvent(event.eventTime)
            val handled = try { gamepad.onKey(event) } finally { timedSink.endEvent() }
            if (handled) {
                touchOverlay.minimal = true
                scheduleTick()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (::gamepad.isInitialized) {
            timedSink.beginEvent(ev.eventTime)
            val handled = try { gamepad.onMotion(ev) } finally { timedSink.endEvent() }
            if (handled) {
                touchOverlay.minimal = true
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                if (gamepad.hasStickInput) Choreographer.getInstance().postFrameCallback(frameCallback)
                scheduleTick()
                return true
            }
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
        super.onWindowFocusChanged(hasFocus)
        if (!::session.isInitialized) return
        if (hasFocus) {
            enterImmersive()
            session.onFocusGained()
        } else {
            session.onFocusLost()
            router.onCancel()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::session.isInitialized) return
        session.onFocusLost()
        router.onCancel()
    }

    override fun onResume() {
        super.onResume()
        if (!::session.isInitialized) return
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
        if (::session.isInitialized) {
            getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
            getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener)
        }
        bridge?.stop()
        if (::backend.isInitialized && isFinishing && !exited) backend.stop()
        super.onDestroy()
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

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showFatal(message: String) {
        app.diagnostics.log("game", "fatal: $message")
        AlertDialog.Builder(this)
            .setTitle("Не удалось запустить игру")
            .setMessage("$message\n\nЖурнал диагностики можно экспортировать в настройках.")
            .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)); finish() }
            .setNegativeButton("Закрыть") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun finishToLauncher() {
        startActivity(Intent(this, LauncherActivity::class.java))
        finish()
    }

    private companion object {
        const val PERIODIC_MS = 60_000L
        val MODE_TITLES = mapOf(
            ControlMode.WORLD to "мир",
            ControlMode.COMBAT to "бой",
            ControlMode.RANGED to "лук/магия",
            ControlMode.MENU to "навигация",
        )
    }
}
