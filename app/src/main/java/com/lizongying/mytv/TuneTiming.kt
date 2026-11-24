package com.lizongying.mytv

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Owned by the player on its application thread. All times use the elapsed-realtime clock. */
internal class TuneTiming(
    private val channel: String,
    cold: Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i("TuneTiming", it) }
) {
    val id = sequence.incrementAndGet()
    private val start = clock()
    private var attempt = 0
    private var route = "primary"
    private var firstLoad: Long? = null
    private var firstFrame: Long? = null
    private var stallStart: Long? = null
    private var stallCount = 0
    private var stallMs = 0L
    private var lastIssue: String? = null
    private var summarized = false

    init { event("start", "mode=" + if (cold) "cold" else "warm") }

    fun attempt(name: String) {
        lastIssue = null
        route = name
        attempt++
        firstLoad = null
        event("attempt")
    }

    val mediaId get() = "$id/$attempt"

    fun segment(task: Long, completed: Boolean) {
        if (!completed && firstLoad == null) {
            firstLoad = task
            event("segment_start")
        } else if (completed && task == firstLoad) event("segment_end")
    }

    fun frame(width: Int, height: Int): Boolean {
        if (firstFrame == null) {
            firstFrame = clock()
            event("first_frame", "width=$width height=$height")
            return true
        }
        event("route_frame", "width=$width height=$height")
        return false
    }

    fun buffering(active: Boolean, now: Long = clock()) {
        val frame = firstFrame ?: return
        if (summarized) return
        val capped = minOf(now, frame + 30_000)
        if (active && stallStart == null && now < frame + 30_000) {
            stallStart = now
            stallCount++
        }
        if (!active || capped == frame + 30_000) {
            stallStart?.let { stallMs += capped - it }
            stallStart = null
        }
        if (now >= frame + 30_000) summarize(30_000)
    }

    fun finish(reason: String) {
        buffering(false)
        firstFrame?.let { if (!summarized) summarize((clock() - it).coerceAtMost(30_000)) }
        val result = if (firstFrame != null) "displayed" else lastIssue ?: "abandoned"
        event("end", "reason=$reason result=$result displayed=${firstFrame != null}")
    }

    private fun summarize(duration: Long) {
        summarized = true
        event("quality", "observedMs=$duration buffers=$stallCount bufferMs=$stallMs")
    }

    fun event(name: String, details: String = "") {
        if (name == "timeout" || name == "playback_error" || name == "source_error") lastIssue = name
        log("id=$id channel=$channel route=$route attempt=$attempt event=$name ms=${clock() - start} $details")
    }

    companion object { private val sequence = AtomicLong() }
}
