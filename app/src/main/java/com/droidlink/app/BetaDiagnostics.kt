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
    val targetVideoBitrateBps: Long? = null,
    val packetLoss: Long = 0L,
    val recentPacketLossPercent: Double? = null,
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
    val packetSendDelayMs: Double? = null,
    val keyFramesEncoded: Long = 0L,
    val keyFramesDecoded: Long = 0L,
    val retransmittedPacketsSent: Long = 0L,
    val nackCount: Long = 0L,
    val pliCount: Long = 0L,
    val firCount: Long = 0L,
    val qualityLimitationReason: String = "Unavailable",
    val qualityLimitationDurations: String = "Unavailable",
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

    fun intervalPacketLossPercent(currentLost: Long, previousLost: Long, currentSent: Long, previousSent: Long): Double? {
        val lostDelta = currentLost - previousLost
        val sentDelta = currentSent - previousSent
        return if (lostDelta < 0L || sentDelta <= 0L) null else lostDelta * 100.0 / sentDelta
    }

    fun trend(currentMs: Double?, previousMs: Double?, thresholdMs: Double = 10.0): String = when {
        currentMs == null || previousMs == null -> "WARMING UP"
        currentMs > previousMs + thresholdMs -> "RISING"
        currentMs < previousMs - thresholdMs -> "FALLING"
        else -> "STABLE"
    }
}

data class VideoAdaptationTarget(
    val maxBitrateBps: Int,
    val maxFps: Int,
    val scaleResolutionDownBy: Double
)

enum class VideoAdaptationAction { HOLD, DEGRADE, RECOVER }

object VideoAdaptationPolicy {
    const val MAX_LEVEL = 3

    fun manages(profile: String) = profile == "Auto" || profile == "Low Latency"

    fun target(profile: String, baseFps: Int, level: Int): VideoAdaptationTarget {
        val safeLevel = level.coerceIn(0, MAX_LEVEL)
        val lowLatency = profile == "Low Latency"
        val maxBitrate = when (safeLevel) {
            0 -> if (lowLatency) 3_500_000 else 8_000_000
            1 -> 2_500_000
            2 -> 1_500_000
            else -> 800_000
        }
        val maxFps = when (safeLevel) {
            0, 1 -> baseFps
            2 -> minOf(baseFps, 45)
            else -> minOf(baseFps, 30)
        }
        val scale = when (safeLevel) {
            0, 1 -> 1.0
            2 -> 4.0 / 3.0
            else -> 2.0
        }
        return VideoAdaptationTarget(maxBitrate, maxFps, scale)
    }

    fun action(
        profile: String,
        level: Int,
        constrainedSamples: Int,
        healthySamples: Int,
        millisecondsSinceChange: Long
    ): VideoAdaptationAction {
        if (!manages(profile)) return VideoAdaptationAction.HOLD
        val degradeSamples = if (profile == "Low Latency") 1 else 2
        val degradeIntervalMs = if (profile == "Low Latency") 5_000L else 10_000L
        return when {
            level < MAX_LEVEL && constrainedSamples >= degradeSamples && millisecondsSinceChange >= degradeIntervalMs -> VideoAdaptationAction.DEGRADE
            level > 0 && healthySamples >= 3 && millisecondsSinceChange >= 15_000L -> VideoAdaptationAction.RECOVER
            else -> VideoAdaptationAction.HOLD
        }
    }

    fun recoveryBandwidthBps(profile: String, baseFps: Int, currentLevel: Int): Double {
        if (currentLevel <= 0) return 0.0
        val nextTarget = target(profile, baseFps, currentLevel - 1)
        return minOf(nextTarget.maxBitrateBps * 1.15, 3_000_000.0)
    }
}

object StreamingLatencyPolicy {
    const val RECEIVE_PLAYOUT_MAX_MS = 60
    const val RECEIVE_MIN_DECODE_PACING_MS = 4
    const val RECEIVE_MAX_DECODE_QUEUE_FRAMES = 1
    const val RECEIVE_FIELD_TRIALS =
        "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:60/" +
            "WebRTC-ZeroPlayoutDelay/min_pacing:4ms,max_decode_queue_size:1/"
}

object ControllerTransportPolicy {
    const val ANALOG_SEND_INTERVAL_MS = 10L
    const val ANALOG_HEARTBEAT_MS = 250L
    const val MAX_ANALOG_BUFFERED_BYTES = 1L * 1024L
    const val STALE_ANALOG_RTT_THRESHOLD_MS = 300L
    const val CHANNEL_RECOVERY_COOLDOWN_MS = 2_000L
    const val HEALTH_INTERVAL_MS = 2_000L
    const val STALE_ACTIVE_INPUT_MS = 2_500L
    const val HEALTH_LOG_EVERY_TICKS = 15

    fun shouldDropAnalog(bufferedBytes: Long) = bufferedBytes >= MAX_ANALOG_BUFFERED_BYTES
    fun estimatedPacketAgeMs(captureMs: Long, roundTripMs: Long?) =
        captureMs.coerceAtLeast(0L) + ((roundTripMs ?: 0L).coerceAtLeast(0L) / 2L)
}
