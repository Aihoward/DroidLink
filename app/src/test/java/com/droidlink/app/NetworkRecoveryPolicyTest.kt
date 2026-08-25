package com.droidlink.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test fun healthyOrUnavailableNetworkDoesNotRestartIce() {
        assertFalse(NetworkRecoveryPolicy.canRestart(0, 20_000L, false))
    }

    @Test fun restartRequiresCooldownAndIsBounded() {
        assertFalse(NetworkRecoveryPolicy.canRestart(0, 9_999L, true))
        assertTrue(NetworkRecoveryPolicy.canRestart(0, 10_000L, true))
        assertTrue(NetworkRecoveryPolicy.canRestart(1, 10_000L, true))
        assertFalse(NetworkRecoveryPolicy.canRestart(2, 10_000L, true))
    }
}
