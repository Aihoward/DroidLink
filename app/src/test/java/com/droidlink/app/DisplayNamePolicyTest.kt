package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNamePolicyTest {
    @Test fun defaults_follow_session_role() {
        assertEquals("Player 1", DisplayNamePolicy.effective("", host = true))
        assertEquals("Player 2", DisplayNamePolicy.effective("   ", host = false))
    }

    @Test fun sanitizes_and_limits_display_names() {
        assertEquals("CP Filmz_1", DisplayNamePolicy.sanitize("  CP   Filmz_1\n" ).trim())
        assertEquals("abcdefghijklmnop", DisplayNamePolicy.sanitize("abcdefghijklmnopq"))
        assertEquals("Jay-The.2", DisplayNamePolicy.sanitize("Jay-The.2🔥"))
    }
}
