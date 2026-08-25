package com.droidlink.app

import android.view.InputDevice

object AndroidControllerDevicePolicy {
    fun isEligible(sources: Int, isVirtual: Boolean, name: String?): Boolean {
        if (isVirtual || name?.startsWith("DroidLink Player ") == true) return false
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return gamepad || joystick
    }

    fun sourceSummary(sources: Int): String = buildList {
        if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("GAMEPAD")
        if (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("JOYSTICK")
        if (sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("DPAD")
    }.ifEmpty { listOf("UNSUPPORTED") }.joinToString(" / ")
}
