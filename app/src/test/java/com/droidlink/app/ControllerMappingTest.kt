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
    }

    @Test fun logicalStateReturnsButtonsAndDpadToNeutral() {
        val down = ControllerInputState().withButton(LogicalControl.A, true).copy(dpadX = -1f)
        assertTrue(down.isDown(LogicalControl.A))
        assertEquals("LEFT", down.dpadText())
        val neutral = down.withButton(LogicalControl.A, false).copy(dpadX = 0f)
        assertFalse(neutral.isDown(LogicalControl.A))
        assertEquals("NEUTRAL", neutral.dpadText())
    }
}
