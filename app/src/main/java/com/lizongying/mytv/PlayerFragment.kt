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
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.requests.Request

class PlayerFragment : Fragment(R.layout.player), Player.Listener {
    private var binding: PlayerBinding? = null
    private var outputView: PlayerView? = null
    private var timing: TuneTiming? = null
    private val finishQualityWindow = Runnable {
        val engine = player ?: return@Runnable
        timing?.buffering(engine.playWhenReady && engine.playbackState == Player.STATE_BUFFERING)
    }
    private var observation: RouteObservation? = null
    private var nextCheckpoint = 0L

    private fun saveObservation(reason: String) {
        val sample = observation ?: return
        SP.saveRoute(sample.pid, sample.route, sample.snapshot(SystemClock.elapsedRealtime(), reason))
    }

    // A comparison arm owns its observation until its history has been captured.
    internal fun finishQualityObservation() {
        saveObservation("benchmark_sample")
        observation = null
    }

    private fun beginObservation(route: String) {
        saveObservation("route_changed")
        observation = channel?.getTV()?.let { RouteObservation(route, it.pid, SystemClock.elapsedRealtime(), avcCompat, softwareVideo) }
        nextCheckpoint = SystemClock.elapsedRealtime() + 30_000
    }

    private fun rankedRoutes(model: TVViewModel): List<String> {
        val tv = model.getTV()
        val backups = lookupBackup(tv.alias).orEmpty()
        val routes = if (tv.backupOnly) backups else listOf(RouteQuality.PRIMARY) + backups
        return RouteQuality.order(routes, SP.routeRecords(tv.pid), SP.lastBackup(tv.pid), System.currentTimeMillis())
    }

    private var timedOut = false
    private val frameListener = object : AnalyticsListener {
        private fun currentLoad(time: AnalyticsListener.EventTime, data: MediaLoadData): Boolean {
            if (data.dataType != C.DATA_TYPE_MEDIA || time.windowIndex !in 0 until time.timeline.windowCount) return false
            return time.timeline.getWindow(time.windowIndex, Timeline.Window()).mediaItem.mediaId == timing?.mediaId
        }

        override fun onLoadStarted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
            if (currentLoad(time, data)) timing?.segment(info.loadTaskId, false)
        }

        override fun onLoadCompleted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
            if (currentLoad(time, data)) timing?.segment(info.loadTaskId, true)
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            renderedOn(output)
        }
    }

    private var player: ExoPlayer? = null
    private var channel: TVViewModel? = null
    private var media: MediaItem? = null
    private var usingBackup = false
    private var avcCompat = false
    private var softwareVideo = false
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
    private var decodedFrame = false
    private var recoveredLiveWindow = false
    private val monitor = object : Runnable {
        override fun run() {
            val engine = player ?: return
            val model = channel ?: return
            val now = SystemClock.elapsedRealtime()
            timing?.buffering(engine.playWhenReady && engine.playbackState == Player.STATE_BUFFERING, now)
            observation?.progress(now, engine.isPlaying, engine.currentPosition)
            observation?.videoSize(engine.videoSize.width, engine.videoSize.height)
            observation?.buffering(now, engine.playWhenReady && engine.playbackState == Player.STATE_BUFFERING)
            if (now >= nextCheckpoint) {
                saveObservation("observing")
                nextCheckpoint = now + 30_000
            }
            when (health?.sample(now, engine.isPlaying, engine.currentPosition)) {
                PlaybackHealth.Decision.STABLE -> {
                    failures = 0
                    // Full quality observations take precedence over this legacy migration hint.
                    if (!RouteQuality.enabled) SP.rememberBackup(model.getTV().pid, if (usingBackup) backupLines[backupLine] else null)
                }
                PlaybackHealth.Decision.SLOW -> {
                    if (!timedOut) {
                        timing?.event(if (reportedFrame) "stall_limit" else "timeout")
                        if (!reportedFrame) observation?.let { it.timeouts++ }
                    }
                    timedOut = true
                    saveObservation(if (reportedFrame) "stall_limit" else "timeout")
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
        timedOut = false
        reportedFrame = false
        decodedFrame = false
        if (RouteQuality.enabled) binding?.tuneCover?.visibility = View.VISIBLE
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
        avcCompat = false
        softwareVideo = false
        recoveredLiveWindow = false
        media = null
        player?.stop()
        startAttempt()
        model.changed("retry")
        return true
    }

    private val retry = Runnable {
        startAttempt()
        if (usingBackup) {
            timing?.attempt("backup-${backupLine + 1}")
            beginObservation(backupLines[backupLine])
            prepareMedia()
        } else channel?.changed("retry")
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
        val renderers = DefaultRenderersFactory(requireContext()).setMediaCodecSelector { mime, secure, tunnel ->
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
            if (RouteQuality.enabled && usingBackup && softwareVideo && mime == MimeTypes.VIDEO_H264)
                available.filter { it.softwareOnly }.ifEmpty { available }
            else available
        }
        val engine = ExoPlayer.Builder(requireContext(), renderers).build()
        player = engine
        outputView?.player = engine
        engine.addAnalyticsListener(frameListener)
        engine.addListener(this)
        engine.playWhenReady = true
        channel?.let { model ->
            tuneStarted = SystemClock.elapsedRealtime()
            timing = TuneTiming(model.getTV().alias.ifEmpty { model.getTV().title }, false).also { model.tuneId = it.id }
            timing?.attempt(if (usingBackup) "backup-${backupLine + 1}" else "primary")
            beginObservation(if (usingBackup) backupLines[backupLine] else RouteQuality.PRIMARY)
            startAttempt()
        }
        prepareMedia()
    }

    fun select(model: TVViewModel, userTune: Boolean, cold: Boolean = false) {
        if (channel !== model || userTune) {
            view?.removeCallbacks(retry)
            view?.removeCallbacks(finishQualityWindow)
            val replacingChannel = channel != null
            saveObservation("retuned")
            observation = null
            timing?.finish("retuned")
            timing = TuneTiming(model.getTV().alias.ifEmpty { model.getTV().title }, cold).also { model.tuneId = it.id }
            tuneStarted = SystemClock.elapsedRealtime()
            recoveredLiveWindow = false
            startAttempt()
            channel = model
            media = null
            usingBackup = false
            avcCompat = false
            softwareVideo = false
            backupLines = emptyList()
            remainingLines = null
            primaryStillAvailable = false
            backupLine = 0
            retriedLine = false
            selectedHeight = null
            failures = 0
            setVideoTrack(null)
            player?.clearMediaItems()
            if (replacingChannel) replaceOutput()
        }
    }

    fun sourceFailed() {
        timing?.event("source_error")
        observation?.let { if (it.errors == 0) it.errors++ }
        saveObservation("source_error")
    }

    fun beginPrimary() {
        timing?.attempt("primary")
        beginObservation(RouteQuality.PRIMARY)
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
        if (RouteQuality.enabled) return rankedRoutes(model).firstOrNull()?.let { it != RouteQuality.PRIMARY } == true
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
        remainingLines = if (RouteQuality.enabled) {
            rankedRoutes(model).filter { it != RouteQuality.PRIMARY }.map { backupLines.indexOf(it) }.iterator()
        } else (if (preferred >= 0) listOf(preferred) + indices else indices).iterator()
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
        val previousSoftwareVideo = softwareVideo
        val profile = if (RouteQuality.enabled) RouteQuality.recent(
            SP.routeRecords(channel!!.getTV().pid)[backupLines[next]].orEmpty(), System.currentTimeMillis())
            .lastOrNull { it.frameMs != null && it.errors == 0 && !it.unsupportedAudio } else null
        avcCompat = profile?.avcCompat ?: false
        softwareVideo = profile?.softwareVideo ?: false
        if (previousSoftwareVideo != softwareVideo) player?.stop()
        retriedLine = false
        recoveredLiveWindow = false
        startAttempt()
        failures = 0
        setVideoTrack(null)
        timing?.attempt("backup-${next + 1}")
        beginObservation(backupLines[next])
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
        val item = media?.buildUpon()?.setMediaId(timing?.mediaId.orEmpty())?.build() ?: return
        media = item
        timing?.event("address")
        engine.volume = channel?.getTV()?.volume ?: 1f
        if (RouteQuality.enabled && usingBackup && avcCompat) {
            engine.setMediaSource(HlsMediaSource.Factory(DefaultDataSource.Factory(requireContext()))
                .setExtractorFactory(ContinuousHlsExtractorFactory()).createMediaSource(item))
        } else engine.setMediaItem(item)
        engine.prepare()
    }

    override fun onPlayerError(error: PlaybackException) {
        val model = channel ?: return
        timing?.event("playback_error", "code=${error.errorCode}")
        observation?.let { it.errors++ }
        saveObservation("playback_error")
        health = null
        scheduleMonitor()
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            if (!recoveredLiveWindow) {
                recoveredLiveWindow = true
                timing?.event("live_window_recovery")
                beginObservation(if (usingBackup) backupLines[backupLine] else RouteQuality.PRIMARY)
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
                if (RouteQuality.enabled) {
                    val causes = generateSequence(error as Throwable) { it.cause }.toList()
                    val avcParsing = causes.any { cause -> cause.stackTrace.any {
                        it.className.startsWith("androidx.media3.extractor.ts.H264Reader")
                    } }
                    val avcDecoding = causes.filterIsInstance<MediaCodecDecoderException>()
                        .any { it.codecInfo?.mimeType == MimeTypes.VIDEO_H264 }
                    if (avcParsing || avcDecoding) {
                        avcCompat = true
                        val available = runCatching {
                            MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                        }.getOrDefault(emptyList())
                        val softwareAvailable = available.any { it.softwareOnly }
                        val emulatedDecoder = available.firstOrNull()?.name?.startsWith("c2.goldfish.") == true
                        softwareVideo = softwareAvailable && (avcDecoding || emulatedDecoder)
                        timing?.event("avc_retry", "compat=$avcCompat software=$softwareVideo")
                    }
                }
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
        if (!decodedFrame) timing?.event("decoded_frame", "width=${player?.videoSize?.width ?: 0} height=${player?.videoSize?.height ?: 0}")
        decodedFrame = true
        revealDecodedFrame()
    }

    private fun revealDecodedFrame() {
        val engine = player ?: return
        if (!decodedFrame) return
        if (RouteQuality.enabled && (engine.playbackState != Player.STATE_READY || engine.playWhenReady && !engine.isPlaying)) return
        binding?.tuneCover?.visibility = View.GONE
        if (reportedFrame) return
        reportedFrame = true
        health?.rendered()
        observation?.rendered(SystemClock.elapsedRealtime(), player?.videoSize?.width ?: 0, player?.videoSize?.height ?: 0)
        nextCheckpoint = SystemClock.elapsedRealtime() + 30_000
        if (timing?.frame(player?.videoSize?.width ?: 0, player?.videoSize?.height ?: 0) == true) {
            view?.postDelayed(finishQualityWindow, 30_000)
            val route = backupPosition()?.let { (line, count) -> "备用 $line/$count" } ?: "主源"
            Log.i("PlaybackHealth", "${channel?.getTV()?.alias} $route 首帧 ${SystemClock.elapsedRealtime() - tuneStarted}ms")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (RouteQuality.enabled && usingBackup && playbackState == Player.STATE_ENDED &&
            player?.playbackState == Player.STATE_ENDED) {
            timing?.buffering(false)
            observation?.let { it.buffering(SystemClock.elapsedRealtime(), false); it.errors++ }
            timing?.event("stream_ended")
            saveObservation("stream_ended")
            forgetCurrentBackup()
            channel?.let {
                if (!switchRoute(it)) {
                    health = null
                    scheduleMonitor()
                    it.setErrInfo("直播信号已结束")
                }
            }
            return
        }
        timing?.buffering(playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
        observation?.buffering(SystemClock.elapsedRealtime(), playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
        health?.buffering(SystemClock.elapsedRealtime(), playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
        revealDecodedFrame()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
            saveObservation("paused")
            observation = null
            health = null
        } else if (health == null && media != null) {
            beginObservation(if (usingBackup) backupLines[backupLine] else RouteQuality.PRIMARY)
            if (reportedFrame) observation?.rendered(SystemClock.elapsedRealtime(), player?.videoSize?.width ?: 0, player?.videoSize?.height ?: 0)
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
        revealDecodedFrame()
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.isEmpty || tracks != player?.currentTracks) return
        val hasAlternatives = usingBackup || channel?.getTV()?.let { lookupBackup(it.alias).orEmpty().isNotEmpty() } == true
        if (RouteQuality.enabled && hasAlternatives && tracks.containsType(C.TRACK_TYPE_AUDIO) &&
            !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
            observation?.let {
                it.errors++
                it.unsupportedAudio = true
            }
            timing?.event("unsupported_audio")
            saveObservation("unsupported_audio")
            forgetCurrentBackup()
            channel?.let {
                if (!switchRoute(it)) {
                    health = null
                    scheduleMonitor()
                    player?.stop()
                    it.setErrInfo("当前线路的音频格式无法播放")
                }
            }
            return
        }
        val height = selectedHeight ?: return
        if (!setResolution(height)) setResolution(null)
    }

    override fun onStop() {
        view?.removeCallbacks(finishQualityWindow)
        saveObservation("stopped")
        SP.flushRouteRecords()
        observation = null
        timing?.finish("stopped")
        timing = null
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
