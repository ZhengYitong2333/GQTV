package com.lizongying.mytv

/** Monotonic times are scoped to a single foreground playback attempt. */
internal class PlaybackHealth(private val started: Long) {
    enum class Decision { WAIT, STABLE, SLOW }

    private var qualified = false
    private var firstFrame = false
    private var stableSince: Long? = null
    private var previousPosition: Long? = null
    private val buffers = mutableListOf<LongRange>()
    private var bufferingSince: Long? = null

    fun rendered() { firstFrame = true }

    fun buffering(now: Long, active: Boolean) {
        if (!firstFrame) return
        if (active) {
            if (bufferingSince == null) bufferingSince = now
            stableSince = null
        } else {
            bufferingSince?.let { buffers.add(it..now) }
            bufferingSince = null
        }
    }

    fun sample(now: Long, playing: Boolean, position: Long): Decision {
        if (!firstFrame) return if (now - started >= 8_000) Decision.SLOW else Decision.WAIT
        val cutoff = now - 30_000
        buffers.removeAll { it.last <= cutoff }
        val current = bufferingSince?.let { now - it } ?: 0L
        val count = buffers.size + if (bufferingSince != null) 1 else 0
        val total = current + buffers.sumOf { it.last - maxOf(it.first, cutoff) }
        if (current >= 6_000 || buffers.any { it.last - it.first >= 6_000 } || count >= 3 && total >= 6_000) return Decision.SLOW
        val previous = previousPosition
        previousPosition = position
        if (!playing || bufferingSince != null || previous != null && position <= previous) {
            stableSince = null
            return Decision.WAIT
        }
        val since = stableSince ?: now.also { stableSince = it }
        if (!qualified && now - since >= 20_000) {
            qualified = true
            return Decision.STABLE
        }
        return Decision.WAIT
    }
}
