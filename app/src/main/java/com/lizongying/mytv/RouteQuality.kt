package com.lizongying.mytv

/** A bounded foreground observation, updated in place while the same attempt is playing. */
internal data class RouteSample(
    val id: String,
    val at: Long,
    val observedMs: Long,
    val frameMs: Long?,
    val width: Int,
    val height: Int,
    val buffers: Int,
    val bufferMs: Long,
    val severe: Int,
    val errors: Int,
    val timeouts: Int,
    val ended: String,
    val playedMs: Long = 0,
    val avcCompat: Boolean = false,
    val softwareVideo: Boolean = false,
    val unsupportedAudio: Boolean = false
) {
    val stable get() = frameMs != null && observedMs >= 30_000 && playedMs >= 20_000 && playedMs + bufferMs >= observedMs - 1_500 && errors == 0 &&
        timeouts == 0 && severe == 0 && bufferMs <= 3_000
    val failed get() = errors > 0 || timeouts > 0 || severe > 0
}

internal object RouteQuality {
    const val PRIMARY = "primary"
    const val RETENTION = 7 * 24 * 60 * 60 * 1000L
    const val COOLDOWN = 10 * 60 * 1000L
    // The old-policy switch is only set by opt-in instrumentation.
    var enabled = true

    fun recent(samples: List<RouteSample>, now: Long) = samples
        .filter { it.at <= now && now - it.at <= RETENTION }.takeLast(8)

    fun retain(samples: List<RouteSample>, now: Long): List<RouteSample> {
        val valid = samples.filter { it.at <= now && now - it.at <= RETENTION }
        val (meaningful, brief) = valid.partition { it.observedMs >= 30_000 || it.failed }
        val scans = brief.takeLast(2)
        return (meaningful.takeLast(8 - scans.size) + scans).sortedBy { it.at }
    }

    fun order(routes: List<String>, records: Map<String, List<RouteSample>>, remembered: String?, now: Long): List<String> {
        fun samples(route: String) = recent(records[route].orEmpty(), now)
        fun cooling(route: String): Boolean {
            val last = samples(route).lastOrNull { it.failed || it.stable } ?: return false
            val failures = samples(route).asReversed().takeWhile { !it.stable }.count { it.failed }
            val delay = when {
                last.unsupportedAudio || failures >= 3 -> COOLDOWN
                failures == 2 -> 60_000L
                else -> 10_000L
            }
            return last.failed && now - last.at < delay
        }
        fun category(route: String): Int {
            val history = samples(route)
            if (cooling(route)) return 3
            if (history.any { it.stable }) return 0
            return 1 // Expired penalties can be retried in catalog order.
        }
        fun errorRate(route: String): Int {
            if (category(route) == 1) return 0
            val history = samples(route).filter { it.failed || it.stable }
            if (history.isEmpty()) return 0
            return history.count { it.failed } * 4 / history.size
        }
        fun bufferRate(route: String): Long {
            val history = samples(route).filter { it.observedMs >= 30_000 }
            val observed = history.sumOf { it.observedMs }
            if (observed == 0L) return 0
            return history.sumOf { it.bufferMs } * 100 / observed
        }
        fun frameBucket(route: String): Long {
            val frames = samples(route).filter { it.stable }.mapNotNull { it.frameMs }.sorted()
            return if (frames.isEmpty()) Long.MAX_VALUE else frames[frames.size / 2] / 500
        }
        return routes.sortedWith(compareBy<String> { category(it) }
            .thenBy { errorRate(it) }.thenBy { bufferRate(it) }
            .thenBy { frameBucket(it) }
            .thenByDescending { samples(it).lastOrNull { s -> s.stable }?.height ?: 0 }
            .thenBy { if (it == remembered) 0 else 1 }
            .thenBy { routes.indexOf(it) })
    }
}

/** Uses elapsed time for durations and wall time only for persistence/expiry. */
internal class RouteObservation(val route: String, val pid: String, private val started: Long,
    private val avcCompat: Boolean = false, private val softwareVideo: Boolean = false,
    private val wall: () -> Long = System::currentTimeMillis) {
    private val id = java.util.UUID.randomUUID().toString()
    private var frame: Long? = null
    private var buffering: Long? = null
    private var total = 0L
    private var count = 0
    private var severe = 0
    var errors = 0
    var timeouts = 0
    var unsupportedAudio = false
    private var playedMs = 0L
    private var lastTick: Long? = null
    private var lastPosition: Long? = null
    private var width = 0
    private var height = 0

    fun rendered(now: Long, width: Int, height: Int) {
        if (frame == null) frame = now
        this.width = width
        this.height = height
    }

    fun progress(now: Long, playing: Boolean, position: Long) {
        val previous = lastPosition
        val tick = lastTick
        if (frame != null && playing && previous != null && tick != null && position > previous) {
            playedMs += (now - tick).coerceIn(0, 1_000L)
        }
        lastTick = now
        lastPosition = position
    }

    fun videoSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun buffering(now: Long, active: Boolean) {
        if (frame == null) return
        if (active && buffering == null) { buffering = now; count++ }
        if (!active) buffering?.let {
            val elapsed = now - it
            total += elapsed
            if (elapsed >= 3_000) severe++
            buffering = null
        }
    }

    fun snapshot(now: Long, reason: String): RouteSample {
        val current = buffering?.let { now - it } ?: 0
        return RouteSample(id, wall(), frame?.let { now - it } ?: 0, frame?.minus(started),
            width, height, count, total + current, severe + if (current >= 3_000) 1 else 0,
            errors, timeouts, reason, playedMs, avcCompat, softwareVideo, unsupportedAudio)
    }
}
