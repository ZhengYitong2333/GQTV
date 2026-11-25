package com.lizongying.mytv

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lizongying.mytv.models.TVList
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, isolated live comparison of the pre-change memory policy and quality learning. */
class StableRouteBenchmark {
    @Test fun measure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val policy = args.getString("policy") ?: return
        assumeTrue(policy in listOf("old", "new", "long"))
        val number = args.getString("channel")!!.toInt()
        val channel = TVList.list.values.flatten()[number - 1]
        val saved = SP.routeRecords(channel.pid)
        val memory = SP.lastBackup(channel.pid)
        val position = SP.itemPosition
        val state = File(instrumentation.targetContext.filesDir, "quality-bench-$number.json")
        var scenario: ActivityScenario<MainActivity>? = null
        val batch = "$number-$policy"
        try {
            RouteQuality.enabled = policy != "old"
            SP.rememberBackup(channel.pid, null)
            SP.writeRouteRecords(channel.pid, emptyMap())
            if (policy == "long" && state.exists()) {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, List<RouteSample>>>() {}.type
                SP.writeRouteRecords(channel.pid, com.google.gson.Gson().fromJson(state.readText(), type))
            }
            Log.i("QualityBench", "baseline batch=$batch ids=" + SP.routeRecords(channel.pid).values.flatten().joinToString(",") { it.id })
            repeat(if (policy == "long") 1 else 5) { sample ->
                val mode = if (policy == "long") "long" else if (sample == 0) "cold" else "remembered"
                Log.i("QualityBench", "begin batch=$batch channel=CCTV$number mode=$mode sample=$sample")
                val start = SystemClock.elapsedRealtime()
                if (scenario == null) {
                    SP.itemPosition = number - 1
                    scenario = ActivityScenario.launch(MainActivity::class.java)
                } else scenario!!.onActivity { it.play(number - 1) }
                var shown = false
                while (!shown && SystemClock.elapsedRealtime() - start < 25_000) {
                    scenario!!.onActivity { host ->
                        shown = host.findViewById<View>(R.id.tune_cover)?.visibility == View.GONE
                    }
                    if (!shown) SystemClock.sleep(50)
                }
                val wait = SystemClock.elapsedRealtime() - start
                val observe = if (policy == "long") 300_600L else 30_600L
                if (shown) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(instrumentation.targetContext.filesDir, "quality-$number-$policy-$sample.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    SystemClock.sleep(observe)
                }
                // Stop flushes the active observation without changing channel history.
                scenario!!.onActivity { host ->
                    val player = host.playbackSettings()
                    player.checkpointQuality()
                    val records = SP.routeRecords(channel.pid)
                    records.forEach { (route, observations) ->
                        val line = if (route == RouteQuality.PRIMARY) "primary" else "backup-${backupSource(channel.alias).orEmpty().indexOf(route) + 1}"
                        observations.forEach { record -> Log.i("RouteRecord", "batch=$batch sample=$sample route=$line data=" + com.google.gson.Gson().toJson(record)) }
                    }
                }
                Log.i("QualityBench", "sample batch=$batch channel=CCTV$number mode=$mode sample=$sample shown=$shown waitMs=$wait")
            }
            scenario?.close()
            scenario = null
            if (policy == "new") state.writeText(com.google.gson.Gson().toJson(SP.routeRecords(channel.pid)))
        } finally {
            scenario?.close()
            RouteQuality.enabled = true
            SP.writeRouteRecords(channel.pid, saved)
            SP.rememberBackup(channel.pid, memory)
            SP.itemPosition = position
        }
    }
}
