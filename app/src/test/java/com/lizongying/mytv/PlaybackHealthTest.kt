package com.lizongying.mytv

import com.lizongying.mytv.PlaybackHealth.Decision.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHealthTest {
    private val health = PlaybackHealth(0)
    @Test fun requestTimeCountsTowardStartup() {
        assertEquals(WAIT, health.sample(7999, false, 0))
        assertEquals(SLOW, health.sample(8000, false, 0))
    }

    @Test fun threeStallsAreCountedWithinRollingWindow() {
        health.rendered()
        for (start in listOf(100L, 5000L, 10000L)) {
            health.buffering(start, true)
            health.buffering(start + 2000, false)
        }
        assertEquals(SLOW, health.sample(12000, true, 200))
        assertEquals(WAIT, health.sample(35000, true, 300))
    }

    @Test fun stationaryPositionAndBriefBufferResetQualification() {
        health.rendered()
        health.sample(0, true, 0)
        assertEquals(WAIT, health.sample(19000, true, 0))
        health.sample(20000, true, 1000)
        health.buffering(30000, true)
        health.buffering(30100, false)
        assertEquals(WAIT, health.sample(40000, true, 20000))
        assertEquals(STABLE, health.sample(60000, true, 40000))
        assertEquals(WAIT, health.sample(60500, true, 40500))
    }
}
