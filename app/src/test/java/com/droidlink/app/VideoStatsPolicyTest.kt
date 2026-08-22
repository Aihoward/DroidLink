package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStatsPolicyTest {
    @Test fun bitrateUsesByteDeltaAndMillisecondInterval() {
        assertEquals(3_200_000L, VideoStatsPolicy.bitrateBps(2_500_000L, 500_000L, 5_000L))
    }

    @Test fun counterResetIsUnavailableInsteadOfProducingAFalseSpike() {
        assertNull(VideoStatsPolicy.bitrateBps(100L, 500L, 5_000L))
        assertNull(VideoStatsPolicy.ratePerSecond(10L, 20L, 5_000L))
    }

    @Test fun jitterBufferUsesIntervalDeltasNotCumulativeAverage() {
        assertEquals(50.0, VideoStatsPolicy.intervalAverageMs(12.0, 10.0, 140L, 100L)!!, 0.001)
    }

    @Test fun jitterBufferRejectsMissingOrResetSamples() {
        assertNull(VideoStatsPolicy.intervalAverageMs(10.0, 12.0, 140L, 100L))
        assertNull(VideoStatsPolicy.intervalAverageMs(12.0, 10.0, 100L, 100L))
    }

    @Test fun jitterTrendUsesADeadband() {
        assertEquals("RISING", VideoStatsPolicy.trend(80.0, 60.0))
        assertEquals("FALLING", VideoStatsPolicy.trend(50.0, 70.0))
        assertEquals("STABLE", VideoStatsPolicy.trend(65.0, 60.0))
        assertEquals("WARMING UP", VideoStatsPolicy.trend(65.0, null))
    }

    @Test fun packetLossUsesRecentIntervalInsteadOfLifetimeAverage() {
        assertEquals(
            2.0,
            VideoStatsPolicy.intervalPacketLossPercent(
                currentLost = 12L,
                previousLost = 10L,
                currentSent = 1_100L,
                previousSent = 1_000L
            )!!,
            0.001
        )
    }

    @Test fun packetLossRejectsCounterResetsAndEmptyIntervals() {
        assertNull(VideoStatsPolicy.intervalPacketLossPercent(5L, 10L, 1_100L, 1_000L))
        assertNull(VideoStatsPolicy.intervalPacketLossPercent(10L, 10L, 1_000L, 1_000L))
    }

    @Test fun adaptationTargetsRestoreExactPresetCeilings() {
        val auto = VideoAdaptationPolicy.target("Auto", 60, 0)
        assertEquals(8_000_000, auto.maxBitrateBps)
        assertEquals(60, auto.maxFps)
        assertEquals(1.0, auto.scaleResolutionDownBy, 0.001)

        val lowLatency = VideoAdaptationPolicy.target("Low Latency", 60, 0)
        assertEquals(3_500_000, lowLatency.maxBitrateBps)
        assertEquals(1.0, lowLatency.scaleResolutionDownBy, 0.001)
    }

    @Test fun constrainedTargetsScaleOnlyTheIndividualSender() {
        val target = VideoAdaptationPolicy.target("Auto", 60, 2)
        assertEquals(1_500_000, target.maxBitrateBps)
        assertEquals(45, target.maxFps)
        assertEquals(4.0 / 3.0, target.scaleResolutionDownBy, 0.001)
    }

    @Test fun lowLatencyDegradesQuicklyWhileAutoRequiresConfirmation() {
        assertEquals(VideoAdaptationAction.DEGRADE, VideoAdaptationPolicy.action("Low Latency", 0, 1, 0, 5_000L))
        assertEquals(VideoAdaptationAction.HOLD, VideoAdaptationPolicy.action("Auto", 0, 1, 0, 10_000L))
        assertEquals(VideoAdaptationAction.DEGRADE, VideoAdaptationPolicy.action("Auto", 0, 2, 0, 10_000L))
    }

    @Test fun recoveryRequiresThreeHealthySamplesAndRestoresOneLevelAtATime() {
        assertEquals(VideoAdaptationAction.HOLD, VideoAdaptationPolicy.action("Auto", 2, 0, 2, 15_000L))
        assertEquals(VideoAdaptationAction.RECOVER, VideoAdaptationPolicy.action("Auto", 2, 0, 3, 15_000L))
        assertEquals(3_000_000.0, VideoAdaptationPolicy.recoveryBandwidthBps("Auto", 60, 1), 0.001)
    }
}
