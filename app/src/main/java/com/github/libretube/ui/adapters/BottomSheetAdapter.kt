package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.databinding.BottomSheetItemBinding
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.obj.bottomsheetitem.BottomSheetItemTitle
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.extensions.setDrawables
import com.github.libretube.ui.viewholders.BottomSheetViewHolder

class BottomSheetAdapter(
    private val listener: (index: Int) -> Unit
) : ListAdapter<BottomSheetItem, BottomSheetViewHolder>(
    DiffUtilItemCallback()
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BottomSheetViewHolder {
        val binding = BottomSheetItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BottomSheetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BottomSheetViewHolder, position: Int) {
        val item = getItem(position)!!
        holder.binding.root.apply {
            val current = item.getCurrent()?.getText(context)
            val title = item.title.getText(context)
            text = if (current != null) "$title ($current)" else title
            isSelected = item.isSelected
            setDrawables(start = item.drawable)

            setOnClickListener {
                // the row's position now, not when it was bound: options added to the top of the
                // list later (e.g. "Play in background") move rows without rebinding them, and the
                // old position made "Watch later" start playback instead
                val current = holder.bindingAdapterPosition
                if (current == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return@setOnClickListener
                getItem(current).onClick.invoke()
                listener.invoke(current)
            }
        }
    }
}
