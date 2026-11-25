package com.lizongying.mytv

import org.junit.Assert.*
import org.junit.Test

class RouteQualityTest {
    private val now = 1_000_000_000L
    private fun sample(id: String = "sample", duration: Long = 30_000, buffer: Long = 0,
        errors: Int = 0, at: Long = now, frame: Long = 1000, height: Int = 1080) =
        RouteSample(id, at, duration, frame, 1920, height, if (buffer > 0) 1 else 0,
            buffer, if (buffer >= 3000) 1 else 0, errors, 0, "retuned", duration - buffer)
    private fun first(records: Map<String, List<RouteSample>>, time: Long = now) =
        RouteQuality.order(listOf("a", "b"), records, null, time).first()

    @Test fun fastStartCannotOutrankSmoothPlayback() {
        assertEquals("b", first(mapOf("a" to listOf(sample(buffer = 6000, frame = 100)), "b" to listOf(sample(frame = 2000)))))
    }
    @Test fun scanningDoesNotQualifyAsStable() {
        assertFalse(sample(duration = 2000).stable)
        assertEquals("b", first(mapOf("a" to listOf(sample(duration = 2000)), "b" to listOf(sample()))))
    }
    @Test fun aFailureCoolsDownAndCanRecover() {
        val history = mapOf("a" to listOf(sample()), "b" to listOf(sample("old"), sample(errors = 1)))
        assertEquals("a", first(history))
        val recovered = mapOf("b" to listOf(sample("old", errors = 1), sample("new", frame = 100)))
        assertEquals("b", first(recovered))
    }
    @Test fun expiredFailureReturnsToCatalogOrder() {
        assertEquals("a", first(mapOf("a" to listOf(sample(errors = 1))), now + RouteQuality.COOLDOWN + 1))
    }
    @Test fun removedAndExpiredRoutesDoNotParticipate() {
        assertEquals("a", first(mapOf("missing" to listOf(sample()), "b" to listOf(sample(at = now - RouteQuality.RETENTION - 1)))))
    }
    @Test fun equalSpeedPrefersResolutionAndRememberedBreaksTies() {
        assertEquals("b", first(mapOf("a" to listOf(sample(height = 720)), "b" to listOf(sample()))))
        assertEquals("b", RouteQuality.order(listOf("a", "b"), emptyMap(), "b", now).first())
    }
    @Test fun activeBufferAndErrorsSurviveRetuning() {
        val observation = RouteObservation("a", "channel", 0) { now }
        observation.rendered(100, 1920, 1080)
        observation.buffering(200, true)
        val partial = observation.snapshot(3300, "retuned")
        assertEquals(3100, partial.bufferMs)
        assertEquals(1, partial.severe)
        assertFalse(partial.stable)
        observation.errors++
        assertEquals(1, observation.snapshot(3400, "route_changed").errors)
    }
    @Test fun stableObservationRequiresThirtySeconds() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(100, 1280, 720)
        for (second in 0L..30L) observation.progress(100 + second * 1000, true, second * 1000)
        assertFalse(observation.snapshot(30_099, "observing").stable)
        assertTrue(observation.snapshot(30_100, "retuned").stable)
    }
}
