package com.lizongying.mytv

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Invoke write and read in separate instrumentation processes. */
class RouteRestartTest {
    @Test fun persistedQuality() {
        val stage = InstrumentationRegistry.getArguments().getString("stage") ?: return
        val pid = "quality-process-fixture"
        if (stage == "write") {
            val sample = RouteSample("persisted", System.currentTimeMillis(), 30000, 1000,
                1920, 1080, 0, 0, 0, 0, 0, Process.myPid().toString(), 29000)
            SP.writeRouteRecords(pid, mapOf("second" to listOf(sample)))
            assertTrue(SP.flushRouteRecords())
        } else {
            val records = SP.routeRecords(pid)
            val sample = records.getValue("second").single()
            assertNotEquals(Process.myPid().toString(), sample.ended)
            assertTrue(sample.stable)
            assertEquals("second", RouteQuality.order(listOf("first", "second"), records, null, System.currentTimeMillis()).first())
            SP.writeRouteRecords(pid, emptyMap())
        }
    }
}
