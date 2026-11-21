package com.lizongying.mytv

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel

class PlayerFragment : Fragment(R.layout.player), Player.Listener {
    private var binding: PlayerBinding? = null
    private var player: ExoPlayer? = null
    private var channel: TVViewModel? = null
    private var media: MediaItem? = null
    private var failures = 0
    private val retry = Runnable { channel?.changed("retry") }

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
        if (channel !== model) {
            channel = model
            media = null
            player?.stop()
            player?.clearMediaItems()
        }
        if (userTune) {
            view?.removeCallbacks(retry)
            failures = 0
        }
    }

    fun play(model: TVViewModel) {
        if (channel !== model) return
        val index = model.videoIndex.value ?: return
        val url = model.videoUrl.value?.getOrNull(index) ?: return
        media = MediaItem.fromUri(url)
        prepareMedia()
    }

    private fun prepareMedia() {
        val engine = player ?: return
        val item = media ?: return
        engine.volume = channel?.getTV()?.volume ?: 1f
        engine.setMediaItem(item)
        engine.prepare()
    }

    override fun onPlayerError(error: PlaybackException) {
        channel?.setErrInfo("播放错误")
        if (failures < 3) {
            view?.removeCallbacks(retry)
            view?.postDelayed(retry, 1_000L shl failures)
            failures++
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            failures = 0
            view?.removeCallbacks(retry)
            channel?.setErrInfo("")
        }
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
