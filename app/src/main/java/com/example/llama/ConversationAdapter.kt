package tr.maya

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import tr.maya.data.Conversation

class ConversationAdapter(
    private val onSelect: (Conversation) -> Unit,
    private val onDelete: (Conversation) -> Unit,
    private val onRename: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    private val items = mutableListOf<Conversation>()
    var activeId: String? = null

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.conv_title)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_conv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val conv = items[position]
        holder.title.text = conv.title
        holder.itemView.isSelected = conv.id == activeId
        holder.itemView.alpha = if (conv.id == activeId) 1f else 0.85f
        holder.itemView.setOnClickListener { onSelect(conv) }
        holder.itemView.setOnLongClickListener { onRename(conv); true }
        holder.btnDelete.setOnClickListener { onDelete(conv) }
    }

    override fun getItemCount() = items.size

    fun submitList(list: List<Conversation>) {
        val oldList = items.toList()
        val newList = list.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = oldList[oldPos]; val n = newList[newPos]
                return o.title == n.title && o.updatedAt == n.updatedAt && o.id == activeId == (n.id == activeId)
            }
        })
        items.clear()
        items.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }
}
