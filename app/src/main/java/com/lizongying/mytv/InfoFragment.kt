package com.lizongying.mytv

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.InfoBinding
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.models.currentProgramIndex

class InfoFragment : Fragment(R.layout.info) {
    private var binding: InfoBinding? = null
    private val close = Runnable { hide() }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding = InfoBinding.bind(view)
        view.isVisible = false
        (requireActivity() as MainActivity).fragmentReady("InfoFragment")
    }

    fun show(model: TVViewModel) {
        val ui = binding ?: return
        val guide = model.epg.value.orEmpty()
        val current = guide.getOrNull(guide.currentProgramIndex(Utils.getDateTimestamp()))
        ui.title.text = model.getTV().displayTitle()
        ui.desc.text = current?.title
        ui.desc.isVisible = current != null
        ui.root.isVisible = true
        ui.root.removeCallbacks(close)
        ui.root.postDelayed(close, 5_000)
    }

    fun hide() {
        binding?.root?.apply { removeCallbacks(close); isVisible = false }
    }

    override fun onPause() { hide(); super.onPause() }
    override fun onDestroyView() { hide(); binding = null; super.onDestroyView() }
}
