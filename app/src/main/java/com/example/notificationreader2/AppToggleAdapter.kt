package com.example.notificationreader2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textview.MaterialTextView

class AppToggleAdapter(
    private val onToggle: (pkg: String, enabled: Boolean) -> Unit,
    private val onModeChange: (pkg: String, mode: ReadAloudPrefs.AnnouncementMode) -> Unit,
    private val onLongPress: (pkg: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class App(val item: AppToggleItem) : Row()
        object Header : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<AppToggleItem>) {
        submitSections(list, emptyList())
    }

    fun submitSections(selectedItems: List<AppToggleItem>, notifiedItems: List<AppToggleItem>) {
        rows.clear()
        rows.addAll(selectedItems.map { Row.App(it) })
        if (notifiedItems.isNotEmpty()) {
            rows.add(Row.Header)
            rows.addAll(notifiedItems.map { Row.App(it) })
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.App -> VIEW_TYPE_APP
            is Row.Header -> VIEW_TYPE_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_toggle_section, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_toggle, parent, false)
            VH(v, onToggle, onModeChange, onLongPress)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.App -> (holder as VH).bind(row.item)
            is Row.Header -> (holder as HeaderVH).bind()
        }
    }

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: MaterialTextView = itemView.findViewById(R.id.sectionTitle)

        fun bind() {
            title.setText(R.string.notified_apps_section)
        }
    }

    class VH(
        itemView: View,
        private val onToggle: (pkg: String, enabled: Boolean) -> Unit,
        private val onModeChange: (pkg: String, mode: ReadAloudPrefs.AnnouncementMode) -> Unit,
        private val onLongPress: (pkg: String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val name: MaterialTextView = itemView.findViewById(R.id.appName)
        private val pkg: MaterialTextView = itemView.findViewById(R.id.packageName)
        private val modeGroup: MaterialButtonToggleGroup = itemView.findViewById(R.id.announcementModeGroup)

        fun bind(item: AppToggleItem) {
            icon.setImageDrawable(item.icon)
            name.text = item.appName
            pkg.text = item.packageName

            modeGroup.clearOnButtonCheckedListeners()
            modeGroup.check(
                if (!item.enabled) {
                    R.id.offModeButton
                } else when (item.announcementMode) {
                    ReadAloudPrefs.AnnouncementMode.DETAIL -> R.id.detailModeButton
                    ReadAloudPrefs.AnnouncementMode.TITLE_ONLY -> R.id.titleOnlyModeButton
                }
            )
            modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                if (checkedId == R.id.offModeButton) {
                    if (item.enabled) {
                        item.enabled = false
                        onToggle(item.packageName, false)
                    }
                    return@addOnButtonCheckedListener
                }

                val mode = when (checkedId) {
                    R.id.titleOnlyModeButton -> ReadAloudPrefs.AnnouncementMode.TITLE_ONLY
                    else -> ReadAloudPrefs.AnnouncementMode.DETAIL
                }

                // 从「关闭」点到「详细/新消息」时，onToggle 会 refresh；须先把模式写入 prefs，
                // 否则 refresh 仍读到旧模式，界面会错显示成「新消息提醒」。
                if (!item.enabled) {
                    if (item.announcementMode != mode) {
                        item.announcementMode = mode
                        onModeChange(item.packageName, mode)
                    }
                    item.enabled = true
                    onToggle(item.packageName, true)
                    return@addOnButtonCheckedListener
                }

                if (item.announcementMode == mode) return@addOnButtonCheckedListener
                item.announcementMode = mode
                onModeChange(item.packageName, mode)
            }

            itemView.setOnLongClickListener {
                onLongPress(item.packageName)
                true
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_APP = 1
        private const val VIEW_TYPE_HEADER = 2
    }
}
