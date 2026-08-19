package com.droidlink.app

import android.view.KeyEvent
import android.view.MotionEvent

enum class LogicalControl(val displayName: String, val linuxCode: Int?) {
    A("A", 304),
    B("B", 305),
    X("X", 307),
    Y("Y", 308),
    L1("L1", 310),
    R1("R1", 311),
    L2("L2", 312),
    R2("R2", 313),
    SELECT("Select", 314),
    START("Start", 315),
    GUIDE("Guide", 316),
    L3("L3", 317),
    R3("R3", 318),
    DPAD_UP("D-pad Up", 544),
    DPAD_DOWN("D-pad Down", 545),
    DPAD_LEFT("D-pad Left", 546),
    DPAD_RIGHT("D-pad Right", 547)
}

object ControllerMapping {
    const val TABLE_VERSION = "android-gamepad-v1"

    private val keyMap = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to LogicalControl.A,
        KeyEvent.KEYCODE_BUTTON_B to LogicalControl.B,
        KeyEvent.KEYCODE_BUTTON_X to LogicalControl.X,
        KeyEvent.KEYCODE_BUTTON_Y to LogicalControl.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to LogicalControl.L1,
        KeyEvent.KEYCODE_BUTTON_R1 to LogicalControl.R1,
        KeyEvent.KEYCODE_BUTTON_L2 to LogicalControl.L2,
        KeyEvent.KEYCODE_BUTTON_R2 to LogicalControl.R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL to LogicalControl.L3,
        KeyEvent.KEYCODE_BUTTON_THUMBR to LogicalControl.R3,
        KeyEvent.KEYCODE_BUTTON_START to LogicalControl.START,
        KeyEvent.KEYCODE_BUTTON_SELECT to LogicalControl.SELECT,
        KeyEvent.KEYCODE_BACK to LogicalControl.SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE to LogicalControl.GUIDE,
        KeyEvent.KEYCODE_DPAD_UP to LogicalControl.DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to LogicalControl.DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to LogicalControl.DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to LogicalControl.DPAD_RIGHT
    )

    fun logicalForAndroidKey(androidKeyCode: Int): LogicalControl? = keyMap[androidKeyCode]
    fun linuxCodeForAndroidKey(androidKeyCode: Int): Int? = logicalForAndroidKey(androidKeyCode)?.linuxCode

    val axisMap = listOf(
        AxisMapping(MotionEvent.AXIS_X, "Left X", false),
        AxisMapping(MotionEvent.AXIS_Y, "Left Y", false),
        AxisMapping(MotionEvent.AXIS_Z, "Right X", false),
        AxisMapping(MotionEvent.AXIS_RZ, "Right Y", false),
        AxisMapping(MotionEvent.AXIS_LTRIGGER, "L2", true),
        AxisMapping(MotionEvent.AXIS_RTRIGGER, "R2", true),
        AxisMapping(MotionEvent.AXIS_HAT_X, "D-pad X", false),
        AxisMapping(MotionEvent.AXIS_HAT_Y, "D-pad Y", false)
    )

    fun tableDescription() = keyMap.entries
        .sortedBy { it.key }
        .joinToString { "${KeyEvent.keyCodeToString(it.key)}->${it.value.displayName}/EV_KEY_${it.value.linuxCode}" }
}

object ControllerButtonNormalization {
    fun canonicalKeyCode(androidKeyCode: Int): Int = when (androidKeyCode) {
        KeyEvent.KEYCODE_MENU -> KeyEvent.KEYCODE_BUTTON_START
        KeyEvent.KEYCODE_BACK -> KeyEvent.KEYCODE_BUTTON_SELECT
        else -> androidKeyCode
    }
}

data class AxisMapping(val androidAxis: Int, val logicalName: String, val trigger: Boolean)

enum class DpadSource { UNSELECTED, KEY, HAT }

data class DpadState(val x: Int = 0, val y: Int = 0) {
    init {
        require(x in -1..1 && y in -1..1) { "D-pad axes must be -1, 0, or 1" }
    }

    val label: String get() = when {
        x == 0 && y == 0 -> "NEUTRAL"
        x == 0 && y < 0 -> "UP"
        x == 0 && y > 0 -> "DOWN"
        x < 0 && y == 0 -> "LEFT"
        x > 0 && y == 0 -> "RIGHT"
        x < 0 && y < 0 -> "UP_LEFT"
        x > 0 && y < 0 -> "UP_RIGHT"
        x < 0 -> "DOWN_LEFT"
        else -> "DOWN_RIGHT"
    }
}

class DpadStateMachine {
    var state: DpadState = DpadState()
        private set

    fun update(next: DpadState): Boolean {
        if (next == state) return false
        state = next
        return true
    }

    fun reset(): Boolean = update(DpadState())
}

data class ControllerInputState(
    val pressed: Set<LogicalControl> = emptySet(),
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val dpadX: Float = 0f,
    val dpadY: Float = 0f
) {
    fun withButton(control: LogicalControl, down: Boolean) = copy(
        pressed = if (down) pressed + control else pressed - control
    )

    fun isDown(control: LogicalControl) = control in pressed
    fun dpadText() = when {
        dpadY < -0.5f -> "UP"
        dpadY > 0.5f -> "DOWN"
        dpadX < -0.5f -> "LEFT"
        dpadX > 0.5f -> "RIGHT"
        isDown(LogicalControl.DPAD_UP) -> "UP"
        isDown(LogicalControl.DPAD_DOWN) -> "DOWN"
        isDown(LogicalControl.DPAD_LEFT) -> "LEFT"
        isDown(LogicalControl.DPAD_RIGHT) -> "RIGHT"
        else -> "NEUTRAL"
    }
}
