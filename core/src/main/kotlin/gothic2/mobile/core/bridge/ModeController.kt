package gothic2.mobile.core.bridge

import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.InputArbiter

/**
 * Источник режима управления: игровой адаптер (уровень 2), если он активен,
 * иначе ручное переключение панелей игроком (уровень 1).
 */
class ModeController(
    private val arbiter: InputArbiter,
    private val onModeChanged: (ControlMode, adapterActive: Boolean) -> Unit = { _, _ -> },
) : BridgeSession.Listener {

    var adapterActive: Boolean = false
        private set

    /** Ручная смена панели; игнорируется, пока режим задаёт адаптер. */
    fun manual(mode: ControlMode) {
        if (adapterActive) return
        set(mode)
    }

    override fun onState(state: BridgeProtocol.GameState) {
        adapterActive = true
        arbiter.setTargetLocked(false)
        set(map(state))
        arbiter.setTargetLocked(state.hasTarget)
    }

    override fun onClosed(reason: String) {
        adapterActive = false
        set(ControlMode.WORLD)
    }

    private fun set(mode: ControlMode) {
        arbiter.setMode(mode)
        onModeChanged(mode, adapterActive)
    }

    companion object {
        fun map(state: BridgeProtocol.GameState): ControlMode = when (state.mode) {
            BridgeProtocol.GameMode.DIALOG -> ControlMode.DIALOG
            BridgeProtocol.GameMode.INVENTORY -> ControlMode.INVENTORY
            BridgeProtocol.GameMode.TRADE -> ControlMode.TRADE
            BridgeProtocol.GameMode.MENU -> ControlMode.MENU
            BridgeProtocol.GameMode.LOADING, BridgeProtocol.GameMode.CUTSCENE -> ControlMode.LOADING
            BridgeProtocol.GameMode.WORLD -> when (state.weapon) {
                BridgeProtocol.Weapon.NONE -> ControlMode.WORLD
                BridgeProtocol.Weapon.MELEE -> ControlMode.COMBAT
                BridgeProtocol.Weapon.RANGED -> ControlMode.RANGED
                BridgeProtocol.Weapon.MAGIC -> ControlMode.MAGIC
            }
        }
    }
}
