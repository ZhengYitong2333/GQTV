package com.lizongying.mytv

import android.os.Bundle
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
import androidx.media3.exoplayer.ExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel

class PlayerFragment : Fragment(R.layout.player), Player.Listener {
    private var binding: PlayerBinding? = null
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
    private val retry = Runnable {
        if (usingBackup) prepareMedia() else channel?.changed("retry")
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding = PlayerBinding.bind(view)
        (requireActivity() as MainActivity).fragmentReady("PlayerFragment")
    }

    override fun onStart() {
        super.onStart()
        val engine = ExoPlayer.Builder(requireContext()).build()
        player = engine
        binding?.playerView?.player = engine
        engine.addListener(this)
        engine.playWhenReady = true
        prepareMedia()
    }

    fun select(model: TVViewModel, userTune: Boolean) {
        if (channel !== model || userTune) {
            view?.removeCallbacks(retry)
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
            player?.stop()
            player?.clearMediaItems()
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
        return remembered in lookupBackup(tv.alias).orEmpty()
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
        if (usingBackup) {
            // One retry per line for a transient blip, then move to the next
            // verified line. The error overlay only appears once the list ends,
            // so a channel that recovers never flashes an error on screen.
            if (!retriedLine) {
                retriedLine = true
                view?.removeCallbacks(retry)
                view?.postDelayed(retry, 1_000L)
            } else {
                val pid = model.getTV().pid
                if (SP.lastBackup(pid) == backupLines[backupLine]) SP.rememberBackup(pid, null)
                if (!advanceBackup()) {
                    if (primaryStillAvailable) {
                        primaryStillAvailable = false
                        usingBackup = false
                        media = null
                        model.changed("retry")
                    } else model.setErrInfo("播放错误")
                }
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

    override fun onRenderedFirstFrame() {
        val item = media ?: return
        if (player?.currentMediaItem != item) return
        val pid = channel?.getTV()?.pid ?: return
        SP.rememberBackup(pid, if (usingBackup) backupLines[backupLine] else null)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            failures = 0
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
        view?.removeCallbacks(retry)
        binding?.playerView?.player = null
        player?.removeListener(this)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
