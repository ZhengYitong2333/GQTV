package com.lizongying.mytv

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment

class ConfirmationFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val builder = AlertDialog.Builder(requireContext()).setTitle(args.getString(MESSAGE))
        if (args.getBoolean(RESET) || args.getString(VERSION) != null) {
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                (parentFragment as? SettingFragment)?.confirmAction(args.getBoolean(RESET), args.getString(VERSION))
            }.setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        return builder.create()
    }

    companion object {
        private const val RESET = "reset"
        private const val VERSION = "version"
        private const val MESSAGE = "message"

        fun reset(message: String) = ConfirmationFragment().apply {
            arguments = bundleOf(MESSAGE to message, RESET to true)
        }

        fun update(message: String, version: String?) = ConfirmationFragment().apply {
            arguments = bundleOf(MESSAGE to message, VERSION to version)
        }
    }
}
