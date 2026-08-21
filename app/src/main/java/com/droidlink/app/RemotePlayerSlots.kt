package com.droidlink.app

object RemotePlayerSlots {
    const val HOST = 1
    const val PLAYER_2 = 2
    const val PLAYER_3 = 3
    const val PLAYER_4 = 4
    const val MAX_TOTAL_PLAYERS = 4
    const val MAX_REMOTE_PLAYERS = 3

    val activeRemoteSlots = listOf(PLAYER_2, PLAYER_3, PLAYER_4)

    fun isActiveRemote(slot: Int) = slot in activeRemoteSlots

    fun controllerDeviceName(slot: Int): String {
        require(isActiveRemote(slot)) { "Unsupported remote player slot: $slot" }
        return "DroidLink Player $slot"
    }

    fun packetMatches(authoritativeSlot: Int, declaredSlot: Int?) =
        isActiveRemote(authoritativeSlot) && declaredSlot == authoritativeSlot

    fun firstAvailable(claimedSlots: Set<Int>): Int? =
        activeRemoteSlots.firstOrNull { it !in claimedSlots }
}
