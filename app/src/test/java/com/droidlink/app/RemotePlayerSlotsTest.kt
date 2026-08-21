package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlayerSlotsTest {
    @Test fun assignsOnlyPlayer2AndPlayer3() {
        assertEquals(2, RemotePlayerSlots.firstAvailable(emptySet()))
        assertEquals(3, RemotePlayerSlots.firstAvailable(setOf(2)))
        assertNull(RemotePlayerSlots.firstAvailable(setOf(2, 3)))
        assertFalse(RemotePlayerSlots.isActiveRemote(RemotePlayerSlots.RESERVED_PLAYER_4))
    }

    @Test fun player3IsNotCompactedWhenPlayer2Leaves() {
        val claimedAfterPlayer2Leaves = setOf(RemotePlayerSlots.PLAYER_3)
        assertTrue(RemotePlayerSlots.PLAYER_3 in claimedAfterPlayer2Leaves)
        assertEquals(RemotePlayerSlots.PLAYER_2, RemotePlayerSlots.firstAvailable(claimedAfterPlayer2Leaves))
    }

    @Test fun controllerIdentityAndPacketRoutingAreSlotSpecific() {
        assertEquals("DroidLink Player 2", RemotePlayerSlots.controllerDeviceName(2))
        assertEquals("DroidLink Player 3", RemotePlayerSlots.controllerDeviceName(3))
        assertTrue(RemotePlayerSlots.packetMatches(2, 2))
        assertTrue(RemotePlayerSlots.packetMatches(3, 3))
        assertFalse(RemotePlayerSlots.packetMatches(2, 3))
        assertFalse(RemotePlayerSlots.packetMatches(3, 2))
    }
}
