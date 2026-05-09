package com.example.notificationreader2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textview.MaterialTextView

class PickAppAdapter(
    private val items: List<PickAppItem>,
    initiallyCheckedPkgs: Set<String> = emptySet()
) : RecyclerView.Adapter<PickAppAdapter.VH>() {

    private val checked = BooleanArray(items.size) { idx ->
        initiallyCheckedPkgs.contains(items[idx].packageName)
    }

    fun getSelectedPackages(): List<String> {
        return items.asSequence()
            .mapIndexedNotNull { idx, item -> if (checked[idx]) item.packageName else null }
            .toList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pick_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], checked[position]) { newChecked ->
            checked[position] = newChecked
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.pickAppIcon)
        private val name: MaterialTextView = itemView.findViewById(R.id.pickAppName)
        private val check: MaterialCheckBox = itemView.findViewById(R.id.pickAppCheck)

        fun bind(item: PickAppItem, isChecked: Boolean, onChecked: (Boolean) -> Unit) {
            icon.setImageDrawable(item.icon)
            name.text = item.appName

            check.setOnCheckedChangeListener(null)
            check.isChecked = isChecked
            check.setOnCheckedChangeListener { _, checked ->
                onChecked(checked)
            }

            itemView.setOnClickListener {
                val next = !check.isChecked
                check.isChecked = next
                onChecked(next)
            }
        }
    }
}

