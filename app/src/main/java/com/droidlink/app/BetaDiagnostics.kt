package com.droidlink.app

data class BetaDiagnostics(
    val connectionState: String = "IDLE",
    val iceState: String = "NEW",
    val route: String = "Unknown",
    val candidatePair: String = "Not selected",
    val rttMs: Double? = null,
    val resolution: String = "—",
    val fps: Double? = null,
    val videoBitrateBps: Long = 0L,
    val packetLoss: Long = 0L,
    val jitterMs: Double? = null,
    val framesEncoded: Long = 0L,
    val framesDecoded: Long = 0L,
    val framesDropped: Long = 0L,
    val captureFps: Double? = null,
    val encodeFps: Double? = null,
    val decodeFps: Double? = null,
    val renderFps: Double? = null,
    val availableOutgoingBitrateBps: Long = 0L,
    val averageEncodeTimeMs: Double? = null,
    val averageDecodeTimeMs: Double? = null,
    val captureLatencyMs: Double? = null,
    val videoJitterBufferMs: Double? = null,
    val renderLatencyMs: Double? = null,
    val frameAgeAtRenderMs: Double? = null,
    val encoderQueueDepth: Int = 0,
    val decoderQueueDepth: Int = 0,
    val renderQueueDepth: Int = 0,
    val encoderImplementation: String = "Unknown",
    val decoderImplementation: String = "Unknown",
    val videoBottleneck: String = "UNKNOWN",
    val gameAudioPacketsSent: Long = 0L,
    val gameAudioBytesSent: Long = 0L,
    val gameAudioPacketsReceived: Long = 0L,
    val gameAudioBytesReceived: Long = 0L,
    val controllerPacketsPerSecond: Double = 0.0,
    val lastControllerLatencyMs: Long? = null,
    val averageControllerLatencyMs: Long? = null,
    val maxControllerLatencyMs: Long? = null,
    val controllerP95LatencyMs: Long? = null,
    val controllerP50LatencyMs: Long? = null,
    val controllerPacketAgeMs: Long? = null,
    val digitalQueueDepth: Int = 0,
    val analogQueueDepth: Int = 0,
    val controlBufferedBytes: Long = 0L,
    val droppedStaleAnalogPackets: Long = 0L,
    val duplicateControlPacketsDropped: Long = 0L,
    val outOfOrderControlPacketsDropped: Long = 0L,
    val player2Status: String = "Not active",
    val player2Classification: String = "Unknown"
)

object ControllerTransportPolicy {
    const val ANALOG_SEND_INTERVAL_MS = 10L
    const val ANALOG_HEARTBEAT_MS = 250L
    const val MAX_ANALOG_BUFFERED_BYTES = 1L * 1024L
    const val STALE_ANALOG_RTT_THRESHOLD_MS = 300L

    fun shouldDropAnalog(bufferedBytes: Long) = bufferedBytes >= MAX_ANALOG_BUFFERED_BYTES
    fun estimatedPacketAgeMs(captureMs: Long, roundTripMs: Long?) =
        captureMs.coerceAtLeast(0L) + ((roundTripMs ?: 0L).coerceAtLeast(0L) / 2L)
}
