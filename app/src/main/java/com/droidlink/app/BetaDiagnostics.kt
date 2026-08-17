package com.droidlink.app

data class BetaDiagnostics(
    val connectionState: String = "IDLE",
    val iceState: String = "NEW",
    val route: String = "Unknown",
    val candidatePair: String = "Not selected",
    val rttMs: Double? = null,
    val resolution: String = "—",
    val fps: Double? = null,
    val videoBitrateBps: Long? = null,
    val packetLoss: Long = 0L,
    val jitterMs: Double? = null,
    val framesEncoded: Long = 0L,
    val framesReceived: Long = 0L,
    val framesDecoded: Long = 0L,
    val framesRendered: Long? = null,
    val packetsReceived: Long = 0L,
    val framesDropped: Long = 0L,
    val captureFps: Double? = null,
    val requestedCaptureFps: Int? = null,
    val videoAdaptationLevel: Int = 0,
    val encodeFps: Double? = null,
    val receiveFps: Double? = null,
    val decodeFps: Double? = null,
    val renderFps: Double? = null,
    val availableOutgoingBitrateBps: Long? = null,
    val averageEncodeTimeMs: Double? = null,
    val averageDecodeTimeMs: Double? = null,
    val captureLatencyMs: Double? = null,
    val videoJitterBufferMs: Double? = null,
    val videoJitterBufferTargetMs: Double? = null,
    val videoJitterBufferMinimumMs: Double? = null,
    val videoJitterBufferObservedMinMs: Double? = null,
    val videoJitterBufferObservedMaxMs: Double? = null,
    val videoJitterBufferTrend: String = "Unavailable",
    val renderLatencyMs: Double? = null,
    val frameAgeAtRenderMs: Double? = null,
    val encoderQueueDepth: Int? = null,
    val decoderQueueDepth: Int? = null,
    val renderQueueDepth: Int? = null,
    val encoderImplementation: String = "Unavailable",
    val decoderImplementation: String = "Unavailable",
    val videoBottleneck: String = "UNKNOWN",
    val gameAudioPacketsSent: Long = 0L,
    val gameAudioBytesSent: Long = 0L,
    val gameAudioPacketsReceived: Long = 0L,
    val gameAudioBytesReceived: Long = 0L,
    val audioJitterBufferMs: Double? = null,
    val audioJitterBufferTargetMs: Double? = null,
    val audioJitterBufferMinimumMs: Double? = null,
    val audioConcealedSamples: Long = 0L,
    val audioConcealmentEvents: Long = 0L,
    val audioPlayoutDelayMs: Double? = null,
    val audioOutputRoute: String = "Unavailable",
    val audioTrackState: String = "Not started",
    val audioUnderruns: Long? = null,
    val avSyncMode: String = "Independent gaming playout",
    val controllerPacketsPerSecond: Double = 0.0,
    val lastControllerLatencyMs: Long? = null,
    val averageControllerLatencyMs: Long? = null,
    val maxControllerLatencyMs: Long? = null,
    val controllerP95LatencyMs: Long? = null,
    val controllerP50LatencyMs: Long? = null,
    val controllerPacketAgeMs: Long? = null,
    val averageControllerPacketAgeMs: Long? = null,
    val digitalQueueDepth: Int = 0,
    val analogQueueDepth: Int = 0,
    val controlBufferedBytes: Long = 0L,
    val droppedStaleAnalogPackets: Long = 0L,
    val duplicateControlPacketsDropped: Long = 0L,
    val outOfOrderControlPacketsDropped: Long = 0L,
    val player2Status: String = "Not active",
    val player2Classification: String = "Unknown"
)

object VideoStatsPolicy {
    fun ratePerSecond(current: Long, previous: Long, elapsedMs: Long): Double? =
        if (elapsedMs <= 0L || current < previous) null else (current - previous) * 1_000.0 / elapsedMs

    fun bitrateBps(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long? =
        ratePerSecond(currentBytes, previousBytes, elapsedMs)?.times(8.0)?.toLong()

    fun intervalAverageMs(currentSeconds: Double, previousSeconds: Double, currentCount: Long, previousCount: Long): Double? {
        val countDelta = currentCount - previousCount
        val delayDelta = currentSeconds - previousSeconds
        return if (countDelta <= 0L || delayDelta < 0.0) null else delayDelta * 1_000.0 / countDelta
    }

    fun trend(currentMs: Double?, previousMs: Double?, thresholdMs: Double = 10.0): String = when {
        currentMs == null || previousMs == null -> "WARMING UP"
        currentMs > previousMs + thresholdMs -> "RISING"
        currentMs < previousMs - thresholdMs -> "FALLING"
        else -> "STABLE"
    }
}

object ControllerTransportPolicy {
    const val ANALOG_SEND_INTERVAL_MS = 10L
    const val ANALOG_HEARTBEAT_MS = 250L
    const val MAX_ANALOG_BUFFERED_BYTES = 1L * 1024L
    const val STALE_ANALOG_RTT_THRESHOLD_MS = 300L

    fun shouldDropAnalog(bufferedBytes: Long) = bufferedBytes >= MAX_ANALOG_BUFFERED_BYTES
    fun estimatedPacketAgeMs(captureMs: Long, roundTripMs: Long?) =
        captureMs.coerceAtLeast(0L) + ((roundTripMs ?: 0L).coerceAtLeast(0L) / 2L)
}
