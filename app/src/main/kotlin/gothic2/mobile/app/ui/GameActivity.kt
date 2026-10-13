package gothic2.mobile.app.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import gothic2.mobile.app.R
import gothic2.mobile.app.app
import gothic2.mobile.app.input.GamepadMapper
import gothic2.mobile.app.runtime.GameLauncher
import gothic2.mobile.app.runtime.GpuInfo
import gothic2.mobile.app.runtime.StubRuntimeBackend
import gothic2.mobile.core.bridge.ModeController
import gothic2.mobile.core.config.DeviceProfiles
import gothic2.mobile.core.input.CameraMapper
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.OverlayLayout
import gothic2.mobile.core.input.TouchCommand
import gothic2.mobile.core.input.TouchRouter
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

    private lateinit var backend: StubRuntimeBackend
    private lateinit var arbiter: InputArbiter
    private lateinit var router: TouchRouter
    private lateinit var overlay: TouchOverlayView
    private lateinit var modes: ModeController
    private lateinit var session: SessionController
    private lateinit var gamepad: GamepadMapper
    private lateinit var status: TextView
    private var audioFocus: AudioFocusRequest? = null
    private val echo = ArrayDeque<String>()

    private val tickRunnable = Runnable { arbiter.tick(); scheduleTick() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            gamepad.onFrame()
            if (gamepad.hasStickInput) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = app
        val build = app.importedBuild() ?: return finishToLauncher()
        val settings = app.settings.userSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        backend = StubRuntimeBackend(app.diagnostics)
        arbiter = InputArbiter(
            sink = backend.inputSink,
            camera = CameraMapper(settings.cameraSensitivity, settings.invertY),
            clock = SystemClock::uptimeMillis,
        )
        var layout = OverlayLayout.gothicDefault().withOverrides(app.settings.layoutOverrides)
        if (settings.leftHanded) layout = layout.mirrored()
        layout = layout.copy(buttonScale = settings.buttonScale, opacity = settings.overlayOpacity)

        router = TouchRouter(arbiter, layout, TouchRouter.Viewport(0f, 0f, 1f, 1f, resources.displayMetrics.density), ::onUiCommand)
        modes = ModeController(arbiter) { _, adapter ->
            router.adapterActive = adapter
            router.onModeChanged()
            overlay.invalidate()
        }
        session = SessionController(arbiter, backend, ::abandonAudioFocus, ::requestAudioFocus)
        gamepad = GamepadMapper(arbiter)

        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                backend.attachSurface(holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = backend.detachSurface()
        })
        overlay = TouchOverlayView(this, router).apply { opacity = settings.overlayOpacity }
        status = TextView(this).apply {
            setTextColor(0xFFC9A45C.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            text = getString(R.string.runtime_stub)
        }
        setContentView(FrameLayout(this).apply {
            addView(surface)
            addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(this@GameActivity.overlay)
        })

        backend.onInputEcho = { line ->
            main.post {
                echo.addLast(line)
                while (echo.size > 6) echo.removeFirst()
                status.text = getString(R.string.runtime_stub_echo, getString(R.string.runtime_stub), echo.joinToString("\n"))
            }
        }
        backend.addListener(::onRuntimeState)

        // Системная «Назад» открывает игровое меню, а не закрывает игру.
        onBackPressedDispatcher.addCallback(this) {
            arbiter.tap(GameKey.ESCAPE)
            scheduleTick()
        }

        app.background.execute {
            val profile = DeviceProfiles.select(GpuInfo.rendererName())
            app.diagnostics.log("game", "profile ${profile.id} verified=${profile.verified}")
            runCatching { GameLauncher(app, backend).prepareAndLaunch(build, profile) }
                .onFailure { e -> main.post { showFatal(e.message ?: e.toString()) } }
        }
    }

    private fun onRuntimeState(state: RuntimeState) {
        when (state) {
            is RuntimeState.Exited -> {
                if (exited) return
                exited = true
                // Запись сохранений гарантированно окончена — резервируем изменившиеся слоты.
                val build = app.importedBuild()
                if (build != null) app.background.execute {
                    runCatching { SaveBackupPolicy(app.saveManager(build)).onGameExited() }
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
        (overlay.parent as FrameLayout).addView(anchor, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.END))
        val menu = PopupMenu(this, anchor)
        val items = linkedMapOf<String, () -> Unit>(
            "Карта" to { arbiter.tap(GameKey.MAP) },
            "Персонаж" to { arbiter.tap(GameKey.CHARACTER) },
            "Журнал" to { arbiter.tap(GameKey.LOG) },
            "Подкрасться" to { arbiter.tap(GameKey.SNEAK) },
            "Посмотреть назад" to { arbiter.tap(GameKey.LOOK_BACK) },
        )
        val slots = listOf(
            GameKey.SLOT_1, GameKey.SLOT_2, GameKey.SLOT_3, GameKey.SLOT_4, GameKey.SLOT_5,
            GameKey.SLOT_6, GameKey.SLOT_7, GameKey.SLOT_8, GameKey.SLOT_9, GameKey.SLOT_10,
        )
        slots.forEachIndexed { i, k -> items["Слот ${i + 1}"] = { arbiter.tap(k) } }
        items["Быстрое сохранение"] = { arbiter.tap(GameKey.QUICKSAVE) }
        items["Быстрая загрузка…"] = { confirm(R.string.quickload_confirm) { arbiter.tap(GameKey.QUICKLOAD) } }
        items["Настройки"] = { startActivity(Intent(this, SettingsActivity::class.java)) }
        items["Выйти из игры…"] = { confirm(R.string.quit_confirm) { backend.stop() } }
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
        val handled = super.dispatchTouchEvent(ev)
        scheduleTick()
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) {
            scheduleTick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepad.onMotion(ev)) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            if (gamepad.hasStickInput) Choreographer.getInstance().postFrameCallback(frameCallback)
            scheduleTick()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

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
        if (::session.isInitialized && hasWindowFocus()) session.onFocusGained()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
}
