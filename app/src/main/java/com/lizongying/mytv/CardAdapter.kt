package com.lizongying.mytv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.databinding.CardBinding
import com.lizongying.mytv.models.TV
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.models.currentProgramIndex

internal fun TV.displayTitle(): String =
    if (title.startsWith("CCTV") && title.getOrNull(4)?.isDigit() == true)
        title.replaceFirst("CCTV", "CCTV-")
    else title

internal class CardAdapter(
    private val channels: List<TVViewModel>,
    private val onSelect: (Int) -> Unit,
    private val onFocus: () -> Unit,
    private val onDisplay: (Int) -> Unit
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    var currentPosition = 0
    var cellWidth = 0

    inner class Holder(val binding: CardBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelect(position)
            }
            itemView.setOnFocusChangeListener { view, focused ->
                val scale = if (focused) 1.02f else 1f
                view.scaleX = scale
                view.scaleY = scale
                if (binding.playing.isVisible) tintPlaying(focused)
                if (focused) onFocus()
            }
        }

        private fun tintPlaying(focused: Boolean) {
            val textColors = binding.title.textColors
            val color = if (focused) textColors.getColorForState(focusedState, textColors.defaultColor) else textColors.defaultColor
            binding.playing.setColorFilter(color)
        }

        fun render(position: Int) {
            val model = channels[position]
            val tv = model.getTV()
            val guide = model.epg.value.orEmpty()
            val program = guide.getOrNull(guide.currentProgramIndex(Utils.getDateTimestamp()))?.title
            val title = tv.displayTitle()
            val service = title.substringBefore(' ')
            binding.title.text = service
            binding.category.text = title.removePrefix(service).trim()
            binding.category.isVisible = binding.category.text.isNotEmpty()
            binding.desc.text = program ?: itemView.context.getString(R.string.channel_position, tv.id + 1, tv.channel)
            binding.playing.isVisible = position == currentPosition
            if (binding.playing.isVisible) tintPlaying(itemView.isFocused)
            val current = if (binding.playing.isVisible) itemView.context.getString(R.string.current_channel) else ""
            itemView.contentDescription = listOf(title, binding.desc.text, current)
                .filter { it.isNotEmpty() }.joinToString("，")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(CardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (cellWidth > 0) holder.itemView.layoutParams.width = cellWidth
        holder.render(position)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) onDisplay(position)
    }

    private companion object {
        val focusedState = intArrayOf(android.R.attr.state_focused)
    }
}
