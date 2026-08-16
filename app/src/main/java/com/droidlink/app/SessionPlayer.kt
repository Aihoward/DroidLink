package com.droidlink.app

data class SessionPlayer(
    val sessionId: String,
    val displayName: String,
    val slot: Int,
    val role: String,
    val connected: Boolean,
    val voiceEnabled: Boolean,
    val speaking: Boolean = false
)
