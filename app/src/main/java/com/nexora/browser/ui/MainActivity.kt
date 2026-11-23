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
import com.nexora.browser.databinding.ActivityMainBinding
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProfileStore
    private lateinit var adapter: ProfileAdapter
    private var presets: List<DevicePreset> = emptyList()
    private var allProfiles: List<BrowserProfile> = emptyList()
    private var uiReady = false
    private var filterMode = FILTER_ALL

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
        setSupportActionBar(binding.toolbar)

        store = ProfileStore(this)
        presets = DeviceDb.load(this)

        adapter = ProfileAdapter(
            presets = presets,
            onLaunch = { launchProfile(it) },
            onEdit = { editProfile(it) },
            onDuplicate = { duplicateProfile(it) },
            onDelete = { confirmDelete(it) },
            onClearData = { clearProfileData(it) }
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        if (uiReady) refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.itemBulkCreate -> { startActivity(Intent(this, BulkCreateActivity::class.java)); true }
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
        allProfiles = store.loadAll().sortedByDescending { it.lastUsedAt }
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
        store.update(profile.copy(lastUsedAt = System.currentTimeMillis()))
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
        store.add(copy)
        toast("Duplicated as \"${copy.name}\"")
        refresh()
    }

    private fun confirmDelete(profile: BrowserProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage("Delete \"${profile.name}\" and its browsing data?")
            .setPositiveButton("Delete") { _, _ ->
                clearProfileData(profile, silent = true)
                store.delete(profile.id)
                toast("Profile deleted")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Removes the profile's WebView data store (where supported) plus the
     * shared cookie/storage data as a fallback.
     */
    fun clearProfileData(profile: BrowserProfile, silent: Boolean = false) {
        val hasMultiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        if (hasMultiProfile) {
            // Clear only this profile. ProfileStore.deleteProfile() cannot be used
            // while its Profile object has been loaded in this process.
            try {
                val webProfile = androidx.webkit.ProfileStore.getInstance()
                    .getProfile(profile.webViewProfileKey)
                if (webProfile != null) {
                    webProfile.cookieManager.removeAllCookies(null)
                    webProfile.cookieManager.flush()
                    webProfile.webStorage.deleteAllData()
                }
            } catch (e: Exception) {
                // Profile may not exist yet; keep other profiles untouched.
            }
        } else {
            // Older WebView providers share one default data store.
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                // Best effort only.
            }
        }
        if (!silent) toast("Data cleared for \"${profile.name}\"")
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
