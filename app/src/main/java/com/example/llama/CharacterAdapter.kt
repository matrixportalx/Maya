package tr.maya

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CharacterAdapter(
    private val onSelect: (MayaCharacter) -> Unit,
    private val onLongClick: (MayaCharacter) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.VH>() {

    var activeId: String? = null
    private val items = mutableListOf<MayaCharacter>()

    fun submitList(list: List<MayaCharacter>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val emoji: TextView = v.findViewById(R.id.char_emoji)
        val name: TextView = v.findViewById(R.id.char_name)
        val userName: TextView = v.findViewById(R.id.char_user_name)
        val activeIndicator: TextView = v.findViewById(R.id.char_active_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_character, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val char = items[position]
        holder.emoji.text = char.emoji
        holder.name.text = char.name
        holder.userName.text = "👤 ${char.userName}"
        holder.activeIndicator.visibility = if (char.id == activeId) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onSelect(char) }
        holder.itemView.setOnLongClickListener { onLongClick(char); true }
    }

    override fun getItemCount() = items.size
}
