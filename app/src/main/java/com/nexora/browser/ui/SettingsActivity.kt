package com.nexora.browser.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.nexora.browser.NexoraApp
import com.nexora.browser.R
import com.nexora.browser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        title = "Settings"

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

        fun showDataVersion() {
            val v = com.nexora.browser.data.DeviceDb.dataVersion(this)
            binding.tvDataVersion.text =
                "Data version: ${v.ifBlank { "bundled" }}  ·  Chrome ${com.nexora.browser.data.DeviceDb.chromeVersion(this).ifBlank { "-" }}"
        }
        showDataVersion()
        binding.btnUpdateData.setOnClickListener {
            binding.btnUpdateData.isEnabled = false
            binding.tvDataVersion.text = "Checking for data update..."
            kotlin.concurrent.thread(name = "device-data-manual-check") {
                val result = com.nexora.browser.data.DeviceDataUpdater.checkNow(this)
                runOnUiThread {
                    binding.btnUpdateData.isEnabled = true
                    when (result) {
                        com.nexora.browser.data.DeviceDataUpdater.Result.UPDATED ->
                            binding.tvDataVersion.text = "Updated. New profiles will use the fresh data."
                        com.nexora.browser.data.DeviceDataUpdater.Result.ALREADY_CURRENT ->
                            showDataVersion()
                        com.nexora.browser.data.DeviceDataUpdater.Result.INVALID_DATA ->
                            binding.tvDataVersion.text = "Update found but failed validation. Current data kept."
                        else ->
                            binding.tvDataVersion.text = "Could not check (offline?). Current data kept."
                    }
                    if (result == com.nexora.browser.data.DeviceDataUpdater.Result.UPDATED) {
                        binding.tvDataVersion.text =
                            "Updated to ${com.nexora.browser.data.DeviceDb.dataVersion(this)}. New profiles will use the fresh data."
                    }
                }
            }
        }
    }
}
