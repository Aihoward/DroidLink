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

    @Test fun controllerRecoveryAndWatchdogMatchStableReleaseTiming() {
        assertEquals(2_000L, ControllerTransportPolicy.CHANNEL_RECOVERY_COOLDOWN_MS)
        assertEquals(2_000L, ControllerTransportPolicy.HEALTH_INTERVAL_MS)
        assertEquals(2_500L, ControllerTransportPolicy.STALE_ACTIVE_INPUT_MS)
        assertEquals(15, ControllerTransportPolicy.HEALTH_LOG_EVERY_TICKS)
    }

    @Test fun streamingLatencyPolicyPinsTwoPointZeroV2ReceivePath() {
        assertEquals(60, StreamingLatencyPolicy.RECEIVE_PLAYOUT_MAX_MS)
        assertEquals(4, StreamingLatencyPolicy.RECEIVE_MIN_DECODE_PACING_MS)
        assertEquals(1, StreamingLatencyPolicy.RECEIVE_MAX_DECODE_QUEUE_FRAMES)
        assertEquals(
            "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:60/" +
                "WebRTC-ZeroPlayoutDelay/min_pacing:4ms,max_decode_queue_size:1/",
            StreamingLatencyPolicy.RECEIVE_FIELD_TRIALS
        )
    }
}
