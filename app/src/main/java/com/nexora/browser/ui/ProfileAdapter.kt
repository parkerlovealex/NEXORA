package com.nexora.browser.ui

import android.view.LayoutInflater
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.databinding.ItemProfileBinding

class ProfileAdapter(
    private val presets: List<DevicePreset>,
    private val onLaunch: (BrowserProfile) -> Unit,
    private val onEdit: (BrowserProfile) -> Unit,
    private val onDuplicate: (BrowserProfile) -> Unit,
    private val onDelete: (BrowserProfile) -> Unit,
    private val onClearData: (BrowserProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var items: List<BrowserProfile> = emptyList()

    fun submit(list: List<BrowserProfile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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
            binding.profileStatus.text = if (profile.hasProxy) "PROXY CONFIGURED" else "LOCAL PROFILE"

            binding.btnLaunch.setOnClickListener { onLaunch(profile) }
            binding.root.setOnClickListener { onLaunch(profile) }
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
