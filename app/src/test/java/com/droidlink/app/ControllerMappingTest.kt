package com.droidlink.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerMappingTest {
    @Test fun androidFaceButtonsUseCanonicalEvdevCodes() {
        assertEquals(304, ControllerMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(305, ControllerMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(307, ControllerMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(308, ControllerMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_Y))
    }

    @Test fun criticalButtonsMapToExpectedLogicalControls() {
        assertEquals(LogicalControl.L1, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(LogicalControl.R1, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(LogicalControl.START, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(LogicalControl.SELECT, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(null, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_MENU))
        assertEquals(null, ControllerMapping.logicalForAndroidKey(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun stableMappingTableUsesCanonicalAndroidAxes() {
        assertEquals("android-gamepad-v1", ControllerMapping.TABLE_VERSION)
        assertEquals(
            listOf(
                android.view.MotionEvent.AXIS_X,
                android.view.MotionEvent.AXIS_Y,
                android.view.MotionEvent.AXIS_Z,
                android.view.MotionEvent.AXIS_RZ,
                android.view.MotionEvent.AXIS_LTRIGGER,
                android.view.MotionEvent.AXIS_RTRIGGER,
                android.view.MotionEvent.AXIS_HAT_X,
                android.view.MotionEvent.AXIS_HAT_Y
            ),
            ControllerMapping.axisMap.map { it.androidAxis }
        )
    }

    @Test fun logicalStateReturnsButtonsAndDpadToNeutral() {
        val down = ControllerInputState().withButton(LogicalControl.A, true).copy(dpadX = -1f)
        assertTrue(down.isDown(LogicalControl.A))
        assertEquals("LEFT", down.dpadText())
        val neutral = down.withButton(LogicalControl.A, false).copy(dpadX = 0f)
        assertFalse(neutral.isDown(LogicalControl.A))
        assertEquals("NEUTRAL", neutral.dpadText())
    }

    @Test fun dpadStateMachineEmitsOnlyEdges() {
        val machine = DpadStateMachine()
        assertTrue(machine.update(DpadState(1, 0)))
        assertFalse(machine.update(DpadState(1, 0)))
        assertTrue(machine.update(DpadState()))
        assertFalse(machine.update(DpadState()))
    }

    @Test fun dpadStateSupportsDiagonalsAndPolarity() {
        assertEquals("UP", DpadState(0, -1).label)
        assertEquals("DOWN", DpadState(0, 1).label)
        assertEquals("UP_LEFT", DpadState(-1, -1).label)
        assertEquals("DOWN_RIGHT", DpadState(1, 1).label)
    }

    @Test fun systemFacingControllerButtonsAreCanonicalizedBeforeTransport() {
        assertEquals(
            KeyEvent.KEYCODE_BUTTON_START,
            ControllerButtonNormalization.canonicalKeyCode(KeyEvent.KEYCODE_MENU)
        )
        assertEquals(
            KeyEvent.KEYCODE_BUTTON_SELECT,
            ControllerButtonNormalization.canonicalKeyCode(KeyEvent.KEYCODE_BACK)
        )
        assertEquals(
            KeyEvent.KEYCODE_BUTTON_R1,
            ControllerButtonNormalization.canonicalKeyCode(KeyEvent.KEYCODE_BUTTON_R1)
        )
    }
}
