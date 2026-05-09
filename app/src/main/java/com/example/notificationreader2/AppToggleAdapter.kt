package com.example.notificationreader2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

class AppToggleAdapter(
    private val onToggle: (pkg: String, enabled: Boolean) -> Unit,
    private val onLongPress: (pkg: String) -> Unit
) : RecyclerView.Adapter<AppToggleAdapter.VH>() {

    private val items = mutableListOf<AppToggleItem>()

    fun submit(list: List<AppToggleItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_toggle, parent, false)
        return VH(v, onToggle, onLongPress)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(
        itemView: View,
        private val onToggle: (pkg: String, enabled: Boolean) -> Unit,
        private val onLongPress: (pkg: String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val name: MaterialTextView = itemView.findViewById(R.id.appName)
        private val pkg: MaterialTextView = itemView.findViewById(R.id.packageName)
        private val sw: MaterialSwitch = itemView.findViewById(R.id.readSwitch)

        fun bind(item: AppToggleItem) {
            icon.setImageDrawable(item.icon)
            name.text = item.appName
            pkg.text = item.packageName

            fun setSwitchChecked(checked: Boolean, notify: Boolean) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = checked
                sw.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(item.packageName, isChecked)
                }
                if (notify) onToggle(item.packageName, checked)
            }

            setSwitchChecked(item.enabled, notify = false)

            // 允许点开关本身（MaterialSwitch）直接触发
            sw.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item.packageName, isChecked)
            }

            // 点整行也切换开关
            itemView.setOnClickListener {
                val next = !sw.isChecked
                setSwitchChecked(next, notify = true)
            }

            itemView.setOnLongClickListener {
                onLongPress(item.packageName)
                true
            }
        }
    }
}

