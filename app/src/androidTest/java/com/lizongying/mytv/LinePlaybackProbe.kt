package com.lizongying.mytv

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lizongying.mytv.models.TVList
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in single-route probe; captures decoder/ready ordering and persistent buffering. */
class LinePlaybackProbe {
    @Test fun measure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val number = args.getString("channel")?.toIntOrNull()
        assumeTrue(number != null)
        val line = args.getString("line")!!.toInt()
        val compatible = args.getString("compat") == "true"
        val software = args.getString("software") == "true"
        var isolatedEngine: androidx.media3.exoplayer.ExoPlayer? = null
        var originalEngine: androidx.media3.exoplayer.ExoPlayer? = null
        val seconds = args.getString("seconds")?.toLong() ?: 35L
        val channel = TVList.list.values.flatten()[number!! - 1]
        val uri = args.getString("uri") ?: backupSource(channel.alias)!![line - 1]
        val originalBackupOnly = channel.backupOnly
        val position = SP.itemPosition
        val originalRecords = SP.routeRecords(channel.pid)
        val originalMemory = SP.lastBackup(channel.pid)
        var observation: RouteObservation? = null
        var frameAt: Long? = null
        var previousRendered = -1
        var lastRenderedAt: Long? = null
        var maxFrameGap = 0L
        val start = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.CREATED) {
                activity.playbackSettings().lookupBackup = { listOf(uri) }
            }
        }
        try {
            RouteQuality.enabled = false
            SP.writeRouteRecords(channel.pid, emptyMap())
            SP.rememberBackup(channel.pid, null)
            SP.itemPosition = number - 1
            channel.backupOnly = true
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { host ->
                val original = host.findViewById<PlayerView>(R.id.player_view).player!! as androidx.media3.exoplayer.ExoPlayer
                check(original.currentMediaItem?.localConfiguration?.uri.toString() == uri) { "Probe did not select its requested URI" }
                originalEngine = original
                original.removeListener(host.playbackSettings())
                val item = original.currentMediaItem!!
                val engine = if (software) {
                    original.stop()
                    val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(host).setMediaCodecSelector { mime, secure, tunnel ->
                        val codecs = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
                        val selected = if (mime == androidx.media3.common.MimeTypes.VIDEO_H264) codecs.filter { it.softwareOnly } else codecs
                        Log.i("LineProbe", "softwareCodecs mime=$mime names=" + selected.joinToString(",") { it.name })
                        selected
                    }
                    androidx.media3.exoplayer.ExoPlayer.Builder(host, renderers).build().also {
                        isolatedEngine = it
                        host.findViewById<PlayerView>(R.id.player_view).player = it
                        it.playWhenReady = true
                    }
                } else original
                val measured = RouteObservation("probe", channel.pid, SystemClock.elapsedRealtime(), compatible, software)
                observation = measured
                engine.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (frameAt == null) {
                            frameAt = SystemClock.elapsedRealtime()
                            measured.rendered(frameAt!!, engine.videoSize.width, engine.videoSize.height)
                            if (software) host.findViewById<android.view.View>(R.id.tune_cover).visibility = android.view.View.GONE
                            Log.i("LineProbe", "frame channel=$number line=$line compat=$compatible software=$software ms=${frameAt!!-start} state=${engine.playbackState} playing=${engine.isPlaying}")
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        measured.buffering(SystemClock.elapsedRealtime(), state == Player.STATE_BUFFERING)
                        Log.i("LineProbe", "state channel=$number line=$line compat=$compatible software=$software ms=${SystemClock.elapsedRealtime()-start} state=$state playing=${engine.isPlaying}")
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        measured.errors++
                        Log.i("LineProbe", "error channel=$number line=$line compat=$compatible software=$software code=${error.errorCode} ms=${SystemClock.elapsedRealtime()-start}")
                    }
                })
                if (compatible || software) {
                    engine.stop()
                    engine.setMediaSource(HlsMediaSource.Factory(DefaultDataSource.Factory(host))
                        .setExtractorFactory(ContinuousHlsExtractorFactory()).createMediaSource(item))
                    engine.prepare()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000
            while (SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { host ->
                    val engine = host.findViewById<PlayerView>(R.id.player_view).player!! as androidx.media3.exoplayer.ExoPlayer
                    val now = SystemClock.elapsedRealtime()
                    val counters = engine.videoDecoderCounters
                    counters?.ensureUpdated()
                    val rendered = counters?.renderedOutputBufferCount ?: 0
                    if (engine.isPlaying && frameAt != null) {
                        if (lastRenderedAt == null || rendered != previousRendered) lastRenderedAt = now
                        maxFrameGap = maxOf(maxFrameGap, now - lastRenderedAt!!)
                    } else lastRenderedAt = null
                    previousRendered = rendered
                    if (args.getString("progress") == "true") Log.i("LineProbe", "progress ms=${now-start} position=${engine.currentPosition} rendered=$rendered playing=${engine.isPlaying}")
                    observation?.progress(now, engine.isPlaying, rendered.toLong())
                    observation?.buffering(now, engine.playbackState == Player.STATE_BUFFERING)
                    observation?.videoSize(engine.videoSize.width, engine.videoSize.height)
                }
                if (frameAt == null && (observation?.errors ?: 0) > 0) break
                if (frameAt == null && SystemClock.elapsedRealtime() - start >= 25_000) break
                SystemClock.sleep(250)
            }
            if (frameAt != null) instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "probe-$number-$line-$compatible.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { host ->
                val counters = (host.findViewById<PlayerView>(R.id.player_view).player as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters
                counters?.ensureUpdated()
                Log.i("LineProbe", "video rendered=${counters?.renderedOutputBufferCount} dropped=${counters?.droppedBufferCount} maxFrameGapMs=$maxFrameGap")
                val measuredEngine = host.findViewById<PlayerView>(R.id.player_view).player as androidx.media3.exoplayer.ExoPlayer
                Log.i("LineProbe", "audio mime=" + measuredEngine.audioFormat?.sampleMimeType +
                    " declared=" + measuredEngine.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) +
                    " supported=" + measuredEngine.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                Log.i("LineProbe", "result channel=$number line=$line compat=$compatible software=$software data=" +
                com.google.gson.Gson().toJson(observation?.snapshot(SystemClock.elapsedRealtime(), "probe"))) }
        } finally {
            if (isolatedEngine != null) {
                scenario?.onActivity {
                    it.findViewById<PlayerView>(R.id.player_view).player = originalEngine
                    isolatedEngine?.release()
                }
            }
            scenario?.close()
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
            channel.backupOnly = originalBackupOnly
            RouteQuality.enabled = true
            SP.writeRouteRecords(channel.pid, originalRecords)
            SP.rememberBackup(channel.pid, originalMemory)
            SP.itemPosition = position
            SP.flushRouteRecords()
        }
    }
}
