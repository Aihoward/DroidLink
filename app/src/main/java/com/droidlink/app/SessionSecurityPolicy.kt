package com.droidlink.app

object SessionSecurityPolicy {
    const val MAX_SDP_BYTES = 256 * 1024
    const val MAX_ICE_BYTES = 4 * 1024
    const val MAX_CONTROL_BYTES = 2 * 1024
    const val MAX_PENDING_ICE = 256
    const val MAX_SIGNAL_AGE_MS = 2 * 60 * 60 * 1_000L
    private val roomCodePattern = Regex("^[0-9]{6}$")
    private val sidePattern = Regex("^(host|client)$")

    fun validRoomCode(code: String) = roomCodePattern.matches(code)
    fun validSide(side: String) = sidePattern.matches(side)

    fun validSdp(sdp: String): Boolean {
        val size = sdp.toByteArray(Charsets.UTF_8).size
        return size in 16..MAX_SDP_BYTES &&
            sdp.startsWith("v=0") &&
            sdp.contains("a=fingerprint:") &&
            sdp.contains("m=video") &&
            !sdp.contains('\u0000')
    }

    fun validIce(candidate: String, mid: String?, line: Int): Boolean {
        val size = candidate.toByteArray(Charsets.UTF_8).size
        return size in 12..MAX_ICE_BYTES &&
            candidate.startsWith("candidate:") &&
            !candidate.contains('\u0000') &&
            line in 0..16 &&
            (mid == null || mid.length <= 64)
    }

    fun validControlPayloadSize(bytes: Int) = bytes in 1..MAX_CONTROL_BYTES
    fun fresh(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()) =
        createdAtMs > 0L && nowMs >= createdAtMs && nowMs - createdAtMs <= MAX_SIGNAL_AGE_MS
}
