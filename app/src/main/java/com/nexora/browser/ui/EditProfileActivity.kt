package com.nexora.browser.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.databinding.ActivityEditProfileBinding
import com.nexora.browser.util.ProxyTester
import com.nexora.browser.util.UaBuilder
import java.util.UUID
import kotlin.random.Random

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var store: ProfileStore
    private var presets: List<DevicePreset> = emptyList()
    private var editing: BrowserProfile? = null

    /** False while the form is being populated programmatically. */
    private var ready = false

    /** True once the user types in the UA field — device changes then stop overwriting it. */
    private var uaEdited = false

    /** Suppresses the UA watcher while code sets the UA text. */
    private var suppressUaWatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        store = ProfileStore(this)
        presets = DeviceDb.load(this)
        if (presets.isEmpty()) {
            toast("Device database could not be loaded")
            finish()
            return
        }

        setupSpinners()
        setupListeners()

        val editId = intent.getStringExtra(EXTRA_PROFILE_ID)
        editing = editId?.let { store.getById(it) }
        val existing = editing
        if (existing != null) {
            title = "Edit profile"
            binding.toolbar.title = "Edit profile"
            prefill(existing)
        } else {
            title = "New profile"
            binding.toolbar.title = "New profile"
            binding.spDevice.setSelection(0)
            val preset = presets[0]
            populateDeviceFields(preset, null, null, null)
            regenerateUa()
        }
        ready = true

        binding.btnRegenUa.setOnClickListener {
            uaEdited = false
            regenerateUa()
        }
        binding.btnTestProxy.setOnClickListener { testProxy() }
        binding.btnSave.setOnClickListener { save() }
    }

    // ------------------------------------------------------------ setup

    private fun setupSpinners() {
        val deviceLabels = presets.map { p ->
            if (p.isIos) "${p.displayName} (iOS emulation)" else p.displayName
        }
        binding.spDevice.adapter = spinnerAdapter(deviceLabels)
        binding.spApp.adapter = spinnerAdapter(
            listOf("Browser") + com.nexora.browser.util.AppUa.APPS.map { it.label }
        )
        binding.spProxyType.adapter = spinnerAdapter(PROXY_LABELS)
        binding.spTimezone.adapter = spinnerAdapter(TIMEZONE_LABELS)
        binding.spLanguageMode.adapter = spinnerAdapter(listOf("Device language", "Based on IP", "Custom language"))
        binding.spWebrtc.adapter = spinnerAdapter(listOf("Default", "Block WebRTC"))
    }

    private fun setupListeners() {
        binding.spDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!ready) return
                val preset = presets.getOrNull(pos) ?: return
                populateDeviceFields(preset, null, null, null)
                if (!uaEdited) regenerateUa()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spOs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (ready && !uaEdited) regenerateUa()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (ready && !uaEdited) regenerateUa()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.etUa.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (!suppressUaWatch) uaEdited = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun populateDeviceFields(
        preset: DevicePreset,
        selOs: String?,
        selRam: Int?,
        selStorage: Int?
    ) {
        binding.spOs.adapter = spinnerAdapter(preset.osVersions)
        val osIndex = if (selOs != null) {
            preset.osVersions.indexOf(selOs).coerceAtLeast(0)
        } else {
            preset.osVersions.lastIndex.coerceAtLeast(0)
        }
        binding.spOs.setSelection(osIndex)

        binding.spRam.adapter = spinnerAdapter(preset.ramOptions.map { "$it GB" })
        val ramIndex = if (selRam != null) {
            preset.ramOptions.indexOf(selRam).coerceAtLeast(0)
        } else 0
        binding.spRam.setSelection(ramIndex)

        binding.spStorage.adapter = spinnerAdapter(preset.storageOptions.map { "$it GB" })
        val storageIndex = if (selStorage != null) {
            preset.storageOptions.indexOf(selStorage).coerceAtLeast(0)
        } else 0
        binding.spStorage.setSelection(storageIndex)
    }

    private fun prefill(p: BrowserProfile) {
        binding.etName.setText(p.name)
        val deviceIndex = presets.indexOfFirst { it.id == p.deviceId }.coerceAtLeast(0)
        binding.spDevice.setSelection(deviceIndex)
        val preset = presets[deviceIndex]
        populateDeviceFields(preset, p.osVersion, p.ramGb, p.storageGb)

        setUaText(p.userAgent)
        uaEdited = true

        binding.spProxyType.setSelection(
            when (p.proxyType) {
                "HTTP" -> 1
                "SOCKS5" -> 2
                else -> 0
            }
        )
        binding.etProxyHost.setText(p.proxyHost)
        binding.etProxyPort.setText(if (p.proxyPort > 0) p.proxyPort.toString() else "")
        binding.etProxyUser.setText(p.proxyUser)
        binding.etProxyPass.setText(p.proxyPass)

        val tzIndex = if (p.timezoneMode == "IP") TIMEZONE_VALUES.indexOf("__IP__")
            else TIMEZONE_VALUES.indexOf(p.timezoneId).coerceAtLeast(0)
        binding.spTimezone.setSelection(tzIndex)
        binding.spLanguageMode.setSelection(
            when (p.languageMode) { "IP" -> 1; "CUSTOM" -> 2; else -> 0 }
        )
        binding.etLanguage.setText(p.language)
        binding.spWebrtc.setSelection(if (p.webrtcBlock) 1 else 0)
        binding.cbCanvas.isChecked = p.canvasNoise
        binding.cbWebgl.isChecked = p.webglSpoof
        binding.cbAudio.isChecked = p.audioNoise
        binding.etStartupUrl.setText(p.startupUrl)
    }

    // ------------------------------------------------------------ helpers

    private fun selectedPreset(): DevicePreset? =
        presets.getOrNull(binding.spDevice.selectedItemPosition)

    private fun setUaText(ua: String) {
        suppressUaWatch = true
        binding.etUa.setText(ua)
        suppressUaWatch = false
    }

    private fun regenerateUa() {
        val preset = selectedPreset() ?: return
        val os = binding.spOs.selectedItem?.toString() ?: preset.osVersions.lastOrNull() ?: return
        val chrome = com.nexora.browser.data.DeviceDb.chromeVersion(this)
        val appIndex = binding.spApp.selectedItemPosition
        if (appIndex <= 0) {
            setUaText(UaBuilder.build(preset, os, chrome))
        } else {
            val app = com.nexora.browser.util.AppUa.APPS.getOrNull(appIndex - 1) ?: return
            setUaText(
                com.nexora.browser.util.AppUa.build(
                    app.id, preset, os, chrome,
                    com.nexora.browser.data.DeviceDb.appVersion(this, app.id),
                    com.nexora.browser.data.DeviceDb.appToken(this, app.id)
                )
            )
        }
    }

    private fun formProfile(id: String): BrowserProfile {
        val preset = selectedPreset()
        val proxyType = when (binding.spProxyType.selectedItemPosition) {
            1 -> "HTTP"
            2 -> "SOCKS5"
            else -> "NONE"
        }
        val created = BrowserProfile(
            id = id,
            name = binding.etName.text.toString().trim(),
            deviceId = preset?.id ?: "",
            osVersion = binding.spOs.selectedItem?.toString() ?: "",
            ramGb = parseGb(binding.spRam.selectedItem?.toString()),
            storageGb = parseGb(binding.spStorage.selectedItem?.toString()),
            userAgent = binding.etUa.text.toString().trim(),
            proxyType = proxyType,
            proxyHost = binding.etProxyHost.text.toString().trim(),
            proxyPort = binding.etProxyPort.text.toString().toIntOrNull() ?: 0,
            proxyUser = binding.etProxyUser.text.toString().trim(),
            proxyPass = binding.etProxyPass.text.toString(),
            timezoneId = TIMEZONE_VALUES.getOrElse(binding.spTimezone.selectedItemPosition) { "" }
                .let { if (it == "__IP__") "" else it },
            timezoneMode = when {
                TIMEZONE_VALUES.getOrElse(binding.spTimezone.selectedItemPosition) { "" } == "__IP__" -> "IP"
                TIMEZONE_VALUES.getOrElse(binding.spTimezone.selectedItemPosition) { "" }.isBlank() -> "DEVICE"
                else -> "CUSTOM"
            },
            languageMode = listOf("DEVICE", "IP", "CUSTOM")
                .getOrElse(binding.spLanguageMode.selectedItemPosition) { "DEVICE" },
            language = binding.etLanguage.text.toString().trim().ifEmpty { "en-US" },
            webrtcBlock = binding.spWebrtc.selectedItemPosition == 1,
            canvasNoise = binding.cbCanvas.isChecked,
            webglSpoof = binding.cbWebgl.isChecked,
            audioNoise = binding.cbAudio.isChecked,
            startupUrl = binding.etStartupUrl.text.toString().trim()
        )
        val previous = editing ?: return created
        return created.copy(
            startupUrls = if (created.startupUrl == previous.startupUrl) previous.startupUrls else listOf(created.startupUrl).filter { it.isNotBlank() },
            groupName = previous.groupName,
            remark = previous.remark,
            targetOs = previous.targetOs,
            userAgentMode = previous.userAgentMode,
            timezoneMode = created.timezoneMode,
            languageMode = created.languageMode,
            ipGeoKey = if (created.timezoneMode == previous.timezoneMode &&
                created.languageMode == previous.languageMode) previous.ipGeoKey else "",
            webrtcMode = if (created.webrtcBlock) "DISABLED" else "REAL_IP",
            mediaDevicesNoise = previous.mediaDevicesNoise,
            clientRectsNoise = previous.clientRectsNoise,
            speechSynthesisNoise = previous.speechSynthesisNoise,
            dntMode = previous.dntMode,
            hardwareAcceleration = previous.hardwareAcceleration,
            blockImages = previous.blockImages
        )
    }

    private fun parseGb(label: String?): Int =
        label?.substringBefore(' ')?.toIntOrNull() ?: 0

    // ------------------------------------------------------------ actions

    private fun testProxy() {
        val temp = formProfile(editing?.id ?: "temp")
        if (temp.proxyType == "NONE") {
            binding.tvProxyResult.text = "No proxy set"
            return
        }
        binding.tvProxyResult.text = "Testing…"
        ProxyTester.test(temp) { result ->
            runOnUiThread { binding.tvProxyResult.text = result }
        }
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("Enter a profile name")
            binding.etName.requestFocus()
            return
        }
        val form = formProfile(editing?.id ?: UUID.randomUUID().toString())
        if (form.proxyType != "NONE") {
            if (form.proxyHost.isEmpty()) {
                toast("Enter the proxy host")
                return
            }
            if (form.proxyPort !in 1..65535) {
                toast("Enter a valid proxy port (1–65535)")
                return
            }
        }
        if (form.userAgent.isEmpty()) {
            regenerateUa()
            form.userAgent = binding.etUa.text.toString().trim()
        }

        val existing = editing
        if (existing != null) {
            form.createdAt = existing.createdAt
            form.seed = existing.seed
            form.lastUsedAt = existing.lastUsedAt
            store.update(form)
        } else {
            form.createdAt = System.currentTimeMillis()
            form.seed = Random.nextInt(1, Int.MAX_VALUE)
            form.lastUsedAt = 0L
            store.add(form)
        }
        toast("Profile saved")
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        private val PROXY_LABELS = listOf("None", "HTTP", "SOCKS5")

        private val TIMEZONE_LABELS = listOf(
            "Auto (device)",
            "Based on IP",
            "UTC",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "Europe/London",
            "Europe/Berlin",
            "Asia/Dubai",
            "Asia/Karachi",
            "Asia/Dhaka",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Australia/Sydney"
        )

        private val TIMEZONE_VALUES = listOf(
            "",
            "__IP__",
            "UTC",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "Europe/London",
            "Europe/Berlin",
            "Asia/Dubai",
            "Asia/Karachi",
            "Asia/Dhaka",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Australia/Sydney"
        )
    }
}
