package com.lizongying.mytv

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.databinding.MenuBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVList
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.models.currentProgramIndex
import com.lizongying.mytv.requests.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainFragment : Fragment(R.layout.menu) {
    private var binding: MenuBinding? = null
    private val host get() = requireActivity() as MainActivity
    private val channels = TVList.list.values.flatten().map(::TVViewModel)
    internal var requestPrimary: (TVViewModel) -> Unit = Request::fetchData
    private var current = 0
    internal val currentChannel: TVViewModel? get() = channels.getOrNull(current)
    private var grid: Boolean? = null
    private var cards: CardAdapter? = null
    private val guideAfterTune = Runnable { requestGuide(current) }
    private val guideRequests = mutableMapOf<Int, Long>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    // Group name -> index of its first channel in `channels`. Same iteration
    // order the channel list was flattened in, so the offsets line up.
    private val groupStarts: Map<String, Int> by lazy {
        var offset = 0
        val starts = linkedMapOf<String, Int>()
        TVList.list.forEach { (name, list) ->
            starts[name] = offset
            offset += list.size
        }
        starts
    }
    private var chips: LinearLayout? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ui = MenuBinding.bind(view)
        binding = ui
        YSP.init(requireContext())
        current = SP.itemPosition.takeIf { it in channels.indices } ?: 0
        ui.root.setOnClickListener { host.closeBrowser() }
        ui.items.setHasFixedSize(true)
        ui.items.itemAnimator = null
        ui.items.addOnLayoutChangeListener { list, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) resizeCards(list.width)
        }
        buildGroups(ui.groups)
        channels.forEachIndexed { index, channel ->
            channel.change.observe(viewLifecycleOwner) { reason ->
                if (index == current) {
                    Request.stopPlaybackRequests()
                    host.selectChannel(channel, reason != "retry", reason == "init")
                    if (reason != "retry") channel.setErrInfo("")
                    when {
                        // Start from the last decoded backup, or the catalog for backup-only channels.
                        channel.getTV().backupOnly || (reason != "retry" && host.playbackSettings().hasRememberedBackup(channel)) -> {
                            if (!host.tryBackup(channel, beforePrimary = true)) {
                                channel.setErrInfo(getString(R.string.source_unavailable))
                            }
                        }
                        else -> {
                            host.playbackSettings().beginPrimary()
                            if (channel.getTV().pid.isNotEmpty()) requestPrimary(channel) else playIfReady(channel)
                        }
                    }
                    host.showInfoFragment(channel)
                }
            }
            channel.ready.observe(viewLifecycleOwner) { if (index == current) playIfReady(channel) }
            channel.errInfo.observe(viewLifecycleOwner) { message ->
                if (index != current) return@observe
                if (!message.isNullOrEmpty()) host.playbackSettings().sourceFailed()
                if (!message.isNullOrEmpty() && host.tryBackup(channel)) {
                    Request.stopPlaybackRequests()
                } else {
                    host.setPlaybackError(message.orEmpty())
                }
            }
            channel.epg.observe(viewLifecycleOwner) {
                if (index == current && !isHidden) renderProgram()
                val holder = ui.items.findViewHolderForAdapterPosition(index) as? CardAdapter.Holder
                holder?.render(index)
            }
        }
        host.fragmentReady(TAG)
    }

    private fun playIfReady(channel: TVViewModel) {
        val index = channel.videoIndex.value ?: -1
        if (!channel.videoUrl.value?.getOrNull(index).isNullOrBlank()) {
            host.play(channel)
        } else if (channel.getTV().pid.isEmpty()) {
            channel.setErrInfo(getString(R.string.source_unavailable))
        }
    }

    fun fragmentReady() {
        currentChannel?.let {
            it.changed("init")
            scheduleGuideAfterTune()
        }
    }

    fun play(position: Int) {
        if (position !in channels.indices) {
            Toast.makeText(requireContext(), R.string.channel_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val previous = current
        current = position
        SP.itemPosition = position
        cards?.currentPosition = current
        if (previous != current) {
            cards?.notifyItemChanged(previous)
            cards?.notifyItemChanged(current)
            syncGroups()
        }
        channels[current].changed("tune")
        scheduleGuideAfterTune()
    }

    private fun scheduleGuideAfterTune() {
        binding?.root?.run {
            removeCallbacks(guideAfterTune)
            postDelayed(guideAfterTune, 250)
        }
    }

    fun steppedPosition(from: Int?, delta: Int) =
        ((from ?: current) + delta + channels.size) % channels.size

    fun step(delta: Int) {
        if (channels.isNotEmpty()) play(steppedPosition(null, delta))
    }

    private fun select(position: Int) {
        if (current != position || !currentChannel?.errInfo?.value.isNullOrEmpty()) play(position)
        host.closeBrowser()
    }

    internal var fetchGuide: (TVViewModel) -> Unit = { channel ->
        when (channel.getTV().programType) {
            ProgramType.Y_PROTO -> Request.fetchYProtoEPG(channel)
            ProgramType.Y_JCE -> Request.fetchYJceEPG(channel)
            ProgramType.F -> Request.fetchFEPG(channel)
        }
    }

    private fun requestGuide(position: Int) {
        val channel = channels.getOrNull(position) ?: return
        if (channel.getTV().pid.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val last = guideRequests[position]
        val hasUpcoming = (channel.epg.value?.lastOrNull()?.beginTime ?: 0) > Utils.getDateTimestamp()
        val retryAfter = if (hasUpcoming) 60 * 60_000L else 60_000L
        if (last != null && now - last < retryAfter) return
        guideRequests[position] = now
        fetchGuide(channel)
    }

    private fun renderProgram() {
        val ui = binding ?: return
        val channel = currentChannel ?: return
        val guide = channel.epg.value.orEmpty()
        val index = guide.currentProgramIndex(Utils.getDateTimestamp())
        val program = guide.getOrNull(index)
        val next = guide.getOrNull(index + 1)
        fun time(seconds: Int) = timeFormat.format(Date(seconds * 1000L))

        ui.station.text = channel.getTV().displayTitle()
        ui.program.text = program?.title ?: channel.getTV().displayTitle()
        ui.schedule.text = when {
            program == null -> getString(R.string.live_label)
            next == null -> getString(R.string.live_label) + "　" + time(program.beginTime)
            else -> getString(R.string.live_label) + "　" +
                getString(R.string.program_time, time(program.beginTime), time(next.beginTime))
        }
        ui.upNext.isVisible = next != null
        if (next != null) ui.upNext.text = getString(R.string.next_program, time(next.beginTime), next.title)
    }

    /**
     * One chip per channel group. Pressing OK jumps the browser to that group's
     * first channel — a way to leave a 30-channel 央视 block without 30 presses.
     */
    private fun buildGroups(strip: LinearLayout) {
        if (binding == null) return
        chips = strip
        strip.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        groupStarts.forEach { (name, start) ->
            val chip = inflater.inflate(R.layout.group_chip, strip, false) as TextView
            chip.text = name
            chip.setOnClickListener {
                jumpTo(start)
                host.mainActive()
            }
            chip.setOnFocusChangeListener { _, focused -> if (focused) host.mainActive() }
            // Default focus search treats the cards below as the nearest
            // neighbours and skips straight out of the strip, so step along the
            // row by hand instead of leaving it to directional search.
            chip.setOnKeyListener { view, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val delta = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                val next = strip.indexOfChild(view) + delta
                // Consume even at the ends: letting the default run there drops
                // focus into the card row, which reads as the strip breaking.
                if (next in 0 until strip.childCount) strip.getChildAt(next).requestFocus()
                true
            }
            strip.addView(chip)
        }
        syncGroups()
    }

    /** Makes the chip of the playing channel the selected one. */
    private fun syncGroups() {
        val strip = chips ?: return
        val playing = groupStarts.entries.lastOrNull { it.value <= current }?.key
        for (i in 0 until strip.childCount) {
            strip.getChildAt(i).isSelected = strip.getChildAt(i) is TextView &&
                (strip.getChildAt(i) as TextView).text.toString() == playing
        }
    }

    /** Scrolls the card row to [position] and focuses it without tuning. */
    private fun jumpTo(position: Int) {
        val ui = binding ?: return
        if (position !in channels.indices) return
        val layout = ui.items.layoutManager as? LinearLayoutManager ?: return
        if (layout.orientation == RecyclerView.HORIZONTAL) {
            val space = ui.items.width - ui.items.paddingLeft - ui.items.paddingRight -
                (cards?.cellWidth ?: 0)
            layout.scrollToPositionWithOffset(position, (space / 2).coerceAtLeast(0))
        } else {
            layout.scrollToPosition(position)
        }
        fun focusCard() {
            if (binding !== ui || isHidden) return
            val holder = ui.items.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
                requestGuide(position)
            } else if (ui.items.isLayoutRequested) {
                ui.items.doOnNextLayout { focusCard() }
            }
        }
        ui.items.post { focusCard() }
    }

    /** Position of the card carrying channel number [number] (1-based), if any. */
    internal fun positionOfNumber(number: Int): Int? =
        channels.indexOfFirst { it.getTV().id + 1 == number }.takeIf { it >= 0 }

    /** Jumps to the card carrying channel number [number] (1-based), if any. */
    fun jumpToNumber(number: Int) {
        val index = positionOfNumber(number)
        if (index == null) {
            Toast.makeText(requireContext(), R.string.channel_missing, Toast.LENGTH_SHORT).show()
            return
        }
        jumpTo(index)
    }

    private val typed = ChannelJump(::positionOfNumber)
    private val commitTyped = Runnable {
        binding?.jump?.isVisible = false
        val position = typed.commit()
        if (position == null) Toast.makeText(requireContext(), R.string.channel_missing, Toast.LENGTH_SHORT).show()
        else jumpTo(position)
    }

    /**
     * Digits typed while the browser is open jump the focus instead of tuning,
     * so a press of "1" "2" lands on channel 12 without leaving the browser.
     * Two digits commit immediately; a single digit commits after a short wait.
     */
    fun typeDigit(digit: String) {
        if (binding == null) return
        // A digit is user activity like any other; without this the browser can
        // auto-close between the two digits of a number.
        host.mainActive()
        val complete = typed.type(digit.firstOrNull() ?: return)
        binding?.jump?.apply {
            removeCallbacks(commitTyped)
            text = typed.typed
            isVisible = true
            // Two digits commit at once; one waits for the second.
            if (complete) postDelayed(commitTyped, 0) else postDelayed(commitTyped, ChannelJump.SECOND_DIGIT_TIMEOUT_MS)
        }
    }

    private fun cancelTyped() {
        typed.reset()
        binding?.jump?.apply { removeCallbacks(commitTyped); isVisible = false }
    }

    private fun resizeCards(width: Int) {
        val ui = binding ?: return
        val adapter = cards ?: return
        val available = width - ui.items.paddingLeft - ui.items.paddingRight
        if (available <= 0) return
        val minWidth = (142 * resources.displayMetrics.density).toInt()
        val columns = (available / minWidth).coerceIn(2, 5)
        val cellWidth = available / columns
        if (adapter.cellWidth == cellWidth) return
        adapter.cellWidth = cellWidth
        (ui.items.layoutManager as? GridLayoutManager)?.spanCount = columns
        for (i in 0 until ui.items.childCount) {
            val child = ui.items.getChildAt(i)
            child.layoutParams = child.layoutParams.apply { this.width = cellWidth }
        }
    }

    fun changeMenu() {
        val ui = binding ?: return
        if (isHidden || (grid == SP.grid && cards != null)) return
        val adapter = cards ?: CardAdapter(channels, ::select, { host.mainActive() }, { index ->
            if (!isHidden) requestGuide(index)
        }).also { cards = it }
        adapter.currentPosition = current
        grid = SP.grid
        ui.items.layoutManager = if (SP.grid) GridLayoutManager(requireContext(), 5)
            else LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val heightDp = if (SP.grid) 146 else 78
        ui.items.layoutParams = ui.items.layoutParams.apply {
            height = (resources.displayMetrics.density * heightDp).toInt()
        }
        adapter.cellWidth = 0
        resizeCards(ui.items.width)
        if (ui.items.adapter !== adapter) ui.items.adapter = adapter
        ui.keys.direction.setText(if (SP.grid) R.string.key_grid else R.string.key_horizontal)
    }

    fun focusCurrent() {
        val ui = binding ?: return
        val list = ui.items
        changeMenu()
        renderProgram()
        requestGuide(current)
        val layout = list.layoutManager as LinearLayoutManager
        if (layout.orientation == RecyclerView.HORIZONTAL) {
            val panel = list.parent as View
            val width = list.width.takeIf { it > 0 } ?: (ui.root.rootView.width - panel.paddingLeft - panel.paddingRight)
            resizeCards(width)
            val space = width - list.paddingLeft - list.paddingRight - (cards?.cellWidth ?: 0)
            layout.scrollToPositionWithOffset(current, (space / 2).coerceAtLeast(0))
        } else {
            layout.scrollToPosition(current)
        }
        fun focusCard() {
            if (binding !== ui || isHidden) return
            list.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus()
            for (i in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(i)) as CardAdapter.Holder
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    holder.render(position)
                    requestGuide(position)
                }
            }
        }
        if (list.isLayoutRequested) list.doOnNextLayout { focusCard() } else focusCard()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) focusCurrent() else cancelTyped()
    }

    override fun onDestroyView() {
        binding?.root?.removeCallbacks(guideAfterTune)
        binding?.items?.adapter = null
        binding?.jump?.removeCallbacks(commitTyped)
        chips = null
        binding = null
        cards = null
        grid = null
        super.onDestroyView()
    }

    companion object { const val TAG = "MainFragment" }
}
