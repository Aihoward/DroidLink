package com.droidlink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun intervalPacketLossUsesRecentDeltas() {
        assertEquals(2.0, VideoStatsPolicy.intervalPacketLossPercent(12L, 10L, 198L, 100L)!!, 0.001)
        assertEquals(2.0, VideoStatsPolicy.intervalPacketLossPercent(12L, 10L, 200L, 100L, packetCountIncludesLost = true)!!, 0.001)
    }

    @Test fun matchingLowReceiveAndDecodeFpsIsNotDecoderLimited() {
        assertFalse(VideoStatsPolicy.decoderLimited(21.4, 21.2, 4.0, 0L))
    }

    @Test fun sustainedDecodeThroughputGapWithDropsIsDecoderLimited() {
        assertTrue(VideoStatsPolicy.decoderLimited(60.0, 35.0, 5.0, 40L))
    }

    @Test fun autoTargetsReduceFpsBeforeResolution() {
        assertEquals(VideoAdaptationTarget(12_000_000, 60, 1.0, 6_000_000.0), VideoQualityPolicy.autoTarget(60, 0))
        assertEquals(VideoAdaptationTarget(10_000_000, 45, 1.0, 5_000_000.0), VideoQualityPolicy.autoTarget(60, 1))
        assertEquals(VideoAdaptationTarget(8_000_000, 30, 1.0, 4_000_000.0), VideoQualityPolicy.autoTarget(60, 2))
        assertEquals(4.0 / 3.0, VideoQualityPolicy.autoTarget(60, 3).scaleResolutionDownBy, 0.001)
    }

    @Test fun temporaryPressureDoesNotChangeQuality() {
        assertEquals(VideoAdaptationAction.HOLD, VideoQualityPolicy.autoAction(0, 2, 0, 0, 20_000L))
        assertEquals(VideoAdaptationAction.DEGRADE, VideoQualityPolicy.autoAction(0, 3, 0, 0, 20_000L))
    }

    @Test fun resolutionReductionRequiresSustainedSeverePressure() {
        assertEquals(VideoAdaptationAction.HOLD, VideoQualityPolicy.autoAction(2, 20, 5, 0, 60_000L))
        assertEquals(VideoAdaptationAction.DEGRADE, VideoQualityPolicy.autoAction(2, 20, 6, 0, 60_000L))
    }

    @Test fun qualityRecoversAfterSustainedHealthyWindow() {
        assertEquals(VideoAdaptationAction.HOLD, VideoQualityPolicy.autoAction(3, 0, 0, 8, 60_000L))
        assertEquals(VideoAdaptationAction.RECOVER, VideoQualityPolicy.autoAction(3, 0, 0, 9, 60_000L))
    }

    @Test fun reportedHealthy720p30ConditionsDoNotTriggerAnotherDrop() {
        val pressure = VideoQualityPolicy.senderPressure(
            target = VideoQualityPolicy.autoTarget(60, 2),
            encodedFps = 27.2,
            captureFps = 60.0,
            averageEncodeTimeMs = 5.0,
            qualityLimitationReason = "none",
            rttMs = 26.0,
            jitterMs = 2.0,
            recentLossPercent = 0.0,
            availableSendBps = 7_500_000.0
        )
        assertEquals(VideoPressure.HEALTHY, pressure)
    }
}
