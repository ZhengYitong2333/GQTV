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
    @Test fun oneBlipRecoversQuicklyWhileRepeatedFailuresBackOff() {
        val once = mapOf("a" to listOf(sample(errors = 1)))
        assertEquals("b", first(once, now + 9_999))
        assertEquals("a", first(once, now + 10_001))
        val twice = mapOf("a" to listOf(sample("one", errors = 1), sample("two", errors = 1)))
        assertEquals("b", first(twice, now + 10_001))
        assertEquals("a", first(twice, now + 60_001))
    }

    @Test fun unsupportedAudioKeepsItsPenaltyBeyondATransientBlip() {
        val records = mapOf("a" to listOf(sample(errors = 1).copy(unsupportedAudio = true)))
        assertEquals("b", first(records, now + 10_001))
        assertEquals("a", first(records, now + RouteQuality.COOLDOWN + 1))
    }

    @Test fun removedAndExpiredRoutesDoNotParticipate() {
        assertEquals("a", first(mapOf("missing" to listOf(sample()), "b" to listOf(sample(at = now - RouteQuality.RETENTION - 1)))))
    }
    @Test fun equalSpeedPrefersResolutionAndRememberedBreaksTies() {
        assertEquals("b", first(mapOf("a" to listOf(sample(height = 720)), "b" to listOf(sample()))))
        assertEquals("b", RouteQuality.order(listOf("a", "b"), emptyMap(), "b", now).first())
    }
    @Test fun equallySmoothRoutesPreferActualResolutionBeforeStartupSpeed() {
        assertEquals("b", first(mapOf(
            "a" to listOf(sample(frame = 500, height = 720)),
            "b" to listOf(sample(frame = 2500, height = 1080)))))
    }
    @Test fun verifiedUnchangedChannelsKeepTheirSelectedRoutes() {
        val root = javaClass.getResourceAsStream("/verified-route-history-20261125.json")!!.reader().use {
            com.google.gson.JsonParser.parseReader(it).asJsonObject
        }
        val type = object : com.google.gson.reflect.TypeToken<Map<String, List<RouteSample>>>() {}.type
        root.entrySet().forEach { (alias, fixture) ->
            val row = fixture.asJsonObject
            val records: Map<String, List<RouteSample>> = com.google.gson.Gson().fromJson(row["records"], type)
            val at = records.values.flatten().maxOf { it.at }
            assertEquals(alias, row["expected"].asString,
                RouteQuality.order(backupSource(alias)!!, records, null, at).first())
        }
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
    @Test fun repeatedScansCannotDisplaceAStableObservation() {
        val good = sample("stable", at = now - 1000)
        val scans = (1..20).map { sample("scan-$it", duration = 500, at = now - 1000 + it) }
        val retained = scans.fold(listOf(good)) { records, scan -> RouteQuality.retain(records + scan, now) }
        assertTrue(retained.any { it.id == "stable" })
        assertEquals(3, retained.size)
    }
    @Test fun elapsedIdleTimeCannotQualifyAnEndedOrFrozenStream() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(100, 1280, 720)
        for (second in 0L..20L) observation.progress(100 + second * 1000, true, second * 1000)
        observation.progress(30_100, false, 20_000)
        assertFalse(observation.snapshot(30_100, "stopped").stable)
    }
    @Test fun renderedProgressDoesNotDependOnTheSlidingLivePosition() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(0, 1280, 720)
        for (tick in 0L..60L) observation.progress(tick * 500, true, tick * 12)
        assertEquals(30_000, observation.snapshot(30_000, "observing").playedMs)
        assertTrue(observation.snapshot(30_000, "observing").stable)
    }

    @Test fun aPlayingClockWithoutNewRenderedFramesCannotQualify() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(0, 1280, 720)
        for (tick in 0L..60L) observation.progress(tick * 500, true, 1)
        assertEquals(0, observation.snapshot(30_000, "observing").playedMs)
        assertFalse(observation.snapshot(30_000, "observing").stable)
    }

    @Test fun decoderCounterResetAndLateSamplesDoNotInventPlayback() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(0, 1280, 720)
        observation.progress(0, true, 100)
        observation.progress(500, true, 0)
        observation.progress(30_000, true, 1)
        assertEquals(1000, observation.snapshot(30_000, "observing").playedMs)
        assertFalse(observation.snapshot(30_000, "observing").stable)
    }

    @Test fun decodedFramesBeforeTheVisibleFrameAreNotPlaybackTime() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.progress(0, true, 1)
        observation.rendered(900, 1280, 720)
        observation.progress(1000, true, 25)
        assertEquals(0, observation.snapshot(1000, "observing").playedMs)
        observation.progress(1500, true, 38)
        assertEquals(500, observation.snapshot(1500, "observing").playedMs)
    }

    @Test fun stableObservationRequiresThirtySeconds() {
        val observation = RouteObservation("b", "channel", 0) { now }
        observation.rendered(100, 1280, 720)
        for (second in 0L..30L) observation.progress(100 + second * 1000, true, second * 1000)
        assertFalse(observation.snapshot(30_099, "observing").stable)
        assertTrue(observation.snapshot(30_100, "retuned").stable)
    }
}
