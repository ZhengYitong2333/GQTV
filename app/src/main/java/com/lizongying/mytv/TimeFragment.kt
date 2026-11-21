package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.TimeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TimeFragment : Fragment(R.layout.time) {
    private var binding: TimeBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val tick = object : Runnable {
        override fun run() {
            val ui = binding ?: return
            val now = Utils.currentTimeMillis()
            format.timeZone = TimeZone.getDefault()
            ui.content.text = format.format(Date(now))
            handler.postDelayed(this, 60_000L - now % 60_000L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = TimeBinding.bind(view)
        (requireActivity() as MainActivity).fragmentReady("TimeFragment")
    }

    private fun updateTicker() {
        handler.removeCallbacks(tick)
        if (isResumed && !isHidden && SP.time) tick.run()
    }

    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); updateTicker() }
    override fun onResume() { super.onResume(); updateTicker() }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroyView() {
        handler.removeCallbacks(tick)
        binding = null
        super.onDestroyView()
    }
}
