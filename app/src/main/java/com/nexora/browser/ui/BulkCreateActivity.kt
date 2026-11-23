package com.nexora.browser.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.bulk.BulkProfileDraft
import com.nexora.browser.bulk.BulkProfileValidator
import com.nexora.browser.bulk.PerProfileDraft
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyEntry
import com.nexora.browser.data.ProxyLibraryStore
import com.nexora.browser.data.UserAgentLibraryStore
import com.nexora.browser.util.ProxyImporter
import com.nexora.browser.util.UaBuilder
import com.nexora.browser.util.UserAgentImporter
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Mobile-first, four-step batch profile wizard. */
class BulkCreateActivity : AppCompatActivity() {

    private lateinit var store: ProfileStore
    private lateinit var proxyStore: ProxyLibraryStore
    private lateinit var uaStore: UserAgentLibraryStore
    private lateinit var presets: List<DevicePreset>
    private var proxyEntries: List<ProxyEntry> = emptyList()
    private var draft = BulkProfileDraft()
    private var step = 0

    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store = ProfileStore(this)
        proxyStore = ProxyLibraryStore(this)
        uaStore = UserAgentLibraryStore(this)
        presets = DeviceDb.load(this)
        proxyEntries = proxyStore.loadAll()
        if (savedInstanceState != null) {
            draft = try { BulkProfileDraft.fromJson(JSONObject(savedInstanceState.getString(STATE_DRAFT, "{}"))) }
            catch (_: Exception) { BulkProfileDraft() }
            step = savedInstanceState.getInt(STATE_STEP, 0).coerceIn(0, 3)
        } else {
            val saved = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getString(DRAFT_JSON, null)
            if (!saved.isNullOrBlank()) {
                try {
                    draft = BulkProfileDraft.fromJson(JSONObject(saved))
                    step = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getInt(DRAFT_STEP, 0).coerceIn(0, 3)
                    toast("Restored your saved bulk setup")
                } catch (_: Exception) {
                    clearDraft()
                }
            }
            if (draft.deviceId.isBlank()) {
                val preset = presets.firstOrNull { !it.isIos }
                if (preset != null) {
                    draft.deviceId = preset.id
                    draft.osVersion = preset.osVersions.lastOrNull().orEmpty()
                    draft.ramGb = preset.ramOptions.firstOrNull() ?: 8
                    draft.storageGb = preset.storageOptions.firstOrNull() ?: 128
                    draft.globalUserAgent = UaBuilder.build(preset, draft.osVersion)
                }
            }
        }
        draft.ensureProfiles()
        buildShell()
        renderStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DRAFT, draft.toJson().toString())
        outState.putInt(STATE_STEP, step)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in AndroidX; retained for compatibility with older Android versions")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(color(R.color.nexora_header))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 26f
            minWidth = dp(44)
            minimumWidth = dp(44)
            setPadding(0, 0, 0, 0)
            contentDescription = "Close bulk creation"
            setOnClickListener { confirmExit() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        title = TextView(this).apply { text = "Bulk profile creation"; textSize = 20f; setTextColor(color(R.color.nexora_text)); setTypeface(null, android.graphics.Typeface.BOLD) }
        subtitle = TextView(this).apply { text = "Configure a batch of browser profiles"; textSize = 12f; setTextColor(color(R.color.nexora_muted)) }
        heading.addView(title)
        heading.addView(subtitle)
        top.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(top)
        progress = TextView(this).apply { textSize = 12f; setTextColor(color(R.color.nexora_accent)); setPadding(dp(4), dp(14), dp(4), dp(4)) }
        header.addView(progress)
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(color(R.color.nexora_header))
        }
        backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { if (step > 0) { step--; renderStep() } else confirmExit() }
        }
        nextButton = MaterialButton(this).apply {
            text = "Next"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(color(R.color.nexora_cta))
            setTextColor(Color.WHITE)
            setOnClickListener { onNext() }
        }
        footer.addView(backButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        footer.addView(nextButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(footer)
        setContentView(root)
    }

    private fun renderStep() {
        persistDraft()
        content.removeAllViews()
        val labels = listOf("1  General", "2  Per Profile", "3  Fingerprint", "4  Advanced")
        progress.text = "STEP ${step + 1} OF 4  ·  ${labels[step].substringAfter("  ").uppercase()}"
        title.text = labels[step].substringAfter("  ")
        subtitle.text = when (step) {
            0 -> "Set defaults for ${draft.count.coerceIn(1, 50)} profiles"
            1 -> "Customize each profile before creation"
            2 -> "Choose privacy and identity preferences"
            else -> "Review and create your batch"
        }
        backButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (step == 3) "Create ${draft.count} profiles" else "Next"
        when (step) {
            0 -> renderGeneral()
            1 -> renderPerProfile()
            2 -> renderFingerprint()
            3 -> renderAdvancedAndReview()
        }
    }

    // ---------------------------------------------------------------------
    // Step 1 — General

    private fun renderGeneral() {
        val batch = section("Batch settings", "Choose how many profiles to create and how to name them.")
        val namePreview = TextView(this).apply {
            text = buildNamePreview()
            textSize = 12f
            setTextColor(color(R.color.nexora_accent))
            setPadding(0, dp(6), 0, dp(4))
        }
        val count = addField(batch, "Count (1–50)", "5", draft.count.toString(), InputType.TYPE_CLASS_NUMBER)
        count.addTextChangedListener(watcher { value ->
            draft.count = value.toIntOrNull() ?: 0
            if (draft.count in 1..50) {
                draft.ensureProfiles()
                namePreview.text = buildNamePreview()
                subtitle.text = "Set defaults for ${draft.count} profiles"
                nextButton.text = "Next"
            } else {
                namePreview.text = "Enter a count from 1 to 50"
            }
        })
        val oldPrefix = draft.namePrefix
        val prefix = addField(batch, "Name prefix", "Profile", draft.namePrefix, InputType.TYPE_CLASS_TEXT)
        var previousPrefix = oldPrefix
        prefix.addTextChangedListener(watcher { value ->
            if (draft.count in 1..50) {
                draft.perProfiles.forEachIndexed { index, row ->
                    if (row.name == "$previousPrefix ${index + 1}") row.name = "${value.trim()} ${index + 1}"
                }
            }
            previousPrefix = value
            draft.namePrefix = value
            namePreview.text = buildNamePreview()
        })
        batch.addView(namePreview)

        val browser = section("Profile template", "The current app uses the Android System WebView supplied by the device.")
        val provider = try {
            val pkg = android.webkit.WebView.getCurrentWebViewPackage()
            if (pkg == null) "Android System WebView · provider version unavailable" else "${pkg.packageName} · ${pkg.versionName}"
        } catch (_: Exception) { "Android System WebView · provider version unavailable" }
        addNote(browser, "Browser engine", provider)
        addNote(browser, "Desktop OS templates", "Windows, macOS and Linux emulation are not available in this Android WebView build. iOS presets are labelled emulation only.")
        addChoiceRow(browser, listOf("Android" to "ANDROID", "iOS (emulation)" to "IOS"), draft.targetOs) { value ->
            draft.targetOs = value
            val selected = presets.firstOrNull { it.isIos == (value == "IOS") }
            if (selected != null) {
                draft.deviceId = selected.id
                draft.osVersion = selected.osVersions.lastOrNull().orEmpty()
                draft.ramGb = selected.ramOptions.firstOrNull() ?: 8
                draft.storageGb = selected.storageOptions.firstOrNull() ?: 128
                if (draft.globalUserAgentMode == "AUTO") draft.globalUserAgent = UaBuilder.build(selected, draft.osVersion)
            }
            renderStep()
        }
        val availablePresets = presets.filter { it.isIos == (draft.targetOs == "IOS") }
        val deviceLabels = availablePresets.map { it.displayName }
        val deviceIndex = availablePresets.indexOfFirst { it.id == draft.deviceId }.coerceAtLeast(0)
        addSpinner(browser, "Device preset", deviceLabels, deviceIndex) { index ->
            val selected = availablePresets.getOrNull(index) ?: return@addSpinner
            draft.deviceId = selected.id
            draft.osVersion = selected.osVersions.lastOrNull().orEmpty()
            draft.ramGb = selected.ramOptions.firstOrNull() ?: 8
            draft.storageGb = selected.storageOptions.firstOrNull() ?: 128
            if (draft.globalUserAgentMode == "AUTO") draft.globalUserAgent = UaBuilder.build(selected, draft.osVersion)
            renderStep()
        }
        var globalUaField: EditText? = null
        val selectedPreset = availablePresets.firstOrNull { it.id == draft.deviceId }
        if (selectedPreset != null) {
            addSpinner(browser, "OS version", selectedPreset.osVersions, selectedPreset.osVersions.indexOf(draft.osVersion).coerceAtLeast(0)) { index ->
                draft.osVersion = selectedPreset.osVersions.getOrNull(index).orEmpty()
                if (draft.globalUserAgentMode == "AUTO") {
                    draft.globalUserAgent = UaBuilder.build(selectedPreset, draft.osVersion)
                    globalUaField?.setText(draft.globalUserAgent)
                }
            }
            addSpinner(browser, "RAM", selectedPreset.ramOptions.map { "$it GB" }, selectedPreset.ramOptions.indexOf(draft.ramGb).coerceAtLeast(0)) { draft.ramGb = selectedPreset.ramOptions.getOrElse(it) { 8 } }
            addSpinner(browser, "Storage", selectedPreset.storageOptions.map { "$it GB" }, selectedPreset.storageOptions.indexOf(draft.storageGb).coerceAtLeast(0)) { draft.storageGb = selectedPreset.storageOptions.getOrElse(it) { 128 } }
        }
        addSpinner(browser, "Global User-Agent", listOf("Auto — generated from device preset", "Custom — use imported or pasted value"), if (draft.globalUserAgentMode == "CUSTOM") 1 else 0) { index ->
            draft.globalUserAgentMode = if (index == 1) "CUSTOM" else "AUTO"
            if (draft.globalUserAgentMode == "AUTO") {
                val p = presets.firstOrNull { it.id == draft.deviceId }
                if (p != null) draft.globalUserAgent = UaBuilder.build(p, draft.osVersion)
            }
            renderStep()
        }
        if (draft.globalUserAgentMode == "AUTO" && draft.globalUserAgent.isBlank()) {
            presets.firstOrNull { it.id == draft.deviceId }?.let { draft.globalUserAgent = UaBuilder.build(it, draft.osVersion) }
        }
        val uaField = addField(browser, "User-Agent string", "Mozilla/5.0 …", draft.globalUserAgent, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multiline = true)
        globalUaField = uaField
        uaField.isEnabled = draft.globalUserAgentMode == "CUSTOM"
        uaField.alpha = if (uaField.isEnabled) 1f else 0.62f
        uaField.addTextChangedListener(watcher { draft.globalUserAgent = it })
        addButton(browser, "Import User-Agents") { showUserAgentImport() }

        val startup = section("Startup tabs", "One HTTP or HTTPS URL per line. Invalid lines are shown before creation.")
        val startupField = addField(startup, "URLs", "https://example.com", draft.startupUrlsText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multiline = true, minLines = 3)
        startupField.addTextChangedListener(watcher { draft.startupUrlsText = it })
        addNote(startup, "Startup tab count", "${BulkProfileValidator.parseStartupUrls(draft.startupUrlsText).size} URLs configured")

        val organization = section("Organization", "Choose an existing group or enter a new group name.")
        val existingGroups = store.loadAll().map { it.groupName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val groupOptions = listOf("No Group") + existingGroups + "Create new group…"
        val groupSelected = when {
            draft.groupName.isBlank() -> 0
            existingGroups.contains(draft.groupName) -> existingGroups.indexOf(draft.groupName) + 1
            else -> groupOptions.lastIndex
        }
        addSpinner(organization, "Group", groupOptions, groupSelected) { index ->
            if (index == 0) draft.groupName = ""
            else if (index in 1..existingGroups.size) draft.groupName = existingGroups[index - 1]
            renderStep()
        }
        val group = addField(organization, "Group name", "e.g. Research", draft.groupName, InputType.TYPE_CLASS_TEXT)
        group.addTextChangedListener(watcher { draft.groupName = it })
        val remark = addField(organization, "Remark", "Optional batch note", draft.remark, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multiline = true, minLines = 2)
        remark.addTextChangedListener(watcher { draft.remark = it })

        val proxy = section("Global proxy", "Choose No Proxy or a saved proxy. Unsupported proxy types and authentication are blocked for browsing in this WebView build.")
        val proxyOptions = listOf("No Proxy") + proxyEntries.map { proxyLabel(it) }
        val proxyIds = listOf("") + proxyEntries.map { it.id }
        addSpinner(proxy, "Apply to all profiles", proxyOptions, proxyIds.indexOf(draft.globalProxyId).coerceAtLeast(0)) { index -> draft.globalProxyId = proxyIds.getOrElse(index) { "" } }
        addButton(proxy, "Import Proxies") { showProxyImport() }
        addButton(proxy, "Manage saved proxies") { showProxyManager() }
    }

    // ---------------------------------------------------------------------
    // Step 2 — Per profile

    private fun renderPerProfile() {
        draft.ensureProfiles()
        val actions = section("Batch actions", "Apply a change to every profile, or edit each card below.")
        addButton(actions, "Randomize all User-Agents") {
            val imported = uaStore.loadAll()
            if (imported.isEmpty()) {
                toast("Import User-Agents first, or use Auto mode.")
            } else {
                val shuffled = imported.shuffled()
                draft.perProfiles.forEachIndexed { index, row ->
                    row.userAgentMode = "CUSTOM"
                    row.userAgent = shuffled[index % shuffled.size]
                }
                renderStep()
            }
        }
        addButton(actions, "Reset all User-Agents to Global") {
            draft.perProfiles.forEach { it.userAgentMode = "AUTO"; it.userAgent = "" }
            renderStep()
        }
        addButton(actions, "Apply Global Proxy to All") {
            draft.perProfiles.forEach { it.proxySelection = "GLOBAL" }
            renderStep()
        }

        val proxyLabels = listOf("Inherit global proxy", "No Proxy") + proxyEntries.map { proxyLabel(it) }
        val proxyValues = listOf("GLOBAL", "NONE") + proxyEntries.map { it.id }
        draft.perProfiles.forEachIndexed { index, row ->
            val card = section("Profile ${index + 1}", "Individual settings override the global defaults.")
            val name = addField(card, "Profile name", "Profile ${index + 1}", row.name, InputType.TYPE_CLASS_TEXT)
            name.addTextChangedListener(watcher { row.name = it })
            addSpinner(card, "User-Agent mode", listOf("Auto — inherit global", "Custom — override this profile"), if (row.userAgentMode == "CUSTOM") 1 else 0) { mode ->
                row.userAgentMode = if (mode == 1) "CUSTOM" else "AUTO"
                renderStep()
            }
            if (row.userAgentMode == "CUSTOM") {
                val ua = addField(card, "Custom User-Agent", "Paste or select a User-Agent", row.userAgent, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multiline = true, minLines = 2)
                ua.addTextChangedListener(watcher { row.userAgent = it })
                addButton(card, "Use imported User-Agent") {
                    val values = uaStore.loadAll()
                    if (values.isEmpty()) toast("No imported User-Agents")
                    else AlertDialog.Builder(this).setTitle("Choose User-Agent")
                        .setItems(values.mapIndexed { i, value -> "${i + 1}. ${value.take(74)}" }.toTypedArray()) { _, which ->
                            row.userAgent = values[which]
                            renderStep()
                        }.show()
                }
            }
            addSpinner(card, "Proxy assignment", proxyLabels, proxyValues.indexOf(row.proxySelection).coerceAtLeast(0)) { selected -> row.proxySelection = proxyValues.getOrElse(selected) { "GLOBAL" } }
        }
    }

    // ---------------------------------------------------------------------
    // Step 3 — Fingerprint

    private fun renderFingerprint() {
        val network = section("Network identity", "These settings are saved per profile. Availability depends on the installed WebView provider.")
        addSpinner(network, "WebRTC", listOf("Disabled — block WebRTC", "Real IP — allow WebRTC", "Proxy IP — unavailable"), when (draft.webrtcMode) { "REAL_IP" -> 1; "PROXY_IP" -> 2; else -> 0 }) {
            draft.webrtcMode = listOf("DISABLED", "REAL_IP", "PROXY_IP").getOrElse(it) { "DISABLED" }
        }
        addSpinner(network, "Timezone", listOf("Device timezone", "Based on IP", "Custom timezone"), when (draft.timezoneMode) { "IP" -> 1; "CUSTOM" -> 2; else -> 0 }) {
            draft.timezoneMode = listOf("DEVICE", "IP", "CUSTOM").getOrElse(it) { "DEVICE" }
            renderStep()
        }
        if (draft.timezoneMode == "CUSTOM") {
            val timezone = addField(network, "IANA timezone", "America/New_York", draft.customTimezone, InputType.TYPE_CLASS_TEXT)
            timezone.addTextChangedListener(watcher { draft.customTimezone = it })
        }
        addSpinner(network, "Language", listOf("Device language", "Based on IP", "Custom language"), when (draft.languageMode) { "IP" -> 1; "CUSTOM" -> 2; else -> 0 }) {
            draft.languageMode = listOf("DEVICE", "IP", "CUSTOM").getOrElse(it) { "DEVICE" }
            renderStep()
        }
        if (draft.languageMode == "CUSTOM") {
            val language = addField(network, "Language tag", "en-US", draft.customLanguage, InputType.TYPE_CLASS_TEXT)
            language.addTextChangedListener(watcher { draft.customLanguage = it })
        }

        val noise = section("Privacy options", "These three controls are applied by the current JavaScript fingerprint layer. They are not native Chromium patches.")
        addToggle(noise, "Canvas noise", "Seeded canvas readback noise", draft.canvasNoise) { draft.canvasNoise = it }
        addToggle(noise, "WebGL identity", "Use the selected device preset's WebGL vendor and renderer", draft.webglNoise) { draft.webglNoise = it }
        addToggle(noise, "Audio noise", "Seeded AudioBuffer readback noise", draft.audioNoise) { draft.audioNoise = it }

        val pending = section("Additional controls — engine support pending", "The current Android System WebView integration does not implement these controls. They are displayed for roadmap visibility and are not saved as active protections.")
        addDisabledToggle(pending, "Media devices", "Not applied in this release")
        addDisabledToggle(pending, "Client rects", "Not applied in this release")
        addDisabledToggle(pending, "Speech synthesis", "Not applied in this release")
    }

    // ---------------------------------------------------------------------
    // Step 4 — Advanced and review

    private fun renderAdvancedAndReview() {
        val advanced = section("Advanced settings", "DNT changes the browser preference signal; it does not force websites to honor it.")
        addSpinner(advanced, "Do Not Track", listOf("Default", "Enable", "Disable"), when (draft.dntMode) { "ENABLE" -> 1; "DISABLE" -> 2; else -> 0 }) {
            draft.dntMode = listOf("DEFAULT", "ENABLE", "DISABLE").getOrElse(it) { "DEFAULT" }
        }
        addToggle(advanced, "Hardware acceleration", "Use hardware rendering for this profile's WebViews", draft.hardwareAcceleration) { draft.hardwareAcceleration = it }
        addToggle(advanced, "Block images", "Reduce data usage; some sites may not display correctly", draft.blockImages) { draft.blockImages = it }

        val review = section("Review batch", "Check the summary before profiles are written to local storage.")
        val selectedProxy = proxyStore.find(draft.globalProxyId)
        addNote(review, "Profiles", "${draft.count} profiles · ${draft.perProfiles.firstOrNull()?.name ?: draft.namePrefix + " 1"} → ${draft.perProfiles.lastOrNull()?.name ?: draft.namePrefix + " ${draft.count}"}")
        addNote(review, "Template", "${draft.targetOs} · ${presets.firstOrNull { it.id == draft.deviceId }?.displayName ?: "No device"} · OS ${draft.osVersion}")
        addNote(review, "User-Agent", if (draft.globalUserAgentMode == "AUTO") "Auto-generated from selected preset" else "Custom global User-Agent")
        addNote(review, "Startup tabs", "${BulkProfileValidator.parseStartupUrls(draft.startupUrlsText).size} URLs")
        addNote(review, "Group", draft.groupName.ifBlank { "No group" })
        addNote(review, "Global proxy", selectedProxy?.let { proxyLabel(it) } ?: "No Proxy")
        addNote(review, "Fingerprint", "WebRTC ${draft.webrtcMode} · timezone ${draft.timezoneMode} · language ${draft.languageMode}")
        addNote(review, "Advanced", "DNT ${draft.dntMode} · hardware acceleration ${if (draft.hardwareAcceleration) "On" else "Off"} · block images ${if (draft.blockImages) "On" else "Off"}")
        addNote(review, "Storage", "Profile metadata will be saved locally. Isolated website storage is created when each profile is launched and requires WebView MULTI_PROFILE support.")
        addButton(review, "Validate batch") {
            val errors = validateDraft()
            if (errors.isEmpty()) toast("Validation passed — ready to create") else showValidationErrors(errors)
        }
    }

    // ---------------------------------------------------------------------
    // Import dialogs

    private fun showUserAgentImport() {
        val input = EditText(this).apply {
            hint = "Paste one User-Agent per line"
            minLines = 6
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(color(R.color.nexora_text))
            setHintTextColor(color(R.color.nexora_muted))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@BulkCreateActivity, R.drawable.omnibox_bg)
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        val preview = TextView(this).apply { text = "One User-Agent per line. Duplicates and malformed lines are excluded."; textSize = 12f; setTextColor(color(R.color.nexora_muted)); setPadding(0, dp(8), 0, 0) }
        container.addView(preview)
        input.addTextChangedListener(watcher { text ->
            val rows = UserAgentImporter.parse(text)
            val valid = rows.count { it.valid }
            preview.text = "$valid valid · ${rows.size - valid} warning/duplicate rows\n" + rows.take(3).joinToString("\n") { "Line ${it.lineNumber}: ${it.osFamily} / ${it.browserFamily} ${if (it.valid) "✓" else "— ${it.warning}"}" }
        })
        AlertDialog.Builder(this)
            .setTitle("Import User-Agents")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val rows = UserAgentImporter.parse(input.text.toString())
                val valid = rows.filter { it.valid }.map { it.value }
                if (valid.isEmpty()) toast("No valid User-Agent strings found")
                else {
                    val result = uaStore.import(valid)
                    draft.globalUserAgentMode = "CUSTOM"
                    draft.globalUserAgent = valid.first()
                    toast("Imported ${result.first} User-Agents · ${result.second} duplicates")
                    renderStep()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProxyImport() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val typeSpinner = spinner(listOf("SOCKS5", "SOCKS4", "HTTP", "HTTPS"), 0)
        container.addView(label("Default protocol for lines without a scheme"))
        container.addView(typeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val input = EditText(this).apply {
            hint = "host:port\nhost:port:user:pass\nsocks5://user:pass@host:port"
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(color(R.color.nexora_text))
            setHintTextColor(color(R.color.nexora_muted))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@BulkCreateActivity, R.drawable.omnibox_bg)
        }
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(10) })
        val preview = TextView(this).apply { text = "Credentials are encrypted in the local proxy library. Current WebView browsing supports only unauthenticated HTTP and SOCKS5 proxies."; textSize = 12f; setTextColor(color(R.color.nexora_muted)); setPadding(0, dp(8), 0, 0) }
        container.addView(preview)
        fun updatePreview() {
            val rows = ProxyImporter.parse(input.text.toString(), typeSpinner.selectedItem.toString())
            val valid = rows.count { it.valid }
            preview.text = "$valid valid · ${rows.size - valid} invalid\n" + rows.take(4).joinToString("\n") {
                "Line ${it.lineNumber}: ${it.type} ${it.host}:${it.port} ${if (it.hasCredentials) "· credentials" else ""} ${if (it.valid) "✓" else "— ${it.error}"}"
            }
        }
        input.addTextChangedListener(watcher { updatePreview() })
        typeSpinner.onItemSelectedListener = simpleSelection(typeSpinner.selectedItemPosition) { updatePreview() }
        AlertDialog.Builder(this)
            .setTitle("Import Proxies")
            .setView(container)
            .setPositiveButton("Import valid") { _, _ ->
                val rows = ProxyImporter.parse(input.text.toString(), typeSpinner.selectedItem.toString())
                val valid = rows.filter { it.valid }
                if (valid.isEmpty()) toast("No valid proxies found")
                else {
                    val result = proxyStore.import(valid)
                    proxyEntries = proxyStore.loadAll()
                    if (draft.globalProxyId.isBlank()) {
                        draft.globalProxyId = proxyEntries.firstOrNull { it.isSupportedByCurrentWebView }?.id.orEmpty()
                    }
                    toast("Imported ${result.first} proxies · ${result.second} duplicates")
                    renderStep()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Validation and creation

    private fun validateDraft(): List<String> = BulkProfileValidator.validate(
        draft,
        store.loadAll(),
        presets,
        proxyEntries
    )

    private fun onNext() {
        if (step == 0) {
            if (draft.count !in 1..50) { toast("Count must be from 1 to 50"); return }
            draft.namePrefix = draft.namePrefix.trim()
            draft.ensureProfiles()
            // Refresh untouched auto-generated names when the prefix changes.
            draft.perProfiles.forEachIndexed { index, row ->
                if (row.name.isBlank() || row.name == "Profile ${index + 1}") row.name = "${draft.namePrefix} ${index + 1}"
            }
        }
        if (step < 3) {
            step++
            renderStep()
            return
        }
        val errors = validateDraft()
        if (errors.isNotEmpty()) {
            showValidationErrors(errors)
            return
        }
        val summary = "Create ${draft.count} profiles?\n\nNames: ${draft.perProfiles.first().name} → ${draft.perProfiles.last().name}\nDevice: ${presets.first { it.id == draft.deviceId }.displayName}\nStartup tabs: ${BulkProfileValidator.parseStartupUrls(draft.startupUrlsText).size}\nGroup: ${draft.groupName.ifBlank { "No group" }}"
        AlertDialog.Builder(this)
            .setTitle("Confirm batch creation")
            .setMessage(summary)
            .setPositiveButton("Create profiles") { _, _ -> createBatch() }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun createBatch() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this)
                .setTitle("WebView update required")
                .setMessage("This device's Android System WebView does not support isolated profile storage (MULTI_PROFILE). Update Android System WebView or Chrome before creating profiles.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val existing = store.loadAll()
        val now = System.currentTimeMillis()
        val startupUrls = BulkProfileValidator.parseStartupUrls(draft.startupUrlsText)
        val preset = presets.firstOrNull { it.id == draft.deviceId } ?: run { toast("Device preset is missing"); return }
        val newProfiles = mutableListOf<BrowserProfile>()
        try {
            draft.perProfiles.take(draft.count).forEachIndexed { index, row ->
                val id = UUID.randomUUID().toString()
                val proxyId = when (row.proxySelection) {
                    "NONE" -> ""
                    "GLOBAL" -> draft.globalProxyId
                    else -> row.proxySelection
                }
                val proxy = proxyStore.find(proxyId)
                val ua = if (row.userAgentMode == "CUSTOM") row.userAgent.trim()
                    else if (draft.globalUserAgentMode == "CUSTOM") draft.globalUserAgent.trim()
                    else UaBuilder.build(preset, draft.osVersion)
                val profile = BrowserProfile(
                    id = id,
                    name = row.name.trim(),
                    deviceId = preset.id,
                    osVersion = draft.osVersion,
                    ramGb = draft.ramGb,
                    storageGb = draft.storageGb,
                    userAgent = ua,
                    proxyType = proxy?.type ?: "NONE",
                    proxyHost = proxy?.host.orEmpty(),
                    proxyPort = proxy?.port ?: 0,
                    proxyUser = "",
                    proxyPass = "",
                    timezoneId = if (draft.timezoneMode == "CUSTOM") draft.customTimezone.trim() else "",
                    language = if (draft.languageMode == "CUSTOM") draft.customLanguage.trim() else java.util.Locale.getDefault().toLanguageTag(),
                    webrtcBlock = draft.webrtcMode == "DISABLED",
                    canvasNoise = draft.canvasNoise,
                    webglSpoof = draft.webglNoise,
                    audioNoise = draft.audioNoise,
                    startupUrl = startupUrls.firstOrNull().orEmpty(),
                    startupUrls = startupUrls,
                    groupName = draft.groupName.trim(),
                    remark = draft.remark.trim(),
                    targetOs = draft.targetOs,
                    userAgentMode = if (row.userAgentMode == "CUSTOM" || draft.globalUserAgentMode == "CUSTOM") "CUSTOM" else "AUTO",
                    timezoneMode = draft.timezoneMode,
                    languageMode = draft.languageMode,
                    webrtcMode = draft.webrtcMode,
                    mediaDevicesNoise = false,
                    clientRectsNoise = false,
                    speechSynthesisNoise = false,
                    dntMode = draft.dntMode,
                    hardwareAcceleration = draft.hardwareAcceleration,
                    blockImages = draft.blockImages,
                    seed = Random.nextInt(1, Int.MAX_VALUE),
                    createdAt = now + index,
                    lastUsedAt = 0L
                )
                newProfiles.add(profile)
            }
        } catch (e: Exception) {
            toast("Could not prepare profiles: ${e.message ?: "unknown error"}")
            return
        }
        try {
            val webProfileStore = androidx.webkit.ProfileStore.getInstance()
            newProfiles.forEach { webProfileStore.getOrCreateProfile(it.webViewProfileKey) }
        } catch (e: Exception) {
            toast("Could not provision isolated WebView storage: ${e.message ?: "unknown error"}. No profiles were saved.")
            return
        }
        if (!store.saveAllChecked(existing + newProfiles)) {
            toast("Could not save profiles. No success was reported; check device storage and retry.")
            return
        }
        Toast.makeText(this, "Created ${newProfiles.size} profiles", Toast.LENGTH_LONG).show()
        clearDraft()
        setResult(RESULT_OK)
        finish()
    }

    private fun showValidationErrors(errors: List<String>) {
        val message = errors.take(12).joinToString("\n\n• ", prefix = "• ") + if (errors.size > 12) "\n\n…and ${errors.size - 12} more." else ""
        AlertDialog.Builder(this).setTitle("Fix before creating").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Leave bulk creation?")
            .setMessage("Save this unfinished setup locally so you can continue later, or discard it.")
            .setPositiveButton("Save draft") { _, _ -> persistDraft(); finish() }
            .setNeutralButton("Discard") { _, _ -> clearDraft(); finish() }
            .setNegativeButton("Continue editing", null)
            .show()
    }

    private fun persistDraft() {
        if (!::store.isInitialized) return
        getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit()
            .putString(DRAFT_JSON, draft.toJson().toString())
            .putInt(DRAFT_STEP, step)
            .apply()
    }

    private fun clearDraft() {
        getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit()
            .remove(DRAFT_JSON).remove(DRAFT_STEP).apply()
    }

    private fun showProxyManager() {
        proxyEntries = proxyStore.loadAll()
        if (proxyEntries.isEmpty()) { toast("No saved proxies yet"); return }
        val labels = proxyEntries.map { proxyLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved proxies · ${proxyEntries.size}")
            .setItems(labels) { _, which ->
                val entry = proxyEntries.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle("${entry.type} ${entry.host}:${entry.port}")
                    .setMessage(if (entry.hasCredentials) "Credentials are stored encrypted. This proxy cannot be assigned to browser profiles until authenticated proxy support is available." else "Saved locally. Current WebView support: ${if (entry.isSupportedByCurrentWebView) "available" else "not available"}.")
                    .setPositiveButton("Delete") { _, _ ->
                        proxyStore.delete(entry.id)
                        if (draft.globalProxyId == entry.id) draft.globalProxyId = ""
                        draft.perProfiles.forEach { if (it.proxySelection == entry.id) it.proxySelection = "GLOBAL" }
                        proxyEntries = proxyStore.loadAll()
                        renderStep()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // UI helpers

    private fun section(heading: String, description: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.nexora_line)
            useCompatPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val h = TextView(this).apply { text = heading; textSize = 16f; setTextColor(color(R.color.nexora_text)); setTypeface(null, android.graphics.Typeface.BOLD) }
        val d = TextView(this).apply { text = description; textSize = 12f; setTextColor(color(R.color.nexora_muted)); setPadding(0, dp(4), 0, dp(8)) }
        body.addView(h)
        body.addView(d)
        card.addView(body)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return body
    }

    private fun addField(
        parent: LinearLayout,
        fieldLabel: String,
        hint: String,
        value: String,
        inputType: Int,
        multiline: Boolean = false,
        minLines: Int = 1
    ): EditText {
        parent.addView(label(fieldLabel))
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            setTextColor(color(R.color.nexora_text))
            setHintTextColor(color(R.color.nexora_muted))
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@BulkCreateActivity, R.drawable.omnibox_bg)
            if (multiline) {
                this.minLines = minLines
                maxLines = maxOf(minLines, 5)
                gravity = Gravity.TOP or Gravity.START
            } else {
                maxLines = 1
                setSingleLine(true)
            }
        }
        parent.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (multiline) ViewGroup.LayoutParams.WRAP_CONTENT else dp(48)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        return edit
    }

    private fun addSpinner(parent: LinearLayout, fieldLabel: String, values: List<String>, selected: Int, onSelected: (Int) -> Unit): Spinner {
        parent.addView(label(fieldLabel))
        val view = spinner(values, selected)
        parent.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        view.onItemSelectedListener = simpleSelection(view.selectedItemPosition) { position -> onSelected(position); persistDraft() }
        return view
    }

    private fun spinner(values: List<String>, selected: Int): Spinner {
        val view = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values.ifEmpty { listOf("Unavailable") })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        view.adapter = adapter
        view.setSelection(selected.coerceIn(0, (adapter.count - 1).coerceAtLeast(0)))
        return view
    }

    private fun addToggle(parent: LinearLayout, heading: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = heading; textSize = 14f; setTextColor(color(R.color.nexora_text)); setTypeface(null, android.graphics.Typeface.BOLD) })
        text.addView(TextView(this).apply { this.text = description; textSize = 11f; setTextColor(color(R.color.nexora_muted)) })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = CheckBox(this).apply { isChecked = checked; buttonTintList = ColorStateList.valueOf(color(R.color.nexora_accent)); setOnCheckedChangeListener { _, value -> onChanged(value); persistDraft() } }
        row.addView(check)
        parent.addView(row)
    }

    private fun addDisabledToggle(parent: LinearLayout, heading: String, description: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = heading; textSize = 14f; setTextColor(color(R.color.nexora_muted)) })
        text.addView(TextView(this).apply { this.text = description; textSize = 11f; setTextColor(color(R.color.nexora_muted)) })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(CheckBox(this).apply { isEnabled = false })
        parent.addView(row)
    }

    private fun addChoiceRow(parent: LinearLayout, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (label, value) ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minHeight = dp(42)
                setPadding(dp(8), 0, dp(8), 0)
                strokeColor = ColorStateList.valueOf(if (value == selected) color(R.color.nexora_accent) else color(R.color.nexora_line))
                setTextColor(if (value == selected) color(R.color.nexora_accent) else color(R.color.nexora_text))
                setOnClickListener { onSelected(value); persistDraft() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) })
    }

    private fun addNote(parent: LinearLayout, heading: String, body: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(8)) }
        box.addView(TextView(this).apply { text = heading; textSize = 12f; setTextColor(color(R.color.nexora_muted)) })
        box.addView(TextView(this).apply { text = body; textSize = 13f; setTextColor(color(R.color.nexora_text)); setPadding(0, dp(2), 0, 0) })
        parent.addView(box)
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color(R.color.nexora_accent))
            strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
            setOnClickListener { onClick() }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(color(R.color.nexora_muted))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun watcher(onChanged: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
            persistDraft()
        }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun simpleSelection(initialPosition: Int, onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        private var lastPosition = initialPosition
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (position == lastPosition) return
            lastPosition = position
            onSelected(position)
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private fun buildNamePreview(): String {
        if (draft.count !in 1..50 || draft.namePrefix.isBlank()) return "Enter a valid count and prefix"
        return "Will create: ${draft.namePrefix.trim()} 1 → ${draft.namePrefix.trim()} ${draft.count}"
    }

    private fun proxyLabel(entry: ProxyEntry): String =
        "${entry.name}${if (entry.hasCredentials) " · Auth" else ""}${if (entry.isSupportedByCurrentWebView) "" else " · Not supported"}"

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_DRAFT = "bulk_draft_json"
        private const val STATE_STEP = "bulk_step"
        private const val DRAFT_PREFS = "nexora_bulk_draft_v1"
        private const val DRAFT_JSON = "draft_json"
        private const val DRAFT_STEP = "draft_step"
    }
}
