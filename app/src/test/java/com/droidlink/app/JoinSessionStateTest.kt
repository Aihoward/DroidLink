package com.droidlink.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinSessionStateTest {
    @Test fun connected_rejects_late_negotiation_regressions() {
        assertFalse(JoinSessionState.Connected.allows(JoinSessionState.Connecting))
        assertFalse(JoinSessionState.Connected.allows(JoinSessionState.Negotiating))
        assertTrue(JoinSessionState.Connected.allows(JoinSessionState.Reconnecting))
        assertTrue(JoinSessionState.Connected.allows(JoinSessionState.Failed))
    }

    @Test fun reconnecting_can_recover_or_end() {
        assertTrue(JoinSessionState.Reconnecting.allows(JoinSessionState.Connected))
        assertTrue(JoinSessionState.Reconnecting.allows(JoinSessionState.Disconnected))
        assertFalse(JoinSessionState.Reconnecting.allows(JoinSessionState.Connecting))
    }
}
