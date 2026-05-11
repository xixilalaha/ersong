package com.example.notificationreader2

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textview.MaterialTextView
import java.util.Collections
import java.util.concurrent.Executors

class PickAppAdapter(
    initialItems: List<PickAppItem>,
    initiallyCheckedPkgs: Set<String> = emptySet()
) : RecyclerView.Adapter<PickAppAdapter.VH>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconCache = Collections.synchronizedMap(mutableMapOf<String, Drawable?>())
    private val iconLoader = Executors.newFixedThreadPool(2)
    private val checkedPkgs = initiallyCheckedPkgs.toMutableSet()
    private var allItems: List<PickAppItem> = initialItems
    private var items: List<PickAppItem> = allItems
    private var query: String = ""

    fun getSelectedPackages(): List<String> {
        return checkedPkgs.toList()
    }

    fun submitItems(newItems: List<PickAppItem>) {
        allItems = newItems
        applyFilter()
    }

    fun filter(query: String) {
        this.query = query.trim()
        applyFilter()
    }

    private fun applyFilter() {
        val q = query
        items = if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                item.appName.contains(q, ignoreCase = true) ||
                    item.packageName.contains(q, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        iconLoader.shutdownNow()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pick_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bindIcon(item, iconCache[item.packageName])
        if (!iconCache.containsKey(item.packageName)) {
            iconLoader.execute {
                val drawable = try {
                    holder.itemView.context.packageManager.getApplicationIcon(item.packageName)
                } catch (_: Throwable) {
                    null
                }
                iconCache[item.packageName] = drawable
                mainHandler.post {
                    if (holder.itemView.tag == item.packageName) {
                        holder.bindIcon(item, drawable)
                    }
                }
            }
        }
        holder.bind(item, checkedPkgs.contains(item.packageName)) { newChecked ->
            if (newChecked) {
                checkedPkgs.add(item.packageName)
            } else {
                checkedPkgs.remove(item.packageName)
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.pickAppIcon)
        private val name: MaterialTextView = itemView.findViewById(R.id.pickAppName)
        private val check: MaterialCheckBox = itemView.findViewById(R.id.pickAppCheck)

        fun bindIcon(item: PickAppItem, drawable: Drawable?) {
            itemView.tag = item.packageName
            icon.setImageDrawable(drawable)
            if (drawable == null) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }

        fun bind(item: PickAppItem, isChecked: Boolean, onChecked: (Boolean) -> Unit) {
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
