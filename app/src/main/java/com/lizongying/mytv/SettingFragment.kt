package com.lizongying.mytv

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Switch
import android.widget.Toast
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.databinding.SettingBinding
import com.lizongying.mytv.requests.ReleaseRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingFragment : DialogFragment() {
    private var binding: SettingBinding? = null
    private var more = false
    private var focusId = R.id.switch_channel_reversal
    private var restoredFocus: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        more = savedInstanceState?.getBoolean("more") ?: false
        focusId = savedInstanceState?.getInt("focus", focusId) ?: focusId
        restoredFocus = savedInstanceState?.getInt("focus")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ui = SettingBinding.inflate(inflater, container, false)
        binding = ui
        ui.keys.direction.setText(R.string.key_vertical)
        ui.keys.move.setText(R.string.key_move)
        ui.keys.confirm.setText(R.string.key_confirm)
        ui.keys.back.setText(R.string.key_back)
        ui.versionName.text = getString(R.string.version_label, requireContext().appVersionName)
        bindSwitch(ui.switchChannelReversal, SP.channelReversal) { SP.channelReversal = it }
        bindSwitch(ui.switchChannelNum, SP.channelNum) { SP.channelNum = it }
        bindSwitch(ui.switchTime, SP.time) { SP.time = it }
        bindSwitch(ui.switchGrid, SP.grid) { SP.grid = it }
        bindSwitch(ui.switchBootStartup, SP.bootStartup) { SP.bootStartup = it }

        ui.resolution.setOnClickListener {
            if (!childFragmentManager.isStateSaved &&
                childFragmentManager.findFragmentByTag(ResolutionFragment.TAG) == null) {
                ResolutionFragment().show(childFragmentManager, ResolutionFragment.TAG)
            }
        }
        ui.more.setOnClickListener { showPage(true, R.id.check_version) }
        ui.checkVersion.setOnClickListener { checkUpdate(ui) }
        ui.clear.setOnClickListener {
            ConfirmationFragment.reset(getString(R.string.confirm_reset)).show(childFragmentManager, "confirm")
        }
        ui.exit.setOnClickListener { requireActivity().finishAffinity() }
        showPage(more, focusId)
        for (row in ui.preferences.children + ui.actions.children) {
            if (row.isFocusable) row.setOnFocusChangeListener { view, focused ->
                // The dialog can focus its first row while Android is tearing down the window.
                if (focused && isResumed && view.hasWindowFocus()) focusId = view.id
            }
        }
        return ui.root
    }

    private fun bindSwitch(view: Switch, checked: Boolean, save: (Boolean) -> Unit) {
        view.isChecked = checked
        view.setOnCheckedChangeListener { _, value -> save(value) }
    }

    private fun showPage(showMore: Boolean, target: Int) {
        val ui = binding ?: return
        more = showMore
        focusId = target
        ui.preferences.isVisible = !more
        ui.actions.isVisible = more
        ui.title.setText(if (more) R.string.more_settings else R.string.settings_title)
        ui.root.scrollTo(0, 0)
        ui.root.findViewById<View>(target)?.requestFocus()
    }

    override fun onStart() {
        val initialFocus = restoredFocus ?: focusId
        super.onStart()
        val ui = binding ?: return
        styleSettingsWindow()
        requireDialog().setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        if (more) showPage(false, R.id.more) else dismiss()
                    }
                    true
                }
                else -> false
            }
        }
        val target = ui.root.findViewById<View>(initialFocus)
        if (target?.requestFocus() == false) target.requestFocusFromTouch()
        if (restoredFocus != null) {
            ui.root.doOnPreDraw { target?.requestFocus() }
            restoredFocus = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("more", more)
        outState.putInt("focus", focusId)
        super.onSaveInstanceState(outState)
    }

    private fun checkUpdate(ui: SettingBinding) {
        if (checkingUpdate) return
        checkingUpdate = true
        ui.checkVersion.setText(R.string.checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val release = try {
                ReleaseRequest().getRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                checkingUpdate = false
                ui.checkVersion.setText(R.string.check_version)
            }
            if (childFragmentManager.isStateSaved || !more || !isResumed) return@launch
            val preview = resources.getBoolean(R.bool.preview_build)
            val newVersion = release?.version_name?.takeIf {
                !preview &&
                (release.version_code ?: 0) > requireContext().appVersionCode
            }
            val message = when {
                preview && release?.version_name != null -> getString(R.string.preview_upstream_version, release.version_name)
                newVersion != null -> getString(R.string.new_version, newVersion)
                release?.version_code != null -> getString(R.string.up_to_date)
                else -> getString(R.string.update_unavailable)
            }
            ConfirmationFragment.update(message, newVersion).show(childFragmentManager, "confirm")
        }
    }

    private var checkingUpdate = false

    internal fun confirmAction(reset: Boolean, version: String?) {
        if (version != null) UpdateManager.startDownload(requireContext(), version)
        else if (reset) resetSettings()
    }

    private fun resetSettings() {
        SP.resetSettings()
        (requireActivity() as MainActivity).syncTime()
        YSP.getGuid()
        binding?.apply {
            switchChannelReversal.isChecked = SP.channelReversal
            switchChannelNum.isChecked = SP.channelNum
            switchTime.isChecked = SP.time
            switchGrid.isChecked = SP.grid
            switchBootStartup.isChecked = SP.bootStartup
        }
        Toast.makeText(requireContext(), R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object { const val TAG = "SettingFragment" }
}

internal fun DialogFragment.styleSettingsWindow() {
    val window = requireDialog().window ?: return
    val metrics = resources.displayMetrics
    val density = metrics.density
    val width = (metrics.widthPixels * .25f).roundToInt()
        .coerceAtLeast((248 * density).roundToInt()).coerceAtMost((metrics.widthPixels * .88f).toInt())
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    window.setBackgroundDrawableResource(R.drawable.tv_panel)
    window.attributes = window.attributes.apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        x = (metrics.widthPixels * .045f).roundToInt()
    }
    if (Build.VERSION.SDK_INT >= 31 && window.windowManager.isCrossWindowBlurEnabled) {
        window.setBackgroundBlurRadius((15 * density).roundToInt())
    }
    window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
}
