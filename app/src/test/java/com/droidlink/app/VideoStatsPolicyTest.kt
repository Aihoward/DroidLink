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
}
