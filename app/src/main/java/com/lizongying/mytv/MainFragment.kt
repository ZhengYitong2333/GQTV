package com.lizongying.mytv

import android.os.Bundle
import android.os.SystemClock
import android.view.View
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
    private var current = 0
    internal val currentChannel: TVViewModel? get() = channels.getOrNull(current)
    private var grid: Boolean? = null
    private var cards: CardAdapter? = null
    private val guideRequests = mutableMapOf<Int, Long>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
        channels.forEachIndexed { index, channel ->
            channel.change.observe(viewLifecycleOwner) { reason ->
                if (index == current) {
                    Request.stopPlaybackRequests()
                    host.selectChannel(channel, reason != "retry")
                    if (reason != "retry") channel.setErrInfo("")
                    if (channel.getTV().pid.isNotEmpty()) {
                        Request.fetchData(channel)
                    } else {
                        playIfReady(channel)
                    }
                    host.showInfoFragment(channel)
                }
            }
            channel.ready.observe(viewLifecycleOwner) { if (index == current) playIfReady(channel) }
            channel.errInfo.observe(viewLifecycleOwner) { message ->
                if (index != current) return@observe
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
            requestGuide(current)
            it.changed("init")
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
        }
        requestGuide(current)
        channels[current].changed("tune")
    }

    fun step(delta: Int) {
        if (channels.isNotEmpty()) play((current + delta + channels.size) % channels.size)
    }

    private fun select(position: Int) {
        if (current != position || !currentChannel?.errInfo?.value.isNullOrEmpty()) play(position)
        host.closeBrowser()
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
        when (channel.getTV().programType) {
            ProgramType.Y_PROTO -> Request.fetchYProtoEPG(channel)
            ProgramType.Y_JCE -> Request.fetchYJceEPG(channel)
            ProgramType.F -> Request.fetchFEPG(channel)
        }
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
        if (!hidden) focusCurrent()
    }

    override fun onDestroyView() {
        binding?.items?.adapter = null
        binding = null
        cards = null
        grid = null
        super.onDestroyView()
    }

    companion object { const val TAG = "MainFragment" }
}
