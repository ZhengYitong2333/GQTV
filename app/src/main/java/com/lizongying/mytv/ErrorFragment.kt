package com.lizongying.mytv

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.ErrorBinding

class ErrorFragment : Fragment(R.layout.error) {
    private var binding: ErrorBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = ErrorBinding.bind(view)
        (requireActivity() as MainActivity).fragmentReady("ErrorFragment")
    }

    fun show(message: String) {
        binding?.msg?.text = message
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
