package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlayerSlotsTest {
    @Test fun assignsPlayer2ThroughPlayer4AndRejectsPlayer5() {
        assertEquals(2, RemotePlayerSlots.firstAvailable(emptySet()))
        assertEquals(3, RemotePlayerSlots.firstAvailable(setOf(2)))
        assertEquals(4, RemotePlayerSlots.firstAvailable(setOf(2, 3)))
        assertNull(RemotePlayerSlots.firstAvailable(setOf(2, 3, 4)))
        assertTrue(RemotePlayerSlots.isActiveRemote(RemotePlayerSlots.PLAYER_4))
        assertFalse(RemotePlayerSlots.isActiveRemote(5))
        assertEquals(4, RemotePlayerSlots.MAX_TOTAL_PLAYERS)
        assertEquals(3, RemotePlayerSlots.MAX_REMOTE_PLAYERS)
    }

    @Test fun preservesPlayer3ControllerSetUntilPlayer4IsClaimed() {
        assertEquals(
            listOf(RemotePlayerSlots.PLAYER_2, RemotePlayerSlots.PLAYER_3),
            RemotePlayerSlots.controllerSlotsAtHostStart
        )
        assertFalse(RemotePlayerSlots.PLAYER_4 in RemotePlayerSlots.controllerSlotsAtHostStart)
        assertTrue(RemotePlayerSlots.PLAYER_4 in RemotePlayerSlots.activeRemoteSlots)
    }

    @Test fun player3IsNotCompactedWhenPlayer2Leaves() {
        val claimedAfterPlayer2Leaves = setOf(RemotePlayerSlots.PLAYER_3, RemotePlayerSlots.PLAYER_4)
        assertTrue(RemotePlayerSlots.PLAYER_3 in claimedAfterPlayer2Leaves)
        assertTrue(RemotePlayerSlots.PLAYER_4 in claimedAfterPlayer2Leaves)
        assertEquals(RemotePlayerSlots.PLAYER_2, RemotePlayerSlots.firstAvailable(claimedAfterPlayer2Leaves))
    }

    @Test fun player4IsNotCompactedWhenPlayer3Leaves() {
        val claimedAfterPlayer3Leaves = setOf(RemotePlayerSlots.PLAYER_2, RemotePlayerSlots.PLAYER_4)
        assertTrue(RemotePlayerSlots.PLAYER_4 in claimedAfterPlayer3Leaves)
        assertEquals(RemotePlayerSlots.PLAYER_3, RemotePlayerSlots.firstAvailable(claimedAfterPlayer3Leaves))
    }

    @Test fun controllerIdentityAndPacketRoutingAreSlotSpecific() {
        assertEquals("DroidLink Player 2", RemotePlayerSlots.controllerDeviceName(2))
        assertEquals("DroidLink Player 3", RemotePlayerSlots.controllerDeviceName(3))
        assertEquals("DroidLink Player 4", RemotePlayerSlots.controllerDeviceName(4))
        assertTrue(RemotePlayerSlots.packetMatches(2, 2))
        assertTrue(RemotePlayerSlots.packetMatches(3, 3))
        assertTrue(RemotePlayerSlots.packetMatches(4, 4))
        assertFalse(RemotePlayerSlots.packetMatches(2, 3))
        assertFalse(RemotePlayerSlots.packetMatches(3, 2))
        assertFalse(RemotePlayerSlots.packetMatches(4, 3))
        assertFalse(RemotePlayerSlots.packetMatches(3, 4))
    }
}
