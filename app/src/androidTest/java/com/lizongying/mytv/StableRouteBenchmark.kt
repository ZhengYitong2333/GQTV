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

/** Opt-in paired comparison. Each arm owns its own persisted history. */
class StableRouteBenchmark {
    private data class History(val records: Map<String, List<RouteSample>>, val remembered: String?)

    @Test fun measure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("policy") ?: return
        assumeTrue(phase in listOf("compare", "long"))
        val number = args.getString("channel")!!.toInt()
        val channel = TVList.list.values.flatten()[number - 1]
        val saved = SP.routeRecords(channel.pid)
        val memory = SP.lastBackup(channel.pid)
        val position = SP.itemPosition
        val state = File(instrumentation.targetContext.filesDir, "quality-bench-$number.json")
        var scenario: ActivityScenario<MainActivity>? = null
        val histories = mutableMapOf("old" to History(emptyMap(), null), "new" to History(emptyMap(), null))
        try {
            if (phase == "long" && state.exists()) {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, List<RouteSample>>>() {}.type
                histories["long"] = History(com.google.gson.Gson().fromJson(state.readText(), type), null)
            } else histories["long"] = History(emptyMap(), null)
            for (policy in if (phase == "long") listOf("long") else listOf("old", "new")) {
                Log.i("QualityBench", "baseline batch=$number-$policy ids=" + histories.getValue(policy).records.values.flatten().joinToString(",") { it.id })
            }
            repeat(if (phase == "long") 1 else 5) { sample ->
                val policies = if (phase == "long") listOf("long") else
                    if ((sample + number) % 2 == 0) listOf("new", "old") else listOf("old", "new")
                for (policy in policies) {
                    val history = histories.getValue(policy)
                    val mode = if (policy == "long") "long" else if (sample == 0) "cold" else "remembered"
                    val batch = "$number-$policy"
                    Log.i("QualityBench", "begin batch=$batch channel=CCTV$number mode=$mode sample=$sample")
                    val start = SystemClock.elapsedRealtime()
                    if (scenario == null) {
                        RouteQuality.enabled = policy != "old"
                        SP.writeRouteRecords(channel.pid, history.records)
                        SP.rememberBackup(channel.pid, history.remembered)
                        SP.itemPosition = number - 1
                        scenario = ActivityScenario.launch(MainActivity::class.java)
                    } else scenario!!.onActivity {
                        RouteQuality.enabled = policy != "old"
                        SP.writeRouteRecords(channel.pid, history.records)
                        SP.rememberBackup(channel.pid, history.remembered)
                        it.play(number - 1)
                    }
                    var shown = false
                    while (!shown && SystemClock.elapsedRealtime() - start < 25_000) {
                        scenario!!.onActivity { host -> shown = host.findViewById<View>(R.id.tune_cover)?.visibility == View.GONE }
                        if (!shown) SystemClock.sleep(50)
                    }
                    val wait = SystemClock.elapsedRealtime() - start
                    if (shown) {
                        saveScreenshot("quality-$number-$policy-$sample.png")
                        if (policy == "long") {
                            var previousCounters: Any? = null
                            var previousRendered = 0
                            var previousDropped = 0
                            var renderedTotal = 0
                            var droppedTotal = 0
                            var lastFrameAt: Long? = null
                            var maxFrameGap = 0L
                            val until = SystemClock.elapsedRealtime() + 300_600
                            while (SystemClock.elapsedRealtime() < until) {
                                scenario!!.onActivity { host ->
                                    val engine = host.findViewById<androidx.media3.ui.PlayerView>(R.id.player_view).player as androidx.media3.exoplayer.ExoPlayer
                                    val counters = engine.videoDecoderCounters
                                    counters?.ensureUpdated()
                                    val rendered = counters?.renderedOutputBufferCount ?: 0
                                    val dropped = counters?.droppedBufferCount ?: 0
                                    if (counters !== previousCounters || rendered < previousRendered) {
                                        previousRendered = 0
                                        previousDropped = 0
                                    }
                                    val added = (rendered - previousRendered).coerceAtLeast(0)
                                    renderedTotal += added
                                    droppedTotal += (dropped - previousDropped).coerceAtLeast(0)
                                    val now = SystemClock.elapsedRealtime()
                                    if (engine.isPlaying) {
                                        lastFrameAt?.let { maxFrameGap = maxOf(maxFrameGap, now - it) }
                                        if (added > 0 || lastFrameAt == null) lastFrameAt = now
                                    } else lastFrameAt = null
                                    previousCounters = counters
                                    previousRendered = rendered
                                    previousDropped = dropped
                                }
                                SystemClock.sleep(500)
                            }
                            Log.i("QualityBench", "video batch=$batch sample=$sample rendered=$renderedTotal dropped=$droppedTotal maxFrameGapMs=$maxFrameGap")
                            saveScreenshot("quality-$number-long-end.png")
                        } else SystemClock.sleep(30_600)
                    }
                    scenario!!.onActivity { host ->
                        host.playbackSettings().finishQualityObservation()
                        val records = SP.routeRecords(channel.pid)
                        records.forEach { (route, observations) ->
                            val line = if (route == RouteQuality.PRIMARY) "primary" else "backup-${backupSource(channel.alias).orEmpty().indexOf(route) + 1}"
                            observations.forEach { record -> Log.i("RouteRecord", "batch=$batch sample=$sample route=$line data=" + com.google.gson.Gson().toJson(record)) }
                        }
                        histories[policy] = History(records, SP.lastBackup(channel.pid))
                    }
                    Log.i("QualityBench", "sample batch=$batch channel=CCTV$number mode=$mode sample=$sample shown=$shown waitMs=$wait")
                    // Both no-history observations start with a fresh activity/player.
                    if (sample == 0 && phase == "compare" && policy == policies.first()) {
                        scenario?.close()
                        scenario = null
                    }
                }
            }
            scenario?.close()
            scenario = null
            val learned = histories.getValue(if (phase == "compare") "new" else "long").records
            state.writeText(com.google.gson.Gson().toJson(learned))
        } finally {
            scenario?.close()
            RouteQuality.enabled = true
            SP.writeRouteRecords(channel.pid, saved)
            SP.rememberBackup(channel.pid, memory)
            SP.itemPosition = position
            SP.flushRouteRecords()
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
