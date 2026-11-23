package com.lizongying.mytv

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class ResolutionFragment : DialogFragment() {
    override fun onStart() {
        super.onStart()
        val list = (dialog as? AlertDialog)?.listView ?: return
        if (!list.requestFocus()) list.requestFocusFromTouch()
        list.setSelection(list.checkedItemPosition.coerceAtLeast(0))
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val player = (requireActivity() as MainActivity).playbackSettings()
        val current = player.videoResolution()
        val available = player.resolutionOptions()
        val builder = AlertDialog.Builder(requireContext()).setTitle(R.string.resolution_title)
        if (available.size < 2) {
            val message = buildList {
                if (current == null) {
                    add(getString(R.string.resolution_waiting))
                } else {
                    add(getString(R.string.resolution_current, current.first, current.second))
                    add(getString(R.string.resolution_fixed))
                }
                val line = player.backupPosition()
                if (line != null) add(getString(R.string.resolution_backup_source, line.first, line.second))
            }
            return builder.setMessage(message.joinToString("\n")).setPositiveButton(android.R.string.ok, null).create()
        }
        val choices = listOf(null) + available
        val labels = choices.map { resolution ->
            when {
                resolution == null -> getString(R.string.resolution_auto)
                current == resolution -> getString(R.string.resolution_current, resolution.first, resolution.second)
                else -> getString(R.string.resolution_pixels, resolution.first, resolution.second)
            }
        }
        val selected = choices.indexOfFirst { it?.second == player.resolutionSelection() }
        return builder.setSingleChoiceItems(labels.toTypedArray(), selected) { _, index ->
            player.setResolution(choices[index]?.second)
            dismiss()
        }.setNegativeButton(android.R.string.cancel, null).create()
    }

    companion object { const val TAG = "ResolutionFragment" }
}
