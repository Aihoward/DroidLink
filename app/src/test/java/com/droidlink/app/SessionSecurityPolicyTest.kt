package com.droidlink.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSecurityPolicyTest {
    private val validSdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=fingerprint:sha-256 AA\r\n"

    @Test fun validatesRoomCodesStrictly() {
        assertTrue(SessionSecurityPolicy.validRoomCode("123456"))
        assertFalse(SessionSecurityPolicy.validRoomCode("12345"))
        assertFalse(SessionSecurityPolicy.validRoomCode("12345A"))
    }

    @Test fun rejectsMalformedOrOversizedSdp() {
        assertTrue(SessionSecurityPolicy.validSdp(validSdp))
        assertFalse(SessionSecurityPolicy.validSdp("v=0\r\nm=video"))
        assertFalse(SessionSecurityPolicy.validSdp(validSdp + "x".repeat(SessionSecurityPolicy.MAX_SDP_BYTES)))
    }

    @Test fun validatesIceShapeAndLimits() {
        assertTrue(SessionSecurityPolicy.validIce("candidate:1 1 udp 1 192.0.2.1 9 typ host", "0", 0))
        assertFalse(SessionSecurityPolicy.validIce("not-a-candidate", "0", 0))
        assertFalse(SessionSecurityPolicy.validIce("candidate:ok", "0", 99))
    }

    @Test fun rejectsOversizedControllerPayloads() {
        assertTrue(SessionSecurityPolicy.validControlPayloadSize(128))
        assertFalse(SessionSecurityPolicy.validControlPayloadSize(SessionSecurityPolicy.MAX_CONTROL_BYTES + 1))
    }
}
