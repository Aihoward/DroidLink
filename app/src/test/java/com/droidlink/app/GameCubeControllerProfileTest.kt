package com.droidlink.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCubeControllerProfileTest {
    @Test fun dolphinProfileTargetsTheEstablishedPlayer2Device() {
        assertEquals("gamecube-dolphin-v3", GameCubeMapping.TABLE_VERSION)
        assertEquals("DroidLink Player 2", GameCubeMapping.DOLPHIN_DEVICE_NAME)
    }

    @Test fun dolphinDigitalOutputsUseAndroidMappedButtons() {
        assertEquals(315, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(315, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_MENU))
        assertEquals(316, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(316, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals(310, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(311, GameCubeMapping.linuxCodeForAndroidKey(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test fun profilesHaveStableStorageValuesAndSafeDefault() {
        assertEquals(ControllerProfile.PC_WINLATOR, ControllerProfile.fromStorage(null))
        assertEquals(ControllerProfile.PC_WINLATOR, ControllerProfile.fromStorage("unknown"))
        assertEquals(ControllerProfile.PS2, ControllerProfile.fromStorage("ps2"))
        assertEquals(ControllerProfile.GAMECUBE_DOLPHIN, ControllerProfile.fromStorage("gamecube_dolphin"))
    }

    @Test fun faceStartAndZButtonsUseExpectedPhysicalLayout() {
        var state = GameCubeControllerState()
        val mappings = listOf(
            KeyEvent.KEYCODE_BUTTON_A to GameCubeButton.A,
            KeyEvent.KEYCODE_BUTTON_B to GameCubeButton.B,
            KeyEvent.KEYCODE_BUTTON_X to GameCubeButton.X,
            KeyEvent.KEYCODE_BUTTON_Y to GameCubeButton.Y,
            KeyEvent.KEYCODE_BUTTON_START to GameCubeButton.START,
            KeyEvent.KEYCODE_BUTTON_R1 to GameCubeButton.Z
        )
        mappings.forEach { (key, button) ->
            state = requireNotNull(GameCubeMapping.updateKey(state, key, true))
            assertTrue(state.isPressed(button))
            state = requireNotNull(GameCubeMapping.updateKey(state, key, false))
            assertFalse(state.isPressed(button))
        }
        assertNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_SELECT, true))
    }

    @Test fun sticksMapDirectlyAndValuesAreBounded() {
        val state = GameCubeMapping.updateAxes(GameCubeControllerState(), 2f, -2f, 0.75f, -0.5f, -1f, 2f)
        assertEquals(1f, state.mainX)
        assertEquals(-1f, state.mainY)
        assertEquals(0.75f, state.cX)
        assertEquals(-0.5f, state.cY)
        assertEquals(0f, state.analogL)
        assertEquals(1f, state.analogR)
    }

    @Test fun analogTriggersPreserveTravelAndActivateDigitalAtEnd() {
        val below = GameCubeMapping.updateAxes(GameCubeControllerState(), 0f, 0f, 0f, 0f, 0.89f, 0.5f)
        assertEquals(0.89f, below.analogL)
        assertFalse(below.digitalL)
        assertFalse(below.digitalR)
        val pressed = GameCubeMapping.updateAxes(below, 0f, 0f, 0f, 0f, 0.90f, 1f)
        assertTrue(pressed.digitalL)
        assertTrue(pressed.digitalR)
    }

    @Test fun explicitTriggerButtonsMergeWithoutLostReleases() {
        var state = GameCubeControllerState()
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_L1, true))
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_L2, true))
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_L1, false))
        assertTrue(state.digitalL)
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_L2, false))
        assertFalse(state.digitalL)

        state = GameCubeMapping.updateAxes(state, 0f, 0f, 0f, 0f, 0f, 0.95f)
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_R2, true))
        state = requireNotNull(GameCubeMapping.updateKey(state, KeyEvent.KEYCODE_BUTTON_R2, false))
        assertTrue(state.digitalR)
    }

    @Test fun dpadSupportsDiagonalsAndClampsInvalidValues() {
        val state = GameCubeMapping.updateDpad(GameCubeControllerState(), -4, 9)
        assertEquals(-1, state.dpadX)
        assertEquals(1, state.dpadY)
        assertEquals("DOWN_LEFT", DpadState(state.dpadX, state.dpadY).label)
    }

    @Test fun freshStateIsFullyNeutral() {
        val state = GameCubeControllerState()
        assertTrue(state.pressed.isEmpty())
        assertFalse(state.digitalL)
        assertFalse(state.digitalR)
        assertEquals(0f, state.mainX)
        assertEquals(0f, state.cY)
        assertEquals(0, state.dpadX)
        assertEquals(0, state.dpadY)
    }
}
