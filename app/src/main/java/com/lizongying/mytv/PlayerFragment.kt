package com.lizongying.mytv

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.requests.Request

class PlayerFragment : Fragment(R.layout.player), Player.Listener {
    private var binding: PlayerBinding? = null
    private var outputView: PlayerView? = null
    private val frameListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            renderedOn(output)
        }
    }

    private var player: ExoPlayer? = null
    private var channel: TVViewModel? = null
    private var media: MediaItem? = null
    private var usingBackup = false
    internal var lookupBackup: (String) -> List<String>? = ::backupSource
    private var backupLines = emptyList<String>()
    private var remainingLines: Iterator<Int>? = null
    private var primaryStillAvailable = false
    private var backupLine = 0
    private var retriedLine = false
    private var selectedHeight: Int? = null
    private var failures = 0
    private var health: PlaybackHealth? = null
    private var tuneStarted = 0L
    private var reportedFrame = false
    private var recoveredLiveWindow = false
    private val monitor = object : Runnable {
        override fun run() {
            val engine = player ?: return
            val model = channel ?: return
            val now = SystemClock.elapsedRealtime()
            when (health?.sample(now, engine.isPlaying, engine.currentPosition)) {
                PlaybackHealth.Decision.STABLE -> {
                    failures = 0
                    SP.rememberBackup(model.getTV().pid, if (usingBackup) backupLines[backupLine] else null)
                }
                PlaybackHealth.Decision.SLOW -> {
                    forgetCurrentBackup()
                    if (!switchRoute(model)) {
                        // The last route may recover. Start a fresh observation window.
                        health = PlaybackHealth(now).also { if (reportedFrame) it.rendered() }
                        onPlaybackStateChanged(engine.playbackState)
                    }
                }
                else -> Unit
            }
            scheduleMonitor()
        }
    }

    private fun scheduleMonitor() {
        view?.removeCallbacks(monitor)
        if (health != null && player != null) view?.postDelayed(monitor, 500)
    }

    private fun startAttempt() {
        reportedFrame = false
        health = PlaybackHealth(SystemClock.elapsedRealtime())
        scheduleMonitor()
    }

    private fun forgetCurrentBackup() {
        val pid = channel?.getTV()?.pid ?: return
        if (usingBackup && SP.lastBackup(pid) == backupLines[backupLine]) SP.rememberBackup(pid, null)
    }

    private fun switchRoute(model: TVViewModel): Boolean {
        if (!usingBackup) {
            if (!tryBackup(model)) return false
            Request.stopPlaybackRequests()
            return true
        }
        if (advanceBackup()) return true
        if (!primaryStillAvailable) return false
        primaryStillAvailable = false
        usingBackup = false
        recoveredLiveWindow = false
        media = null
        player?.stop()
        startAttempt()
        model.changed("retry")
        return true
    }

    private val retry = Runnable {
        startAttempt()
        if (usingBackup) prepareMedia() else channel?.changed("retry")
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding = PlayerBinding.bind(view)
        replaceOutput()
        (requireActivity() as MainActivity).fragmentReady("PlayerFragment")
    }

    // A SurfaceView can retain the last submitted video buffer after the playlist is cleared.
    // Keep the decoder pipeline's player, but give each tune its own presentation surface.
    private fun replaceOutput() {
        val ui = binding ?: return
        ui.tuneCover.visibility = View.VISIBLE
        outputView?.let {
            it.player = null
            ui.root.removeView(it)
        }
        val next = layoutInflater.inflate(R.layout.video_output, ui.root, false) as PlayerView
        outputView = next
        ui.root.addView(next, 0)
        next.player = player
    }

    override fun onStart() {
        super.onStart()
        val engine = ExoPlayer.Builder(requireContext()).build()
        player = engine
        outputView?.player = engine
        engine.addAnalyticsListener(frameListener)
        engine.addListener(this)
        engine.playWhenReady = true
        if (channel != null) startAttempt()
        prepareMedia()
    }

    fun select(model: TVViewModel, userTune: Boolean) {
        if (channel !== model || userTune) {
            view?.removeCallbacks(retry)
            tuneStarted = SystemClock.elapsedRealtime()
            recoveredLiveWindow = false
            startAttempt()
            channel = model
            media = null
            usingBackup = false
            backupLines = emptyList()
            remainingLines = null
            primaryStillAvailable = false
            backupLine = 0
            retriedLine = false
            selectedHeight = null
            failures = 0
            setVideoTrack(null)
            val hadVideo = player?.currentMediaItem != null
            player?.clearMediaItems()
            if (hadVideo) replaceOutput()
        }
    }

    fun play(model: TVViewModel) {
        if (channel !== model || usingBackup) return
        val index = model.videoIndex.value ?: return
        val url = model.videoUrl.value?.getOrNull(index) ?: return
        media = MediaItem.fromUri(url)
        prepareMedia()
    }

    internal fun hasRememberedBackup(model: TVViewModel): Boolean {
        val tv = model.getTV()
        val remembered = SP.lastBackup(tv.pid) ?: return false
        if (remembered in lookupBackup(tv.alias).orEmpty()) return true
        SP.rememberBackup(tv.pid, null)
        return false
    }

    fun tryBackup(model: TVViewModel, beforePrimary: Boolean = false): Boolean {
        val tv = model.getTV()
        if (channel !== model || tv.pid.isEmpty() || remainingLines != null) return false
        backupLines = lookupBackup(tv.alias).orEmpty()
        if (backupLines.isEmpty()) return false
        val preferred = backupLines.indexOf(SP.lastBackup(tv.pid))
        val indices = backupLines.indices.filter { it != preferred }
        remainingLines = (if (preferred >= 0) listOf(preferred) + indices else indices).iterator()
        primaryStillAvailable = beforePrimary && !tv.backupOnly
        return advanceBackup()
    }

    private fun advanceBackup(): Boolean {
        val remaining = remainingLines ?: return false
        if (!remaining.hasNext()) return false
        val next = remaining.next()
        view?.removeCallbacks(retry)
        usingBackup = true
        backupLine = next
        retriedLine = false
        recoveredLiveWindow = false
        startAttempt()
        failures = 0
        setVideoTrack(null)
        media = MediaItem.Builder().setUri(backupLines[next]).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        prepareMedia()
        return true
    }

    internal fun videoResolution(): Pair<Int, Int>? = player?.videoSize?.let {
        if (it.width > 0 && it.height > 0) it.width to it.height else null
    }

    internal fun resolutionSelection(): Int? = selectedHeight
    internal fun isUsingBackup() = usingBackup

    /** 1-based line number and total lines, or null when playing the app's own source. */
    internal fun backupPosition(): Pair<Int, Int>? {
        if (!usingBackup) return null
        return (backupLine + 1) to backupLines.size
    }

    @OptIn(UnstableApi::class)
    internal fun resolutionOptions(): List<Pair<Int, Int>> = videoTracks().distinctBy { it.first }
        .sortedByDescending { it.first }.map { (_, override) ->
            val format = override.mediaTrackGroup.getFormat(override.trackIndices.first())
            format.width to format.height
        }

    @OptIn(UnstableApi::class)
    internal fun setResolution(height: Int?): Boolean {
        val track = height?.let { requested -> videoTracks().firstOrNull { it.first == requested }?.second }
        if (height != null && track == null) return false
        selectedHeight = height
        setVideoTrack(track)
        return true
    }

    @OptIn(UnstableApi::class)
    private fun videoTracks(): List<Pair<Int, TrackSelectionOverride>> =
        player?.currentTracks?.groups.orEmpty()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length)
                    .filter {
                        val format = group.getTrackFormat(it)
                        group.isTrackSupported(it) && format.width > 0 && format.height > 0
                    }
                    .groupBy { group.getTrackFormat(it).height }
                    .map { (height, indices) -> height to TrackSelectionOverride(group.mediaTrackGroup, indices) }
            }

    private fun setVideoTrack(track: TrackSelectionOverride?) {
        val engine = player ?: return
        val builder = engine.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (track != null) builder.setOverrideForType(track)
        engine.trackSelectionParameters = builder.build()
    }

    private fun prepareMedia() {
        val engine = player ?: return
        val item = media ?: return
        engine.volume = channel?.getTV()?.volume ?: 1f
        engine.setMediaItem(item)
        engine.prepare()
    }

    override fun onPlayerError(error: PlaybackException) {
        val model = channel ?: return
        health = null
        scheduleMonitor()
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            if (!recoveredLiveWindow) {
                recoveredLiveWindow = true
                startAttempt()
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }
            forgetCurrentBackup()
            if (!switchRoute(model)) model.setErrInfo("直播信号暂时无法恢复")
            return
        }
        if (usingBackup) {
            // One retry per line for a transient blip, then move to the next
            // verified line. The error overlay only appears once the list ends,
            // so a channel that recovers never flashes an error on screen.
            if (!retriedLine) {
                retriedLine = true
                view?.removeCallbacks(retry)
                view?.postDelayed(retry, 1_000L)
            } else {
                forgetCurrentBackup()
                if (!switchRoute(model)) model.setErrInfo("播放错误")
            }
            return
        }
        model.setErrInfo("播放错误")
        // The error observer may already have switched the media synchronously.
        if (usingBackup) return
        if (failures < 3) {
            view?.removeCallbacks(retry)
            view?.postDelayed(retry, 1_000L shl failures)
            failures++
        }
    }

    internal fun renderedOn(output: Any) {
        val surfaceView = outputView?.videoSurfaceView as? SurfaceView ?: return
        if (output !== surfaceView.holder.surface) return
        val item = media ?: return
        if (player?.currentMediaItem != item) return
        binding?.tuneCover?.visibility = View.GONE
        if (reportedFrame) return
        reportedFrame = true
        health?.rendered()
        val route = backupPosition()?.let { (line, count) -> "备用 $line/$count" } ?: "主源"
        Log.i("PlaybackHealth", "${channel?.getTV()?.alias} $route 首帧 ${SystemClock.elapsedRealtime() - tuneStarted}ms")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        health?.buffering(SystemClock.elapsedRealtime(), playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) health = null
        else if (health == null && media != null) {
            health = PlaybackHealth(SystemClock.elapsedRealtime())
            if (reportedFrame) health?.rendered()
            player?.let { onPlaybackStateChanged(it.playbackState) }
        }
        scheduleMonitor()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            view?.removeCallbacks(retry)
            channel?.setErrInfo("")
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.isEmpty) return
        val height = selectedHeight ?: return
        if (!setResolution(height)) setResolution(null)
    }

    override fun onStop() {
        health = null
        view?.removeCallbacks(monitor)
        view?.removeCallbacks(retry)
        binding?.tuneCover?.visibility = View.VISIBLE
        player?.removeAnalyticsListener(frameListener)
        outputView?.player = null
        player?.removeListener(this)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroyView() {
        outputView = null
        binding = null
        super.onDestroyView()
    }
}
