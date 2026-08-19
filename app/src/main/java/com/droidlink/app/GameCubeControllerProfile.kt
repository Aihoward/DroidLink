package com.droidlink.app

import android.view.KeyEvent

enum class ControllerProfile(val storageValue: String, val label: String) {
    PC_WINLATOR("pc_winlator", "PC / Winlator"),
    PS2("ps2", "PS2"),
    GAMECUBE_DOLPHIN("gamecube_dolphin", "GameCube / Dolphin");

    companion object {
        fun fromStorage(value: String?): ControllerProfile =
            entries.firstOrNull { it.storageValue == value } ?: PC_WINLATOR
    }
}

enum class GameCubeButton(val displayName: String) {
    A("A"), B("B"), X("X"), Y("Y"), START("Start"), Z("Z")
}

data class GameCubeControllerState(
    val pressed: Set<GameCubeButton> = emptySet(),
    val mainX: Float = 0f,
    val mainY: Float = 0f,
    val cX: Float = 0f,
    val cY: Float = 0f,
    val analogL: Float = 0f,
    val analogR: Float = 0f,
    val digitalLKeys: Set<Int> = emptySet(),
    val digitalRKeys: Set<Int> = emptySet(),
    val dpadX: Int = 0,
    val dpadY: Int = 0
) {
    val digitalL: Boolean get() = digitalLKeys.isNotEmpty() || analogL >= GameCubeMapping.DIGITAL_TRIGGER_THRESHOLD
    val digitalR: Boolean get() = digitalRKeys.isNotEmpty() || analogR >= GameCubeMapping.DIGITAL_TRIGGER_THRESHOLD

    fun isPressed(button: GameCubeButton): Boolean = button in pressed
}

object GameCubeMapping {
    const val TABLE_VERSION = "gamecube-dolphin-v1"
    const val DIGITAL_TRIGGER_THRESHOLD = 0.90f

    fun updateKey(state: GameCubeControllerState, androidKeyCode: Int, down: Boolean): GameCubeControllerState? = when (androidKeyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> state.withButton(GameCubeButton.A, down)
        KeyEvent.KEYCODE_BUTTON_B -> state.withButton(GameCubeButton.B, down)
        KeyEvent.KEYCODE_BUTTON_X -> state.withButton(GameCubeButton.X, down)
        KeyEvent.KEYCODE_BUTTON_Y -> state.withButton(GameCubeButton.Y, down)
        KeyEvent.KEYCODE_BUTTON_START -> state.withButton(GameCubeButton.START, down)
        KeyEvent.KEYCODE_BUTTON_R1 -> state.withButton(GameCubeButton.Z, down)
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> state.copy(
            digitalLKeys = state.digitalLKeys.withKey(androidKeyCode, down)
        )
        KeyEvent.KEYCODE_BUTTON_R2 -> state.copy(
            digitalRKeys = state.digitalRKeys.withKey(androidKeyCode, down)
        )
        else -> null
    }

    fun updateAxes(
        state: GameCubeControllerState,
        mainX: Float,
        mainY: Float,
        cX: Float,
        cY: Float,
        analogL: Float,
        analogR: Float
    ) = state.copy(
        mainX = mainX.coerceIn(-1f, 1f),
        mainY = mainY.coerceIn(-1f, 1f),
        cX = cX.coerceIn(-1f, 1f),
        cY = cY.coerceIn(-1f, 1f),
        analogL = analogL.coerceIn(0f, 1f),
        analogR = analogR.coerceIn(0f, 1f)
    )

    fun updateDpad(state: GameCubeControllerState, x: Int, y: Int) =
        state.copy(dpadX = x.coerceIn(-1, 1), dpadY = y.coerceIn(-1, 1))

    private fun GameCubeControllerState.withButton(button: GameCubeButton, down: Boolean) = copy(
        pressed = if (down) pressed + button else pressed - button
    )

    private fun Set<Int>.withKey(keyCode: Int, down: Boolean) = if (down) this + keyCode else this - keyCode
}
