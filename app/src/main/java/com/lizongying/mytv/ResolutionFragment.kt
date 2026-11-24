package com.lizongying.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.databinding.ResolutionBinding

class ResolutionFragment : DialogFragment() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = ResolutionBinding.inflate(inflater, container, false)
        val player = (requireActivity() as MainActivity).playbackSettings()
        val current = player.videoResolution()
        val available = player.resolutionOptions()
        ui.details.text = buildList {
            player.backupPosition()?.let { (index, total) -> add(getString(R.string.resolution_backup_source, index, total)) }
            if (current != null) add(getString(R.string.resolution_current, current.first, current.second))
            if (available.size < 2) add(getString(if (current == null) R.string.resolution_waiting else R.string.resolution_fixed))
        }.joinToString("\n")
        ui.details.isVisible = ui.details.text.isNotEmpty()
        ui.keys.direction.setText(R.string.key_vertical)
        ui.keys.move.setText(R.string.key_move)
        ui.keys.confirm.setText(R.string.key_confirm)
        ui.keys.back.setText(R.string.key_back)
        fun row(label: String, selected: Boolean, action: () -> Unit) {
            val button = inflater.inflate(R.layout.resolution_choice, ui.options, false) as Button
            button.text = if (selected) "✓　" + label else label
            button.isSelected = selected
            button.setOnClickListener { action() }
            ui.options.addView(button)
            if (selected) button.requestFocus()
        }
        if (available.size < 2) {
            ui.keys.root.isVisible = false
            row(getString(R.string.key_back), false) { dismiss() }
        } else {
            val selected = player.resolutionSelection()
            row(getString(R.string.resolution_auto), selected == null) { player.setResolution(null); dismiss() }
            for ((width, height) in available) {
                row(getString(R.string.resolution_pixels, width, height), selected == height) {
                    player.setResolution(height)
                    dismiss()
                }
            }
        }
        return ui.root
    }

    private fun parentPanel() = (parentFragment as? DialogFragment)?.dialog?.window?.decorView

    override fun onStart() {
        super.onStart()
        styleSettingsWindow()
        parentPanel()?.visibility = View.INVISIBLE
        val options = requireView().findViewById<ViewGroup>(R.id.options)
        val target = (0 until options.childCount).map(options::getChildAt).firstOrNull { it.isSelected } ?: options.getChildAt(0)
        if (!target.requestFocus()) target.requestFocusFromTouch()
        requireDialog().setOnKeyListener { _, code, event ->
            if (code == android.view.KeyEvent.KEYCODE_MENU || code == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) dismiss()
                true
            } else false
        }
    }

    override fun onStop() {
        parentPanel()?.visibility = View.VISIBLE
        super.onStop()
    }

    companion object { const val TAG = "ResolutionFragment" }
}
