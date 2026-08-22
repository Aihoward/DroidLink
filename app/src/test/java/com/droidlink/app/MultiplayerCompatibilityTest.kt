package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplayerCompatibilityTest {
    @Test fun identicalAppAndProtocolVersionsAreCompatible() {
        assertEquals(
            CompatibilityResult.COMPATIBLE,
            MultiplayerCompatibility.evaluate("3.1", 1, "3.1", 1)
        )
    }

    @Test fun differentAppVersionsAreRejectedWithoutLatestVersionPolicy() {
        assertEquals(
            CompatibilityResult.APP_VERSION_MISMATCH,
            MultiplayerCompatibility.evaluate("3.1", 1, "3.0", 1)
        )
        assertEquals(
            CompatibilityResult.COMPATIBLE,
            MultiplayerCompatibility.evaluate("3.0", 1, "3.0", 1)
        )
    }

    @Test fun protocolMismatchAndMissingMetadataAreRejected() {
        assertEquals(
            CompatibilityResult.PROTOCOL_VERSION_MISMATCH,
            MultiplayerCompatibility.evaluate("3.1", 1, "3.1", 2)
        )
        assertEquals(
            CompatibilityResult.MISSING_METADATA,
            MultiplayerCompatibility.evaluate("3.1", 1, null, null)
        )
    }
}
