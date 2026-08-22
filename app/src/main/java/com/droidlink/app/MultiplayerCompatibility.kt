package com.droidlink.app

data class MultiplayerParticipantMetadata(
    val appVersion: String,
    val protocolVersion: Int,
    val displayName: String
)

enum class CompatibilityResult {
    COMPATIBLE,
    MISSING_METADATA,
    APP_VERSION_MISMATCH,
    PROTOCOL_VERSION_MISMATCH
}

object MultiplayerCompatibility {
    const val PROTOCOL_VERSION = 1

    fun evaluate(
        localAppVersion: String,
        localProtocolVersion: Int,
        remoteAppVersion: String?,
        remoteProtocolVersion: Int?
    ): CompatibilityResult = when {
        remoteAppVersion.isNullOrBlank() || remoteProtocolVersion == null -> CompatibilityResult.MISSING_METADATA
        localAppVersion != remoteAppVersion -> CompatibilityResult.APP_VERSION_MISMATCH
        localProtocolVersion != remoteProtocolVersion -> CompatibilityResult.PROTOCOL_VERSION_MISMATCH
        else -> CompatibilityResult.COMPATIBLE
    }
}
