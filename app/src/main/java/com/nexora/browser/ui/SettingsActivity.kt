package com.nexora.browser.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import android.content.Intent
import kotlin.concurrent.thread
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.nexora.browser.NexoraApp
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.data.DeviceDataUpdater
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && ::binding.isInitialized) {
                binding.switchDownloadNotifications.isChecked = false
                getSharedPreferences(NexoraApp.PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(NexoraApp.KEY_DOWNLOAD_NOTIFICATIONS, false).apply()
                Toast.makeText(this, "Notifications remain off until Android permission is granted.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiFeedback.install(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        title = "Settings"
        binding.btnBackupRestore.setOnClickListener {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }

        val prefs = getSharedPreferences(NexoraApp.PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(NexoraApp.KEY_THEME_MODE, NexoraApp.THEME_SYSTEM)
        binding.rgTheme.setOnCheckedChangeListener(null)
        when (currentTheme) {
            NexoraApp.THEME_LIGHT -> binding.rbLight.isChecked = true
            NexoraApp.THEME_DARK -> binding.rbDark.isChecked = true
            else -> binding.rbSystem.isChecked = true
        }
        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbLight -> NexoraApp.THEME_LIGHT
                R.id.rbDark -> NexoraApp.THEME_DARK
                else -> NexoraApp.THEME_SYSTEM
            }
            prefs.edit().putInt(NexoraApp.KEY_THEME_MODE, mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    NexoraApp.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    NexoraApp.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        binding.switchSoundEffects.isChecked =
            prefs.getBoolean(NexoraApp.KEY_SOUND_EFFECTS, true)
        binding.switchHapticFeedback.isChecked =
            prefs.getBoolean(NexoraApp.KEY_HAPTIC_FEEDBACK, true)
        binding.switchDownloadNotifications.isChecked =
            prefs.getBoolean(NexoraApp.KEY_DOWNLOAD_NOTIFICATIONS, true)

        binding.switchSoundEffects.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(NexoraApp.KEY_SOUND_EFFECTS, checked).apply()
            UiFeedback.install(binding.root)
        }
        binding.switchHapticFeedback.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(NexoraApp.KEY_HAPTIC_FEEDBACK, checked).apply()
        }
        binding.switchDownloadNotifications.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(NexoraApp.KEY_DOWNLOAD_NOTIFICATIONS, checked).apply()
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val currentSearch = prefs.getString(
            NexoraApp.KEY_SEARCH_ENGINE,
            NexoraApp.SEARCH_DUCKDUCKGO
        )
        binding.rgSearchEngine.setOnCheckedChangeListener(null)
        when (currentSearch) {
            NexoraApp.SEARCH_GOOGLE -> binding.rbGoogle.isChecked = true
            NexoraApp.SEARCH_BING -> binding.rbBing.isChecked = true
            NexoraApp.SEARCH_BRAVE -> binding.rbBrave.isChecked = true
            else -> binding.rbDuckDuckGo.isChecked = true
        }
        binding.rgSearchEngine.setOnCheckedChangeListener { _, checkedId ->
            val engine = when (checkedId) {
                R.id.rbGoogle -> NexoraApp.SEARCH_GOOGLE
                R.id.rbBing -> NexoraApp.SEARCH_BING
                R.id.rbBrave -> NexoraApp.SEARCH_BRAVE
                else -> NexoraApp.SEARCH_DUCKDUCKGO
            }
            prefs.edit().putString(NexoraApp.KEY_SEARCH_ENGINE, engine).apply()
        }

        binding.etCatalogApiBase.setText(
            prefs.getString(NexoraApp.KEY_CATALOG_API_BASE, "").orEmpty()
        )

        fun showCatalogStatus(message: String? = null) {
            val version = DeviceDb.dataVersion(this).ifBlank { "bundled" }
            val chrome = DeviceDb.chromeVersion(this).ifBlank { "not verified" }
            binding.tvDataVersion.text = message ?: "Catalog: $version  ·  Chrome: $chrome"
            binding.btnRollbackData.isEnabled = DeviceDataUpdater.hasPrevious(this)
        }

        showCatalogStatus()
        binding.btnUpdateData.setOnClickListener {
            val apiBase = binding.etCatalogApiBase.text?.toString()?.trim().orEmpty()
            prefs.edit().putString(NexoraApp.KEY_CATALOG_API_BASE, apiBase).apply()
            binding.btnUpdateData.isEnabled = false
            binding.tvDataVersion.text = "Checking the NEXORA catalog..."
            thread(name = "nexora-manual-catalog-check") {
                val result = DeviceDataUpdater.checkNow(applicationContext, apiBase)
                runOnUiThread {
                    binding.btnUpdateData.isEnabled = true
                    when (result) {
                        DeviceDataUpdater.Result.UPDATED ->
                            showCatalogStatus("Catalog updated. New profiles will use the new data.")
                        DeviceDataUpdater.Result.ALREADY_CURRENT ->
                            showCatalogStatus("Your catalog is already up to date.")
                        DeviceDataUpdater.Result.NOT_CONFIGURED ->
                            showCatalogStatus("Enter your HTTPS NEXORA API origin first.")
                        DeviceDataUpdater.Result.INVALID_DATA ->
                            showCatalogStatus("The server returned invalid catalog data. Current data was kept.")
                        DeviceDataUpdater.Result.OFFLINE_OR_ERROR ->
                            showCatalogStatus("Could not reach the server. Your current catalog was kept.")
                    }
                }
            }
        }

        binding.btnRollbackData.setOnClickListener {
            val restored = DeviceDataUpdater.rollback(applicationContext)
            showCatalogStatus(
                if (restored) "Previous catalog restored."
                else "No previous catalog is available."
            )
        }
    }
}
