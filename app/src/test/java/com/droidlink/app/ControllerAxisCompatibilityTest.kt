package com.droidlink.app

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun zRzBecomeTriggersWhenRxRyAndNoDedicatedTriggersExist() {
        val resolved = ControllerAxisCompatibility.resolve(
            setOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX,
                MotionEvent.AXIS_RY
            )
        )

        assertEquals(MotionEvent.AXIS_RX, resolved.rightX)
        assertEquals(MotionEvent.AXIS_RY, resolved.rightY)
        assertEquals(MotionEvent.AXIS_Z, resolved.leftTrigger)
        assertEquals(MotionEvent.AXIS_RZ, resolved.rightTrigger)
    }

    @Test fun brakeThrottleControllersUseSupportedTriggerFallback() {
        val resolved = ControllerAxisCompatibility.resolve(
            setOf(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_THROTTLE)
        )

        assertEquals(MotionEvent.AXIS_BRAKE, resolved.leftTrigger)
        assertEquals(MotionEvent.AXIS_THROTTLE, resolved.rightTrigger)
    }

    @Test fun missingPairsAreNotInvented() {
        val resolved = ControllerAxisCompatibility.resolve(
            setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_RX, MotionEvent.AXIS_HAT_X)
        )

        assertTrue(resolved.orderedMappings().all {
            it.androidAxis == ControllerAxisCompatibility.UNAVAILABLE_AXIS
        })
    }

    @Test fun sticksUseMotionRangeAndPreserveFullAnalogRange() {
        val range = ControllerAxisRange(-32768f, 32767f, 4096f)

        assertEquals(-1f, ControllerAxisNormalizer.normalizeStick(-32768f, range), 0.0001f)
        assertEquals(0f, ControllerAxisNormalizer.normalizeStick(0f, range), 0.0001f)
        assertEquals(1f, ControllerAxisNormalizer.normalizeStick(32767f, range), 0.0001f)
        assertEquals(0.50002f, ControllerAxisNormalizer.normalizeStick(16384f, range), 0.0001f)
    }

    @Test fun centeredTriggersIgnoreInitialAndroidZeroThenNormalizeAfterActivation() {
        val range = ControllerAxisRange(-1f, 1f, 0f)

        assertEquals(0f, ControllerAxisNormalizer.normalizeTrigger(0f, range, false), 0.0001f)
        assertTrue(ControllerAxisNormalizer.centeredTriggerActivated(-1f, range))
        assertEquals(0f, ControllerAxisNormalizer.normalizeTrigger(-1f, range, true), 0.0001f)
        assertEquals(0.5f, ControllerAxisNormalizer.normalizeTrigger(0f, range, true), 0.0001f)
        assertEquals(1f, ControllerAxisNormalizer.normalizeTrigger(1f, range, true), 0.0001f)
    }

    @Test fun hatAxesUseStableDigitalThresholds() {
        assertEquals(-1, ControllerAxisNormalizer.dpadDirection(-0.75f))
        assertEquals(0, ControllerAxisNormalizer.dpadDirection(0.49f))
        assertEquals(1, ControllerAxisNormalizer.dpadDirection(0.75f))
    }
}
