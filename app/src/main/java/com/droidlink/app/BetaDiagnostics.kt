package com.droidlink.app

data class BetaDiagnostics(
    val connectionState: String = "IDLE",
    val iceState: String = "NEW",
    val route: String = "Unknown",
    val candidatePair: String = "Not selected",
    val rttMs: Double? = null,
    val resolution: String = "—",
    val captureResolution: String = "Unavailable",
    val encodedResolution: String = "Unavailable",
    val decodedResolution: String = "Unavailable",
    val renderedResolution: String = "Unavailable",
    val fps: Double? = null,
    val videoBitrateBps: Long? = null,
    val targetVideoBitrateBps: Long? = null,
    val recentPacketLossPercent: Double? = null,
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
    val selectedVideoCodec: String = "Unavailable",
    val codecQualityLimitationReason: String = "Unavailable",
    val videoAdaptationReason: String = "Full quality",
    val degradationPreference: String = "Unavailable",
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

    fun intervalPacketLossPercent(
        currentLost: Long,
        previousLost: Long,
        currentPackets: Long,
        previousPackets: Long,
        packetCountIncludesLost: Boolean = false
    ): Double? {
        val lostDelta = currentLost - previousLost
        val packetDelta = currentPackets - previousPackets
        val denominator = packetDelta + if (packetCountIncludesLost) 0L else lostDelta
        return if (lostDelta < 0L || packetDelta <= 0L || denominator <= 0L) null else lostDelta * 100.0 / denominator
    }

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

    fun decoderLimited(
        receivedFps: Double?,
        decodedFps: Double?,
        averageDecodeTimeMs: Double?,
        droppedFramesDelta: Long
    ): Boolean {
        if (receivedFps == null || decodedFps == null || receivedFps < 1.0) return false
        val frameBudgetMs = 1_000.0 / receivedFps
        val decodeTimePressure = averageDecodeTimeMs != null && averageDecodeTimeMs >= frameBudgetMs * 0.85
        val decodeThroughputPressure = receivedFps >= 15.0 && decodedFps < receivedFps * 0.82 && droppedFramesDelta > 0L
        return decodeTimePressure || decodeThroughputPressure
    }
}

enum class VideoPressure { HEALTHY, ENCODER, BANDWIDTH, SEVERE, UNKNOWN }
enum class VideoAdaptationAction { HOLD, DEGRADE, RECOVER }

data class VideoAdaptationTarget(
    val maxBitrateBps: Int,
    val maxFps: Int,
    val scaleResolutionDownBy: Double,
    val minimumHealthyBandwidthBps: Double
)

object VideoQualityPolicy {
    const val MAX_AUTO_LEVEL = 4

    fun autoTarget(baseFps: Int, level: Int): VideoAdaptationTarget = when (level.coerceIn(0, MAX_AUTO_LEVEL)) {
        0 -> VideoAdaptationTarget(12_000_000, baseFps, 1.0, 6_000_000.0)
        1 -> VideoAdaptationTarget(10_000_000, minOf(baseFps, 45), 1.0, 5_000_000.0)
        2 -> VideoAdaptationTarget(8_000_000, minOf(baseFps, 30), 1.0, 4_000_000.0)
        3 -> VideoAdaptationTarget(6_000_000, minOf(baseFps, 30), 4.0 / 3.0, 2_500_000.0)
        else -> VideoAdaptationTarget(3_500_000, minOf(baseFps, 30), 2.0, 1_500_000.0)
    }

    fun lowLatencyTarget(baseFps: Int, level: Int): VideoAdaptationTarget = when (level.coerceIn(0, 3)) {
        0 -> VideoAdaptationTarget(3_500_000, baseFps, 1.0, 3_000_000.0)
        1 -> VideoAdaptationTarget(2_500_000, baseFps, 1.0, 2_000_000.0)
        2 -> VideoAdaptationTarget(1_500_000, minOf(baseFps, 45), 4.0 / 3.0, 1_200_000.0)
        else -> VideoAdaptationTarget(800_000, minOf(baseFps, 30), 2.0, 650_000.0)
    }

    fun senderPressure(
        target: VideoAdaptationTarget,
        encodedFps: Double?,
        captureFps: Double?,
        averageEncodeTimeMs: Double?,
        qualityLimitationReason: String?,
        rttMs: Double?,
        jitterMs: Double?,
        recentLossPercent: Double?,
        availableSendBps: Double?
    ): VideoPressure {
        val reason = qualityLimitationReason?.lowercase()
        val severe = (recentLossPercent ?: 0.0) >= 8.0 || (rttMs ?: 0.0) >= 250.0 ||
            (jitterMs ?: 0.0) >= 60.0 || (availableSendBps != null && availableSendBps < 2_000_000.0)
        if (severe) return VideoPressure.SEVERE
        if (reason == "bandwidth" || (recentLossPercent ?: 0.0) >= 3.0 || (rttMs ?: 0.0) >= 150.0 ||
            (jitterMs ?: 0.0) >= 30.0 || (availableSendBps != null && availableSendBps < target.minimumHealthyBandwidthBps)
        ) return VideoPressure.BANDWIDTH

        val frameBudgetMs = 1_000.0 / target.maxFps.coerceAtLeast(1)
        val encoderTimePressure = averageEncodeTimeMs != null && averageEncodeTimeMs >= frameBudgetMs * 0.85
        val encoderFpsPressure = encodedFps != null && captureFps != null &&
            captureFps >= target.maxFps * 0.85 && encodedFps < target.maxFps * 0.72
        if (reason == "cpu" || encoderTimePressure || encoderFpsPressure) return VideoPressure.ENCODER

        val fpsHealthy = encodedFps == null || encodedFps >= target.maxFps * 0.82
        val encodeHealthy = averageEncodeTimeMs == null || averageEncodeTimeMs < frameBudgetMs * 0.70
        val networkHealthy = (recentLossPercent == null || recentLossPercent < 1.0) &&
            (rttMs == null || rttMs < 100.0) && (jitterMs == null || jitterMs < 20.0) &&
            (availableSendBps == null || availableSendBps >= target.minimumHealthyBandwidthBps)
        return if ((reason == null || reason == "none") && fpsHealthy && encodeHealthy && networkHealthy) {
            VideoPressure.HEALTHY
        } else {
            VideoPressure.UNKNOWN
        }
    }

    fun autoAction(
        level: Int,
        constrainedSamples: Int,
        severeSamples: Int,
        healthySamples: Int,
        millisecondsSinceChange: Long
    ): VideoAdaptationAction = when {
        level < 2 && constrainedSamples >= 3 && millisecondsSinceChange >= 15_000L -> VideoAdaptationAction.DEGRADE
        level in 2 until MAX_AUTO_LEVEL && severeSamples >= 6 && millisecondsSinceChange >= 30_000L -> VideoAdaptationAction.DEGRADE
        level > 0 && healthySamples >= 9 && millisecondsSinceChange >= 45_000L -> VideoAdaptationAction.RECOVER
        else -> VideoAdaptationAction.HOLD
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
