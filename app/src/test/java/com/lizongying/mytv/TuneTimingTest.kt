package com.lizongying.mytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneTimingTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val timing = TuneTiming("CCTV8", false, { now }, { lines.add(it) })

    @Test fun keepsOneQualityWindowAcrossRouteChanges() {
        timing.attempt("primary")
        now = 1000
        timing.frame(1280, 720)
        now = 1500
        timing.buffering(true)
        now = 2200
        timing.buffering(false)
        timing.attempt("backup-1")
        timing.frame(1920, 1080)
        now = 30_500
        timing.buffering(true)
        now = 33_000
        timing.finish("retuned")
        assertEquals(1, lines.count { "event=first_frame " in it })
        assertEquals(1, lines.count { "event=quality " in it })
        assertTrue(lines.any { "observedMs=30000 buffers=2 bufferMs=1200" in it })
    }

    @Test fun abandonmentAndPlaybackFailureHaveDistinctResults() {
        timing.attempt("primary")
        timing.event("timeout")
        timing.finish("retuned")
        assertTrue(lines.last().contains("result=timeout"))
        timing.attempt("backup-1")
        timing.finish("retuned")
        assertTrue(lines.last().contains("result=abandoned"))
        timing.event("playback_error")
        timing.finish("stopped")
        assertTrue(lines.last().contains("result=playback_error"))
    }
}
