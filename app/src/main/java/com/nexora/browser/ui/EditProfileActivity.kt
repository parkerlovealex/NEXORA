package com.nexora.browser.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.WebSettings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewFeature
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyEntry
import com.nexora.browser.data.ProxyLibraryStore
import com.nexora.browser.databinding.ActivityEditProfileBinding
import com.nexora.browser.util.ProxyTester
import com.nexora.browser.util.ProxyRelayConfig
import com.nexora.browser.util.ProxyLocaleResolver
import com.nexora.browser.util.ProxyLocaleResult
import com.nexora.browser.util.UaBuilder
import com.nexora.browser.util.UiFeedback
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var store: ProfileStore
    private lateinit var proxyLibraryStore: ProxyLibraryStore
    private var proxyEntries: List<ProxyEntry> = emptyList()
    private var proxySelectionValues: List<String> = listOf("MANUAL", "NONE")
    private var proxyManagerRequested = false
    private var presets: List<DevicePreset> = emptyList()
    private var editing: BrowserProfile? = null

    /** False while the form is being populated programmatically. */
    private var ready = false

    /** True when Custom mode is selected — device changes then stop overwriting the UA. */
    private var uaEdited = false

    /** Suppresses the UA watcher while code sets the UA text. */
    private var suppressUaWatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiFeedback.install(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        store = ProfileStore(this)
        proxyLibraryStore = ProxyLibraryStore(this)
        proxyEntries = proxyLibraryStore.loadAll()
        presets = DeviceDb.load(this)
        if (presets.isEmpty()) {
            toast("Device database could not be loaded")
            finish()
            return
        }

        setupSpinners()
        setupListeners()

        val editId = intent.getStringExtra(EXTRA_PROFILE_ID)
        editing = readLocalStore { editId?.let { store.getById(it) } }.getOrElse { finish(); return }
        if (!editId.isNullOrBlank() && editing == null) {
            toast("Profile not found")
            finish()
            return
        }
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
            refreshProxySpinner("NONE")
            regenerateUa()
        }
        ready = true
        updateProxySelection()

        binding.btnRegenUa.setOnClickListener {
            uaEdited = false
            binding.spUaMode.setSelection(0)
            regenerateUa()
            updateUaInputState()
        }
        binding.btnTestProxy.setOnClickListener { testProxy() }
        binding.btnResolveLocale.setOnClickListener { resolveLocaleFromProxy() }
        binding.btnManageProxies.setOnClickListener {
            proxyManagerRequested = true
            startActivity(Intent(this, ProxyLibraryActivity::class.java))
        }
        binding.btnSave.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        if (proxyManagerRequested && ::binding.isInitialized) {
            proxyManagerRequested = false
            val selected = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition)
                ?: editing?.proxyEntryId.orEmpty()
            refreshProxySpinner(selected)
            updateProxySelection()
        }
    }

    // ------------------------------------------------------------ setup

    private fun setupSpinners() {
        val deviceLabels = presets.map { p ->
            if (p.isIos) "${p.displayName} (iOS emulation)" else p.displayName
        }
        binding.spDevice.adapter = spinnerAdapter(deviceLabels)
        binding.spProxyType.adapter = spinnerAdapter(PROXY_LABELS)
        refreshProxySpinner()
        binding.spTimezone.adapter = spinnerAdapter(TIMEZONE_LABELS)
        binding.spWebrtc.adapter = spinnerAdapter(listOf("Allow WebRTC (provider default)", "Restrict WebRTC JavaScript API"))
        binding.spUaMode.adapter = spinnerAdapter(listOf("Auto — generate from device preset", "Custom — use this User-Agent"))
        binding.spLanguageMode.adapter = spinnerAdapter(listOf("Device language", "Custom language tag (JavaScript-level)"))
        binding.spDnt.adapter = spinnerAdapter(listOf("Default", "Enable", "Disable"))
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
                if (ready) refreshDevicePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spSavedProxy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (ready) updateProxySelection()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spUaMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!ready) return
                uaEdited = pos == 1
                if (!uaEdited) regenerateUa()
                updateUaInputState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spTimezone.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (ready) updateTimezoneInputState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spLanguageMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!ready) return
                if (pos == 0) {
                    binding.etLanguage.setText(Locale.getDefault().toLanguageTag())
                }
                updateLanguageInputState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val hardwareSelectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (ready) refreshDevicePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spRam.onItemSelectedListener = hardwareSelectionListener
        binding.spStorage.onItemSelectedListener = hardwareSelectionListener
        binding.etUa.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (!suppressUaWatch) uaEdited = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.etStartupUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val count = ProfileEditorValidator.parseStartupUrls(s?.toString().orEmpty()).size
                binding.tvStartupCount.text = "$count startup tab${if (count == 1) "" else "s"} · max ${ProfileEditorValidator.MAX_STARTUP_URLS}"
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
        refreshDevicePreview()
    }

    private fun refreshDevicePreview() {
        val preset = selectedPreset() ?: return
        val os = binding.spOs.selectedItem?.toString().orEmpty()
        val ram = binding.spRam.selectedItem?.toString().orEmpty()
        val storage = binding.spStorage.selectedItem?.toString().orEmpty()
        val platform = if (preset.isIos) "iOS emulation" else "Android"
        binding.tvDevicePreviewTitle.text = preset.displayName
        binding.tvDevicePreviewMeta.text =
            "$platform $os  ·  $ram RAM  ·  $storage storage  ·  ${preset.cssWidth} × ${preset.cssHeight} CSS px"
    }

    private fun prefill(p: BrowserProfile) {
        binding.etName.setText(p.name)
        binding.etGroupName.setText(p.groupName)
        binding.etRemark.setText(p.remark)

        val deviceIndex = presets.indexOfFirst { it.id == p.deviceId }.coerceAtLeast(0)
        binding.spDevice.setSelection(deviceIndex)
        val preset = presets[deviceIndex]
        populateDeviceFields(preset, p.osVersion, p.ramGb, p.storageGb)

        setUaText(p.userAgent)
        // Recognize older auto-generated UAs even when their Chromium version is stale.
        // Preserve genuinely custom strings rather than silently replacing them.
        uaEdited = p.userAgentMode == "CUSTOM" ||
            (p.userAgent.isNotBlank() && !UaBuilder.isGeneratedFor(preset, p.osVersion, p.userAgent))
        if (!uaEdited) setUaText(buildAutoUa(preset, p.osVersion))
        binding.spUaMode.setSelection(if (uaEdited) 1 else 0)

        binding.spProxyType.setSelection(
            when (p.proxyType) {
                "HTTP" -> 1
                "HTTPS" -> 2
                "SOCKS5" -> 3
                else -> 0
            }
        )
        binding.etProxyHost.setText(p.proxyHost)
        binding.etProxyPort.setText(if (p.proxyPort > 0) p.proxyPort.toString() else "")
        binding.etProxyUser.setText(p.proxyUser)
        binding.etProxyPass.setText(p.proxyPass)
        val proxySelection = when {
            p.proxyEntryId.isNotBlank() -> p.proxyEntryId
            p.proxyType == "NONE" -> "NONE"
            else -> "MANUAL"
        }
        refreshProxySpinner(proxySelection)

        val knownTimezoneIndex = TIMEZONE_VALUES.indexOf(p.timezoneId)
        if (p.timezoneId.isBlank()) {
            binding.spTimezone.setSelection(0)
        } else if (knownTimezoneIndex > 0 && knownTimezoneIndex < CUSTOM_TIMEZONE_INDEX) {
            binding.spTimezone.setSelection(knownTimezoneIndex)
        } else {
            binding.spTimezone.setSelection(CUSTOM_TIMEZONE_INDEX)
            binding.etTimezoneCustom.setText(p.timezoneId)
        }

        val deviceLanguage = Locale.getDefault().toLanguageTag()
        // Legacy single-profile builds stored a custom language string while leaving
        // languageMode at its default. Preserve a differing value instead of silently
        // replacing it when the user edits an unrelated field.
        val customLanguage = p.languageMode == "CUSTOM" ||
            (p.language.isNotBlank() && !p.language.equals(deviceLanguage, ignoreCase = true))
        binding.spLanguageMode.setSelection(if (customLanguage) 1 else 0)
        binding.etLanguage.setText(if (customLanguage) p.language else deviceLanguage)
        binding.spWebrtc.setSelection(if (p.webrtcBlock) 1 else 0)
        binding.cbCanvas.isChecked = p.canvasNoise
        binding.cbWebgl.isChecked = p.webglSpoof
        binding.cbAudio.isChecked = p.audioNoise
        binding.spDnt.setSelection(
            when (p.dntMode) {
                "ENABLE" -> 1
                "DISABLE" -> 2
                else -> 0
            }
        )
        binding.cbHardwareAcceleration.isChecked = p.hardwareAcceleration
        binding.cbBlockImages.isChecked = p.blockImages

        val startup = p.startupUrls.ifEmpty { listOf(p.startupUrl).filter { it.isNotBlank() } }
        binding.etStartupUrl.setText(startup.joinToString("\\n"))
        updateUaInputState()
        updateTimezoneInputState()
        updateLanguageInputState()
        updateStartupCount()
    }

    // ------------------------------------------------------------ helpers

    private fun refreshProxySpinner(preferredValue: String? = null) {
        proxyEntries = proxyLibraryStore.loadAll()
        val wanted = preferredValue.orEmpty()
        val values = mutableListOf("MANUAL", "NONE")
        val labels = mutableListOf("Manual proxy settings", "No Proxy")
        if (wanted.isNotBlank() && wanted !in setOf("MANUAL", "NONE") &&
            proxyEntries.none { it.id == wanted }
        ) {
            values += wanted
            labels += "Missing saved proxy — choose another"
        }
        proxyEntries.forEach { entry ->
            values += entry.id
            labels += "${entry.name} · ${entry.type}" +
                if (entry.hasCredentials) " · Auth" else ""
        }
        proxySelectionValues = values
        binding.spSavedProxy.adapter = spinnerAdapter(labels)
        val selected = values.indexOf(wanted).takeIf { it >= 0 } ?: 0
        binding.spSavedProxy.setSelection(selected)
    }

    private fun setManualProxyEnabled(enabled: Boolean) {
        binding.spProxyType.isEnabled = enabled
        binding.etProxyHost.isEnabled = enabled
        binding.etProxyPort.isEnabled = enabled
        binding.etProxyUser.isEnabled = enabled
        binding.etProxyPass.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.58f
        binding.spProxyType.alpha = alpha
        binding.etProxyHost.alpha = alpha
        binding.etProxyPort.alpha = alpha
        binding.etProxyUser.alpha = alpha
        binding.etProxyPass.alpha = alpha
    }

    private fun updateProxySelection() {
        val selected = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition) ?: "MANUAL"
        when (selected) {
            "MANUAL" -> {
                setManualProxyEnabled(true)
                binding.tvSavedProxyInfo.text =
                    "Manual HTTP, HTTPS and SOCKS5 proxies use the NEXORA relay. Tap Test proxy to verify the endpoint and exit IP before browsing."
            }
            "NONE" -> {
                setManualProxyEnabled(false)
                binding.tvSavedProxyInfo.text = "This profile will connect without a proxy."
            }
            else -> {
                setManualProxyEnabled(false)
                val entry = proxyEntries.firstOrNull { it.id == selected }
                if (entry == null) {
                    binding.tvSavedProxyInfo.text =
                        "The previously assigned proxy is missing. Choose a replacement or No Proxy before saving."
                } else {
                    binding.etProxyHost.setText(entry.host)
                    binding.etProxyPort.setText(entry.port.toString())
                    binding.etProxyUser.setText("")
                    binding.etProxyPass.setText("")
                    val typeIndex = when (entry.type) { "HTTP" -> 1; "HTTPS" -> 2; "SOCKS5" -> 3; else -> 0 }
                    binding.spProxyType.setSelection(typeIndex)
                    binding.tvSavedProxyInfo.text = when {
                        entry.isSupportedByProxyRelay ->
                            "Saved proxy selected. Tap Test proxy to verify credentials and exit IP; the browser checks the route again when opened."
                        else ->
                            "${entry.type} is stored but not supported by the current NEXORA proxy relay."
                    }
                }
            }
        }
    }

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
        setUaText(buildAutoUa(preset, os))
        updateUaInputState()
    }

    private fun updateUaInputState() {
        binding.etUa.isEnabled = uaEdited
        binding.etUa.alpha = if (uaEdited) 1f else 0.72f
    }

    private fun updateTimezoneInputState() {
        val custom = binding.spTimezone.selectedItemPosition == CUSTOM_TIMEZONE_INDEX
        binding.etTimezoneCustom.visibility = if (custom) View.VISIBLE else View.GONE
        binding.tvTimezoneScope.visibility =
            if (binding.spTimezone.selectedItemPosition > 0) View.VISIBLE else View.GONE
    }

    private fun updateLanguageInputState() {
        val custom = binding.spLanguageMode.selectedItemPosition == 1
        binding.etLanguage.isEnabled = custom
        binding.etLanguage.alpha = if (custom) 1f else 0.72f
        binding.tvLanguageScope.visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun updateStartupCount() {
        val count = ProfileEditorValidator.parseStartupUrls(binding.etStartupUrl.text?.toString().orEmpty()).size
        binding.tvStartupCount.text = "$count startup tab${if (count == 1) "" else "s"} · max ${ProfileEditorValidator.MAX_STARTUP_URLS}"
    }

    private fun selectedTimezoneId(): String {
        val position = binding.spTimezone.selectedItemPosition
        return when {
            position <= 0 -> ""
            position == CUSTOM_TIMEZONE_INDEX -> binding.etTimezoneCustom.text.toString().trim()
            else -> TIMEZONE_VALUES.getOrElse(position) { "" }
        }
    }

    private fun formProfile(id: String): BrowserProfile {
        val preset = selectedPreset()
        val proxySelection = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition) ?: "MANUAL"
        val savedProxy = if (proxySelection !in setOf("MANUAL", "NONE")) {
            proxyEntries.firstOrNull { it.id == proxySelection }
        } else null
        val proxyType = when (proxySelection) {
            "NONE" -> "NONE"
            "MANUAL" -> when (binding.spProxyType.selectedItemPosition) {
                1 -> "HTTP"
                2 -> "HTTPS"
                3 -> "SOCKS5"
                else -> "NONE"
            }
            else -> savedProxy?.type ?: "NONE"
        }
        val proxyHost = when (proxySelection) {
            "NONE" -> ""
            "MANUAL" -> binding.etProxyHost.text.toString().trim()
            else -> savedProxy?.host.orEmpty()
        }
        val proxyPort = when (proxySelection) {
            "NONE" -> 0
            "MANUAL" -> binding.etProxyPort.text.toString().toIntOrNull() ?: 0
            else -> savedProxy?.port ?: 0
        }
        val proxyUser = if (proxySelection == "MANUAL") binding.etProxyUser.text.toString().trim() else ""
        val proxyPass = if (proxySelection == "MANUAL") binding.etProxyPass.text.toString() else ""
        val startupUrls = ProfileEditorValidator.parseStartupUrls(
            binding.etStartupUrl.text.toString()
        )
        val timezoneId = selectedTimezoneId()
        val languageMode = if (binding.spLanguageMode.selectedItemPosition == 1) "CUSTOM" else "DEVICE"
        val language = if (languageMode == "CUSTOM") {
            binding.etLanguage.text.toString().trim()
        } else {
            Locale.getDefault().toLanguageTag()
        }
        val webrtcBlock = binding.spWebrtc.selectedItemPosition == 1
        val previous = editing

        return BrowserProfile(
            id = id,
            name = binding.etName.text.toString().trim(),
            deviceId = preset?.id ?: "",
            osVersion = binding.spOs.selectedItem?.toString() ?: "",
            ramGb = parseGb(binding.spRam.selectedItem?.toString()),
            storageGb = parseGb(binding.spStorage.selectedItem?.toString()),
            userAgent = binding.etUa.text.toString().trim(),
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            proxyEntryId = if (proxySelection !in setOf("MANUAL", "NONE")) proxySelection else "",
            timezoneId = timezoneId,
            language = language,
            webrtcBlock = webrtcBlock,
            canvasNoise = binding.cbCanvas.isChecked,
            webglSpoof = binding.cbWebgl.isChecked,
            audioNoise = binding.cbAudio.isChecked,
            startupUrl = startupUrls.firstOrNull().orEmpty(),
            startupUrls = startupUrls,
            groupName = binding.etGroupName.text.toString().trim(),
            remark = binding.etRemark.text.toString().trim(),
            targetOs = if (preset?.isIos == true) "IOS" else "ANDROID",
            userAgentMode = if (uaEdited) "CUSTOM" else "AUTO",
            timezoneMode = if (timezoneId.isBlank()) "DEVICE" else "CUSTOM",
            languageMode = languageMode,
            webrtcMode = if (webrtcBlock) "DISABLED" else "REAL_IP",
            // These controls are roadmap-only in the current WebView build.
            mediaDevicesNoise = false,
            clientRectsNoise = false,
            speechSynthesisNoise = false,
            dntMode = when (binding.spDnt.selectedItemPosition) {
                1 -> "ENABLE"
                2 -> "DISABLE"
                else -> "DEFAULT"
            },
            hardwareAcceleration = binding.cbHardwareAcceleration.isChecked,
            blockImages = binding.cbBlockImages.isChecked,
            seed = previous?.seed ?: 0,
            createdAt = previous?.createdAt ?: 0L,
            lastUsedAt = previous?.lastUsedAt ?: 0L
        )
    }

    private fun buildAutoUa(preset: DevicePreset, osVersion: String): String {
        val native = WebSettings.getDefaultUserAgent(this)
        return runCatching { UaBuilder.build(preset, osVersion, native) }.getOrDefault(native)
    }

    private fun parseGb(label: String?): Int =
        label?.substringBefore(' ')?.toIntOrNull() ?: 0

    // ------------------------------------------------------------ actions

    private fun testProxy() {
        val selected = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition) ?: "MANUAL"
        val temp = if (selected !in setOf("MANUAL", "NONE")) {
            val entry = proxyEntries.firstOrNull { it.id == selected }
            if (entry == null) {
                binding.tvProxyResult.text = "Saved proxy no longer exists"
                return
            }
            if (entry.type !in setOf("HTTP", "HTTPS", "SOCKS5")) {
                binding.tvProxyResult.text = "Testing ${entry.type} is unavailable in this build"
                return
            }
            val credentials = proxyLibraryStore.credentials(entry)
            if (entry.hasCredentials && credentials.first.isEmpty() && credentials.second.isEmpty()) {
                binding.tvProxyResult.text = "Could not decrypt saved credentials. Check Android Keystore availability."
                return
            }
            BrowserProfile(
                id = "proxy-test",
                name = "Proxy test",
                deviceId = "",
                osVersion = "",
                ramGb = 0,
                storageGb = 0,
                userAgent = "Mozilla/5.0 NEXORA Proxy Test",
                proxyType = entry.type,
                proxyHost = entry.host,
                proxyPort = entry.port,
                proxyUser = credentials.first,
                proxyPass = credentials.second
            )
        } else {
            if (selected == "NONE") {
                binding.tvProxyResult.text = "No proxy selected"
                return
            }
            formProfile(editing?.id ?: "temp")
        }
        if (temp.proxyType == "NONE") {
            binding.tvProxyResult.text = "No proxy set"
            return
        }
        binding.tvProxyResult.text = "Testing…"
        binding.btnTestProxy.isEnabled = false
        ProxyTester.test(temp) { result ->
            runOnUiThread {
                binding.tvProxyResult.text = result
                binding.btnTestProxy.isEnabled = true
            }
        }
    }

    private fun selectedProxyRelayConfig(): ProxyRelayConfig? {
        val selection = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition) ?: "MANUAL"
        val config = when (selection) {
            "NONE" -> return null
            "MANUAL" -> {
                val type = when (binding.spProxyType.selectedItemPosition) {
                    1 -> "HTTP"
                    2 -> "HTTPS"
                    3 -> "SOCKS5"
                    else -> return null
                }
                ProxyRelayConfig(
                    type = type,
                    host = binding.etProxyHost.text.toString().trim(),
                    port = binding.etProxyPort.text.toString().toIntOrNull() ?: 0,
                    username = binding.etProxyUser.text.toString(),
                    password = binding.etProxyPass.text.toString()
                )
            }
            else -> {
                val entry = proxyEntries.firstOrNull { it.id == selection } ?: return null
                if (!entry.isSupportedByProxyRelay) return null
                val credentials = proxyLibraryStore.credentials(entry)
                if (entry.hasCredentials && credentials.first.isEmpty() && credentials.second.isEmpty()) {
                    throw IllegalStateException("Could not decrypt the saved proxy credentials")
                }
                ProxyRelayConfig(
                    type = entry.type,
                    host = entry.host,
                    port = entry.port,
                    username = credentials.first,
                    password = credentials.second
                )
            }
        }
        config.validate()?.let { throw IllegalArgumentException(it) }
        return config
    }

    private fun resolveLocaleFromProxy() {
        val config = try {
            selectedProxyRelayConfig()
        } catch (error: Exception) {
            toast(error.message ?: "Could not read the selected proxy")
            return
        }
        if (config == null) {
            toast("Select a supported HTTP, HTTPS or SOCKS5 proxy first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Resolve locale through this proxy?")
            .setMessage(
                "NEXORA will make one HTTPS request to ipapi.co through the selected proxy. " +
                    "The provider will see the proxy's exit IP and standard request metadata. " +
                    "The request does not include your profile fingerprint or proxy credentials. " +
                    "IP-based timezone and language are estimates; review them before applying. " +
                    "There is no direct-network fallback."
            )
            .setPositiveButton("Continue") { _, _ ->
                binding.btnResolveLocale.isEnabled = false
                binding.tvLocaleResolveResult.text = "Looking up locale through selected proxy…"
                ProxyLocaleResolver.lookup(config) { result ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            binding.btnResolveLocale.isEnabled = true
                            result.onSuccess { suggestion ->
                                showProxyLocaleSuggestion(suggestion)
                            }.onFailure { error ->
                                binding.tvLocaleResolveResult.text =
                                    "Lookup unavailable: ${error.message ?: "provider or proxy error"}"
                                toast("Proxy locale lookup failed")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProxyLocaleSuggestion(suggestion: ProxyLocaleResult) {
        val timezone = suggestion.timezoneId ?: "Not reported"
        val languages = suggestion.languageTags.joinToString(", ").ifBlank { "Not reported" }
        val message = buildString {
            append("Observed exit IP: ")
            append(suggestion.exitIp.ifBlank { "Not reported" })
            append("\nTimezone: ").append(timezone)
            append("\nLanguage suggestions: ").append(languages)
            append("\n\nThis is approximate IP-database information, not device GPS or a verified user preference.")
        }
        AlertDialog.Builder(this)
            .setTitle("Proxy locale suggestions")
            .setMessage(message)
            .setPositiveButton("Apply available suggestions") { _, _ ->
                applyProxyLocaleSuggestion(suggestion, includeLanguage = true)
            }
            .setNeutralButton("Timezone only") { _, _ ->
                applyProxyLocaleSuggestion(suggestion, includeLanguage = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyProxyLocaleSuggestion(
        suggestion: ProxyLocaleResult,
        includeLanguage: Boolean
    ) {
        suggestion.timezoneId?.let { timezone ->
            binding.spTimezone.setSelection(CUSTOM_TIMEZONE_INDEX)
            binding.etTimezoneCustom.setText(timezone)
            updateTimezoneInputState()
        }
        if (includeLanguage) {
            suggestion.languageTags.firstOrNull()?.let { language ->
                binding.spLanguageMode.setSelection(1)
                binding.etLanguage.setText(language)
                updateLanguageInputState()
            }
        }
        val applied = buildList {
            if (suggestion.timezoneId != null) add("timezone")
            if (includeLanguage && suggestion.languageTags.isNotEmpty()) add("language")
        }
        binding.tvLocaleResolveResult.text = if (applied.isEmpty()) {
            "No applicable locale suggestion was returned."
        } else {
            "Applied ${applied.joinToString(" and ")} suggestion(s). Review the fields and save the profile."
        }
    }

    private fun save() {
        if (!uaEdited) regenerateUa()
        val selectedProxy = proxySelectionValues.getOrNull(binding.spSavedProxy.selectedItemPosition) ?: "MANUAL"
        if (selectedProxy !in setOf("MANUAL", "NONE")) {
            val entry = proxyEntries.firstOrNull { it.id == selectedProxy }
            if (entry == null) {
                binding.tvSavedProxyInfo.text = "The selected saved proxy is missing. Choose another proxy or No Proxy. This profile will not be saved as a direct connection."
                toast("Selected saved proxy is missing. Choose another proxy or No Proxy.")
                return
            }
            if (!entry.isSupportedByProxyRelay) {
                toast("${entry.type} is imported but cannot be used for browsing in this WebView.")
                return
            }
            if (entry.hasCredentials) {
                val credentials = proxyLibraryStore.credentials(entry)
                if (credentials.first.isEmpty() && credentials.second.isEmpty()) {
                    toast("Saved proxy credentials could not be decrypted. Re-import the proxy before saving.")
                    return
                }
            }
        }
        val form = formProfile(editing?.id ?: UUID.randomUUID().toString())
        val profiles = readLocalStore { store.loadAll() }.getOrElse { return }
        val errors = ProfileEditorValidator.validate(form, profiles, presets, proxyEntries)
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Check profile settings")
                .setMessage(errors.joinToString("\\n") { "• $it" })
                .setPositiveButton("Review", null)
                .show()
            return
        }

        val existing = editing
        if (existing == null && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this)
                .setTitle("WebView update required")
                .setMessage(
                    "This device's Android System WebView does not support isolated profile storage. " +
                        "Update Android System WebView or Chrome before creating a NEXORA profile."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (existing == null) {
            try {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(form.webViewProfileKey)
            } catch (error: Exception) {
                toast("Could not prepare isolated profile storage: ${error.message ?: "unknown error"}")
                return
            }
        }

        val saved = if (existing != null) {
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
        if (!saved) {
            toast("Could not save the profile. Check device storage and try again.")
            return
        }
        toast("Profile saved")
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        private val PROXY_LABELS = listOf("None", "HTTP", "HTTPS", "SOCKS5")

        private const val CUSTOM_TIMEZONE_INDEX = 14

        private val TIMEZONE_LABELS = listOf(
            "Auto (device)",
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
            "Australia/Sydney",
            "Custom IANA timezone (JS-level partial)…"
        )

        private val TIMEZONE_VALUES = listOf(
            "",
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
            "Australia/Sydney",
            ""
        )
    }
}
