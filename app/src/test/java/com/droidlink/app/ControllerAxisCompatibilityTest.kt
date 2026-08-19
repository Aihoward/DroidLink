package com.droidlink.app

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerAxisCompatibilityTest {
    @Test fun canonicalAndroidAxesRemainUnchanged() {
        val available = setOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y
        )
        val resolved = ControllerAxisCompatibility.resolve(available)
        assertEquals(ControllerMapping.axisMap.map { it.androidAxis }, resolved.orderedMappings().map { it.androidAxis })
    }

    @Test fun rxRyAndBrakeGasControllersUseSupportedFallbacks() {
        val resolved = ControllerAxisCompatibility.resolve(
            setOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_RX,
                MotionEvent.AXIS_RY,
                MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_GAS,
                MotionEvent.AXIS_HAT_X,
                MotionEvent.AXIS_HAT_Y
            )
        )
        assertEquals(MotionEvent.AXIS_RX, resolved.rightX)
        assertEquals(MotionEvent.AXIS_RY, resolved.rightY)
        assertEquals(MotionEvent.AXIS_BRAKE, resolved.leftTrigger)
        assertEquals(MotionEvent.AXIS_GAS, resolved.rightTrigger)
    }

    @Test fun canonicalAxesWinWhenBothRepresentationsExist() {
        val all = setOf(
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX,
            MotionEvent.AXIS_RY,
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GAS
        )
        val resolved = ControllerAxisCompatibility.resolve(all)
        assertEquals(MotionEvent.AXIS_Z, resolved.rightX)
        assertEquals(MotionEvent.AXIS_RZ, resolved.rightY)
        assertEquals(MotionEvent.AXIS_LTRIGGER, resolved.leftTrigger)
        assertEquals(MotionEvent.AXIS_RTRIGGER, resolved.rightTrigger)
    }
}
