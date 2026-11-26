package com.nexora.browser.ui

import android.view.LayoutInflater
import android.view.View
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileSelectionState
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.databinding.ItemProfileBinding

class ProfileAdapter(
    private val presets: List<DevicePreset>,
    private val onLaunch: (BrowserProfile) -> Unit,
    private val onEdit: (BrowserProfile) -> Unit,
    private val onDuplicate: (BrowserProfile) -> Unit,
    private val onDelete: (BrowserProfile) -> Unit,
    private val onClearData: (BrowserProfile) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var items: List<BrowserProfile> = emptyList()
    private var selectionMode: Boolean = false
    private val selection = ProfileSelectionState()

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selection.clear()
        notifyDataSetChanged()
        onSelectionChanged(selection.selectedIds)
    }

    fun selectAll(ids: Collection<String>) {
        selection.selectAll(ids)
        notifyDataSetChanged()
        onSelectionChanged(selection.selectedIds)
    }

    fun clearSelection() {
        selection.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    fun retainSelection(validIds: Set<String>) {
        if (selection.retain(validIds)) {
            notifyDataSetChanged()
            onSelectionChanged(selection.selectedIds)
        }
    }

    private fun toggleSelection(profileId: String) {
        if (!selectionMode) return
        selection.toggle(profileId)
        notifyDataSetChanged()
        onSelectionChanged(selection.selectedIds)
    }

    fun submit(list: List<BrowserProfile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        UiFeedback.install(binding.root)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: BrowserProfile) {
            val preset = presets.firstOrNull { it.id == profile.deviceId }
            binding.profileName.text = profile.name
            binding.profileMeta.text = metaLine(profile, preset)
            binding.profileStatus.text = if (profile.hasProxy) "PROXY ASSIGNED · VERIFY ON OPEN" else "DIRECT CONNECTION"

            binding.profileSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.profileSelect.setOnCheckedChangeListener(null)
            binding.profileSelect.isChecked = selection.contains(profile.id)
            binding.profileSelect.setOnCheckedChangeListener { _, checked ->
                if (selectionMode) {
                    selection.setSelected(profile.id, checked)
                    onSelectionChanged(selection.selectedIds)
                }
            }
            binding.btnLaunch.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.btnOverflow.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.btnLaunch.setOnClickListener {
                if (!selectionMode) onLaunch(profile)
            }
            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(profile.id) else onLaunch(profile)
            }
            binding.btnOverflow.setOnClickListener { view ->
                val menu = PopupMenu(view.context, view)
                menu.menu.add(0, ACTION_EDIT, 0, "Edit")
                menu.menu.add(0, ACTION_DUPLICATE, 1, "Duplicate")
                menu.menu.add(0, ACTION_CLEAR, 2, "Clear data")
                menu.menu.add(0, ACTION_DELETE, 3, "Delete")
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        ACTION_EDIT -> onEdit(profile)
                        ACTION_DUPLICATE -> onDuplicate(profile)
                        ACTION_CLEAR -> onClearData(profile)
                        ACTION_DELETE -> onDelete(profile)
                    }
                    true
                }
                menu.show()
            }
        }

        private fun metaLine(profile: BrowserProfile, preset: DevicePreset?): String {
            val sb = StringBuilder()
            sb.append(preset?.displayName ?: profile.deviceId)
            if (preset != null) {
                if (preset.isIos) sb.append(" · iOS ").append(profile.osVersion).append(" (emulation)")
                else sb.append(" · Android ").append(profile.osVersion)
            }
            if (profile.groupName.isNotBlank()) {
                sb.append(" · Group: ").append(profile.groupName)
            }
            if (profile.hasProxy) {
                sb.append(" · ").append(profile.proxyType).append(" proxy")
            } else {
                sb.append(" · Direct connection")
            }
            if (profile.lastUsedAt > 0L) {
                sb.append(" · Used ").append(
                    DateUtils.getRelativeTimeSpanString(
                        profile.lastUsedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                )
            } else {
                sb.append(" · Not opened yet")
            }
            return sb.toString()
        }
    }

    companion object {
        private const val ACTION_EDIT = 1
        private const val ACTION_DUPLICATE = 2
        private const val ACTION_CLEAR = 3
        private const val ACTION_DELETE = 4
    }
}
