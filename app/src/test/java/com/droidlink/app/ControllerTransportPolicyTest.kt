package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTransportPolicyTest {
    @Test fun analogBackpressureDropsAtBoundedThreshold() {
        assertFalse(ControllerTransportPolicy.shouldDropAnalog(ControllerTransportPolicy.MAX_ANALOG_BUFFERED_BYTES - 1))
        assertTrue(ControllerTransportPolicy.shouldDropAnalog(ControllerTransportPolicy.MAX_ANALOG_BUFFERED_BYTES))
    }

    @Test fun estimatedPacketAgeIncludesCaptureAndHalfRoundTrip() {
        assertEquals(27L, ControllerTransportPolicy.estimatedPacketAgeMs(captureMs = 7L, roundTripMs = 40L))
    }

    @Test fun estimatedPacketAgeClampsInvalidInputs() {
        assertEquals(0L, ControllerTransportPolicy.estimatedPacketAgeMs(captureMs = -2L, roundTripMs = -10L))
    }
}
