package com.droidlink.app

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidControllerDevicePolicyTest {
    @Test fun standardBluetoothOrUsbGamepadCapabilitiesAreEligible() {
        assertTrue(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_GAMEPAD, false, "Wireless Controller"))
        assertTrue(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_JOYSTICK, false, "USB Gamepad"))
    }

    @Test fun gamingHandheldControllerCapabilitiesRemainEligible() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD
        assertTrue(AndroidControllerDevicePolicy.isEligible(sources, false, "Built-in gamepad"))
    }

    @Test fun touchscreenKeyboardMouseAndDpadOnlyDevicesAreRejected() {
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_TOUCHSCREEN, false, "Touchscreen"))
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_KEYBOARD, false, "Keyboard"))
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_MOUSE, false, "Mouse"))
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_DPAD, false, "Remote"))
    }

    @Test fun droidLinkOutputAndOtherVirtualDevicesAreNotReingested() {
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_GAMEPAD, false, "DroidLink Player 2"))
        assertFalse(AndroidControllerDevicePolicy.isEligible(InputDevice.SOURCE_GAMEPAD, true, "Virtual gamepad"))
    }
}
