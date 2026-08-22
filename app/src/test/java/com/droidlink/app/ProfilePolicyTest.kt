package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePolicyTest {
    @Test fun displayNamesAreTrimmedCollapsedAndControlCharactersRemoved() {
        assertEquals("Cameron Player", ProfilePolicy.normalizeDisplayName("  Cameron\n\t Player  "))
    }

    @Test fun emptyNameUsesSafeFallback() {
        assertEquals(ProfilePolicy.DEFAULT_DISPLAY_NAME, ProfilePolicy.normalizeDisplayName(" \n\t "))
        assertEquals("Player 3", ProfilePolicy.normalizeDisplayName(null, "Player 3"))
    }

    @Test fun displayNameLengthIsBounded() {
        val normalized = ProfilePolicy.normalizeDisplayName("A".repeat(100))
        assertEquals(ProfilePolicy.MAX_DISPLAY_NAME_LENGTH, normalized.length)
        assertTrue(SessionSecurityPolicy.validDisplayName(normalized))
    }

    @Test fun multiplayerMetadataValidationKeepsNamesAsValuesOnly() {
        assertTrue(
            SessionSecurityPolicy.validParticipantMetadata(
                MultiplayerParticipantMetadata("3.1", MultiplayerCompatibility.PROTOCOL_VERSION, "Same Name")
            )
        )
    }
}
