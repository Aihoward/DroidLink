package com.droidlink.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuMusicPolicyTest {
    @Test fun menuMusicPlaysOnlyOnNormalInactiveMenus() {
        assertTrue(MenuMusicPolicy.shouldPlay(true, true, false, false))
        assertFalse(MenuMusicPolicy.shouldPlay(false, true, false, false))
        assertFalse(MenuMusicPolicy.shouldPlay(true, false, false, false))
        assertFalse(MenuMusicPolicy.shouldPlay(true, true, true, false))
        assertFalse(MenuMusicPolicy.shouldPlay(true, true, false, true))
    }
}
