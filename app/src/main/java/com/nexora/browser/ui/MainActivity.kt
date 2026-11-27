package com.nexora.browser.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.graphics.Color
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewFeature
import com.nexora.browser.R
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.databinding.ActivityMainBinding
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProfileStore
    private lateinit var adapter: ProfileAdapter
    private var presets: List<DevicePreset> = emptyList()
    private var allProfiles: List<BrowserProfile> = emptyList()
    private var uiReady = false
    private var filterMode = FILTER_ALL
    private var selectionMode = false
    private val selectedProfileIds = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostic: show the previous run's crash report, if one was saved.
        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val report = try { crashFile.readText() } catch (e: Exception) { "unreadable" }
            crashFile.delete()
            val tv = TextView(this)
            tv.text = "NEXORA crash report (screenshot this):\n\n$report"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.DKGRAY)
            tv.textSize = 11f
            tv.setPadding(24, 24, 24, 24)
            val sv = ScrollView(this)
            sv.addView(tv)
            setContentView(sv)
            return
        }
        try {
            onCreateReal(savedInstanceState)
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val tv = TextView(this)
            tv.text = "NEXORA crash in MainActivity (screenshot this):\n\n$sw"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.DKGRAY)
            tv.textSize = 11f
            tv.setPadding(24, 24, 24, 24)
            val sv = ScrollView(this)
            sv.addView(tv)
            setContentView(sv)
        }
    }

    private fun onCreateReal(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiFeedback.install(binding.root)
        setSupportActionBar(binding.toolbar)

        store = ProfileStore(this)
        presets = DeviceDb.load(this)

        adapter = ProfileAdapter(
            presets = presets,
            onLaunch = { launchProfile(it) },
            onEdit = { editProfile(it) },
            onDuplicate = { duplicateProfile(it) },
            onDelete = { confirmDelete(it) },
            onClearData = { clearProfileData(it) },
            onSelectionChanged = { ids ->
                selectedProfileIds.clear()
                selectedProfileIds.addAll(ids)
                updateSelectionUi()
            }
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.isNestedScrollingEnabled = false
        binding.profileList.adapter = adapter

        bindDashboardActions()

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }

        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll(allProfiles.map { it.id })
        }
        binding.btnDeselectAll.setOnClickListener {
            adapter.clearSelection()
        }
        binding.btnBulkExport.setOnClickListener {
            exportSelectedProfiles()
        }
        binding.btnBulkDelete.setOnClickListener {
            confirmDeleteSelectedProfiles()
        }

        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            filterMode = when (checkedId) {
                R.id.chipProxied -> FILTER_PROXIED
                R.id.chipRecent -> FILTER_RECENT
                else -> FILTER_ALL
            }
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // onResume can run even when onCreate returned early to show a crash report.
        // Only touch lateinit UI/store fields after the full screen setup succeeded.
        uiReady = true
    }

    private fun bindDashboardActions() {
        binding.btnCreateProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
        binding.btnLaunchRecent.setOnClickListener {
            launchMostRecentProfile()
        }
        binding.cardProfileStudio.setOnClickListener {
            startActivity(Intent(this, ProfileStudioActivity::class.java))
        }
        binding.cardUserAgent.setOnClickListener {
            startActivity(Intent(this, UserAgentGeneratorActivity::class.java))
        }
        binding.cardProxyCenter.setOnClickListener {
            startActivity(Intent(this, ProxyLibraryActivity::class.java))
        }
        binding.cardPrivacyCenter.setOnClickListener {
            withRequiredProfile { profile ->
                startActivity(
                    Intent(this, PrivacyCenterActivity::class.java)
                        .putExtra(PrivacyCenterActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
        }
        binding.cardFingerprint.setOnClickListener {
            withRequiredProfile { profile ->
                startActivity(
                    Intent(this, EditProfileActivity::class.java)
                        .putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
        }
        binding.cardCompatibility.setOnClickListener {
            withRequiredProfile { profile ->
                startActivity(
                    Intent(this, CompatibilityLabActivity::class.java)
                        .putExtra(CompatibilityLabActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
        }
        binding.cardAutomation.setOnClickListener {
            startActivity(Intent(this, AutomationCenterActivity::class.java))
        }
        binding.cardBackup.setOnClickListener {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }
        binding.cardFeatureCenter.setOnClickListener {
            startActivity(Intent(this, FeatureCenterActivity::class.java))
        }
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            UiFeedback.tap(this, binding.bottomNavigation)
            when (item.itemId) {
                R.id.nav_home -> binding.mainScroll.smoothScrollTo(0, 0)
                R.id.nav_profiles -> binding.mainScroll.post {
                    binding.mainScroll.smoothScrollTo(0, binding.profilesSection.top)
                }
                R.id.nav_tools -> startActivity(Intent(this, FeatureCenterActivity::class.java))
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.mainScroll.smoothScrollTo(0, 0)
                R.id.nav_profiles -> binding.mainScroll.post {
                    binding.mainScroll.smoothScrollTo(0, binding.profilesSection.top)
                }
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun launchMostRecentProfile() {
        val profile = readLocalStore { store.loadAll() }.getOrElse { return }.maxByOrNull { it.lastUsedAt }
        if (profile == null) {
            toast("Create a profile before opening the browser.")
            startActivity(Intent(this, EditProfileActivity::class.java))
        } else {
            launchProfile(profile)
        }
    }

    private fun withRequiredProfile(action: (BrowserProfile) -> Unit) {
        val profile = readLocalStore { store.loadAll() }.getOrElse { return }.maxByOrNull { it.lastUsedAt }
        if (profile == null) {
            toast("Create a browser profile first.")
            startActivity(Intent(this, EditProfileActivity::class.java))
        } else {
            action(profile)
        }
    }

    override fun onResume() {
        super.onResume()
        if (uiReady) {
            refresh()
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.itemSelectProfiles)?.title =
            if (selectionMode) "Finish selection" else "Select profiles"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (::binding.isInitialized) UiFeedback.tap(this, binding.toolbar)
        return when (item.itemId) {
            R.id.itemSelectProfiles -> {
                setSelectionMode(!selectionMode)
                true
            }
            R.id.itemFeatureCenter -> { startActivity(Intent(this, FeatureCenterActivity::class.java)); true }
            R.id.itemBulkCreate -> { startActivity(Intent(this, ProfileStudioActivity::class.java)); true }
            R.id.itemUserAgentGenerator -> { startActivity(Intent(this, UserAgentGeneratorActivity::class.java)); true }
            R.id.itemProxyManager -> { startActivity(Intent(this, ProxyLibraryActivity::class.java)); true }
            R.id.itemAutomationCenter -> { startActivity(Intent(this, AutomationCenterActivity::class.java)); true }
            R.id.itemSettings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.itemAbout -> {
                startActivity(Intent(this, AboutActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------ list

    private fun refresh() {
        allProfiles = readLocalStore { store.loadAll() }.getOrElse {
            binding.emptyText.text = it.message
            binding.emptyText.visibility = View.VISIBLE
            binding.profileList.visibility = View.GONE
            return
        }.sortedByDescending { it.lastUsedAt }
        adapter.retainSelection(allProfiles.map { it.id }.toSet())
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        binding.tvProfileCount.text = allProfiles.size.toString()
        binding.tvProxyCount.text = allProfiles.count { it.hasProxy }.toString()
        binding.tvRecentCount.text = allProfiles.count { it.lastUsedAt >= cutoff && it.lastUsedAt > 0L }.toString()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val modeFiltered = when (filterMode) {
            FILTER_PROXIED -> allProfiles.filter { it.hasProxy }
            FILTER_RECENT -> allProfiles.filter { it.lastUsedAt >= cutoff && it.lastUsedAt > 0L }
            else -> allProfiles
        }
        val filtered = if (q.isEmpty()) {
            modeFiltered
        } else {
            modeFiltered.filter { p ->
                val preset = presets.firstOrNull { it.id == p.deviceId }
                p.name.lowercase().contains(q) ||
                    (preset?.displayName?.lowercase()?.contains(q) == true) ||
                    (preset?.model?.lowercase()?.contains(q) == true) ||
                    p.proxyHost.lowercase().contains(q)
            }
        }
        adapter.submit(filtered)
        binding.emptyText.text = if (allProfiles.isEmpty()) {
            getString(R.string.empty_profiles)
        } else {
            "No profiles match this filter."
        }
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.profileList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ actions

    private fun launchProfile(profile: BrowserProfile) {
        if (!store.update(profile.copy(lastUsedAt = System.currentTimeMillis()))) {
            toast("Could not save profile changes. Check device storage and retry.")
            return
        }
        refresh()
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra(BrowserActivity.EXTRA_PROFILE_ID, profile.id)
        startActivity(intent)
    }

    private fun editProfile(profile: BrowserProfile) {
        val intent = Intent(this, EditProfileActivity::class.java)
        intent.putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id)
        startActivity(intent)
    }

    private fun duplicateProfile(profile: BrowserProfile) {
        val now = System.currentTimeMillis()
        val copy = profile.copy(
            id = UUID.randomUUID().toString(),
            name = profile.name + " copy",
            createdAt = now,
            lastUsedAt = 0L
        )
        if (store.add(copy)) {
            toast("Duplicated as \"${copy.name}\"")
            refresh()
        } else {
            toast("Could not duplicate profile. Check device storage and try again.")
        }
    }

    private fun confirmDelete(profile: BrowserProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage(
                "Delete \"${profile.name}\"? Its profile record will be removed. " +
                    "On WebView providers without isolated profile storage, shared browsing data is kept " +
                    "so deleting one profile cannot erase another profile's cookies."
            )
            .setPositiveButton("Delete") { _, _ ->
                val deleted = store.deleteMany(setOf(profile.id))
                if (deleted) {
                    clearProfileDataForDeletion(profile)
                    toast("Profile deleted")
                    refresh()
                } else {
                    toast("Could not save profile changes. Nothing was deleted.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        adapter.setSelectionMode(enabled)
        updateSelectionUi()
        invalidateOptionsMenu()
    }

    private fun updateSelectionUi() {
        if (!uiReady && !::binding.isInitialized) return
        binding.selectionToolbar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectedCount.text = "${selectedProfileIds.size} selected"
        val hasSelection = selectedProfileIds.isNotEmpty()
        binding.btnDeselectAll.isEnabled = hasSelection
        binding.btnBulkExport.isEnabled = hasSelection
        binding.btnBulkDelete.isEnabled = hasSelection
        binding.btnSelectAll.isEnabled = allProfiles.isNotEmpty()
    }

    private fun exportSelectedProfiles() {
        val selected = allProfiles.filter { it.id in selectedProfileIds }
        if (selected.isEmpty()) {
            toast("Select at least one profile")
            return
        }
        val array = JSONArray()
        selected.forEach { profile ->
            val json = profile.toJson()
            // Profile exports must never include proxy credentials.
            json.remove("proxyPass")
            json.remove("proxyUser")
            json.remove("proxyPassEncrypted")
            array.put(json)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "NEXORA profile export")
            putExtra(Intent.EXTRA_TEXT, array.toString(2))
        }
        startActivity(Intent.createChooser(send, "Export selected profiles"))
    }

    private fun confirmDeleteSelectedProfiles() {
        val selected = allProfiles.filter { it.id in selectedProfileIds }
        if (selected.isEmpty()) {
            toast("Select at least one profile")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} profiles?")
            .setMessage(
                "The selected profile records will be removed. Profile-specific WebView data " +
                    "will be cleared only when the installed WebView supports isolated profile storage. " +
                    "Shared browsing data is never cleared by this bulk action."
            )
            .setPositiveButton("Delete") { _, _ ->
                val deleted = store.deleteMany(selected.map { it.id }.toSet())
                if (deleted) {
                    selected.forEach { clearProfileDataForDeletion(it) }
                    setSelectionMode(false)
                    refresh()
                    toast("${selected.size} profiles deleted")
                } else {
                    toast("Could not save profile changes. Nothing was deleted.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A single-profile delete must not clear the shared WebView store on older
     * providers. Only remove data when AndroidX WebKit confirms multi-profile
     * storage is supported.
     */
    private fun clearProfileDataForDeletion(profile: BrowserProfile) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        clearIsolatedProfileData(profile)
    }

    private fun clearProfileData(profile: BrowserProfile) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            clearIsolatedProfileData(profile)
            toast("Browsing data cleared for \"${profile.name}\"")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear shared browsing data?")
            .setMessage(
                "This WebView provider does not support isolated profile storage. " +
                    "Continuing will clear cookies and website storage for every NEXORA profile."
            )
            .setPositiveButton("Clear all profiles' data") { _, _ ->
                clearSharedBrowsingData()
                toast("Shared browsing data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearIsolatedProfileData(profile: BrowserProfile) {
        try {
            val webProfile = androidx.webkit.ProfileStore.getInstance()
                .getProfile(profile.webViewProfileKey)
            if (webProfile != null) {
                webProfile.cookieManager.removeAllCookies(null)
                webProfile.cookieManager.flush()
                webProfile.webStorage.deleteAllData()
            }
        } catch (_: Exception) {
            // The profile may not have been launched on this WebView provider.
        }
    }

    private fun clearSharedBrowsingData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_PROXIED = 1
        private const val FILTER_RECENT = 2
        private const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
