package com.droidlink.app

enum class JoinSessionState {
    Idle, LookingForRoom, Negotiating, Connecting, Connected, Reconnecting, Failed, Disconnected;

    fun allows(next: JoinSessionState): Boolean = when {
        next == this -> false
        this == Connected -> next in setOf(Reconnecting, Failed, Disconnected)
        this == Reconnecting -> next in setOf(Connected, Failed, Disconnected)
        this == Failed || this == Disconnected -> next == Idle || next == LookingForRoom
        else -> true
    }

    val showsActiveSession: Boolean get() = this == Connected || this == Reconnecting
}
