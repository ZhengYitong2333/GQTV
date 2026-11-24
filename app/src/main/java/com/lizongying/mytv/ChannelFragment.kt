package com.lizongying.mytv

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.ChannelBinding
import com.lizongying.mytv.models.TVViewModel

class ChannelFragment : Fragment(R.layout.channel) {
    private var binding: ChannelBinding? = null
    private var digits = ""
    private val hide = Runnable { clear() }
    private val tune = Runnable {
        val number = digits.toIntOrNull()
        clear()
        if (number != null) (activity as? MainActivity)?.play(number - 1)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = ChannelBinding.bind(view)
        view.isVisible = false
        (requireActivity() as MainActivity).fragmentReady("ChannelFragment")
    }

    fun show(model: TVViewModel) {
        val ui = binding ?: return
        clear()
        ui.content.text = (model.getTV().id + 1).toString()
        ui.root.isVisible = true
        ui.root.postDelayed(hide, 5_000)
    }

    fun preview(number: Int) {
        val ui = binding ?: return
        clear()
        ui.content.text = number.toString()
        ui.root.isVisible = true
    }

    fun show(digit: String) {
        val ui = binding ?: return
        ui.root.removeCallbacks(hide)
        ui.root.removeCallbacks(tune)
        digits += digit
        ui.content.text = digits
        ui.root.isVisible = true
        if (digits.length == 2) tune.run() else ui.root.postDelayed(tune, ChannelJump.SECOND_DIGIT_TIMEOUT_MS)
    }

    fun clear() {
        digits = ""
        binding?.root?.apply {
            removeCallbacks(hide)
            removeCallbacks(tune)
            isVisible = false
        }
    }

    override fun onPause() {
        clear()
        super.onPause()
    }

    override fun onDestroyView() {
        clear()
        binding = null
        super.onDestroyView()
    }
}
