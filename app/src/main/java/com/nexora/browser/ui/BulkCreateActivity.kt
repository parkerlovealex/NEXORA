package com.nexora.browser.ui

import android.content.Intent
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
import android.webkit.WebSettings
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
import com.nexora.browser.util.UiFeedback
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
    private var proxyManagerOpened = false

    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var stepTrack: LinearLayout
    private lateinit var footerStepLabel: TextView

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
                    draft.ramGb = preset.ramOptions.firstOrNull() ?: 0
                    draft.storageGb = preset.storageOptions.firstOrNull() ?: 0
                    draft.globalUserAgent = buildAutoUa(preset, draft.osVersion)
                }
            }
        }
        draft.ensureProfiles()
        if (draft.globalUserAgentMode == "AUTO") {
            presets.firstOrNull { it.id == draft.deviceId }?.let { draft.globalUserAgent = buildAutoUa(it, draft.osVersion) }
        }
        val resetUnavailable = draft.normalizeUnavailableSelections()
        if (resetUnavailable.isNotEmpty()) {
            toast("Unsupported saved options reset to supported defaults: ${resetUnavailable.joinToString()}")
            persistDraft()
        }
        buildShell()
        renderStep()
    }

    override fun onResume() {
        super.onResume()
        if (proxyManagerOpened && ::content.isInitialized) {
            proxyManagerOpened = false
            proxyEntries = proxyStore.loadAll()
            renderStep()
        }
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
            setPadding(dp(20), dp(14), dp(20), dp(16))
            setBackgroundColor(color(R.color.nexora_surface))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "×"
            textSize = 22f
            isAllCaps = false
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(44)
            setPadding(0, 0, 0, dp(2))
            cornerRadius = dp(14)
            strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
            setTextColor(color(R.color.nexora_text))
            contentDescription = "Close profile creation"
            setOnClickListener { confirmExit() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val brandLabel = TextView(this).apply {
            text = "N E X O R A   /   P R O F I L E   S T U D I O"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(color(R.color.nexora_accent))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        title = TextView(this).apply {
            text = "Create profiles"
            textSize = 21f
            setTextColor(color(R.color.nexora_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        }
        subtitle = TextView(this).apply {
            text = "Build a reusable browser workspace"
            textSize = 12f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(3), 0, 0)
        }
        brand.addView(brandLabel)
        brand.addView(title)
        brand.addView(subtitle)
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(top)

        progress = TextView(this).apply {
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(color(R.color.nexora_muted))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        }
        header.addView(progress)

        stepTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(4) { index ->
            val segment = View(this).apply {
                background = roundedFill(if (index == 0) color(R.color.nexora_accent) else color(R.color.nexora_line), 4)
            }
            stepTrack.addView(segment, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        header.addView(stepTrack)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(14))
            setBackgroundColor(color(R.color.nexora_surface))
        }
        val divider = View(this).apply { setBackgroundColor(color(R.color.nexora_line)) }
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val footerMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        footerStepLabel = TextView(this).apply {
            text = "STEP 01 / 04"
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(color(R.color.nexora_muted))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val savedLabel = TextView(this).apply {
            text = "Draft saves automatically"
            textSize = 10f
            setTextColor(color(R.color.nexora_muted))
            gravity = Gravity.END
        }
        footerMeta.addView(footerStepLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footerMeta.addView(savedLabel)
        footer.addView(footerMeta)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Back"
            isAllCaps = false
            textSize = 13f
            minHeight = dp(50)
            cornerRadius = dp(14)
            strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
            setTextColor(color(R.color.nexora_text))
            setOnClickListener { if (step > 0) { step--; renderStep() } else confirmExit() }
        }
        nextButton = MaterialButton(this).apply {
            text = "Continue"
            isAllCaps = false
            textSize = 13f
            minHeight = dp(50)
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(color(R.color.nexora_cta))
            setTextColor(Color.WHITE)
            setOnClickListener { onNext() }
        }
        actions.addView(backButton, LinearLayout.LayoutParams(dp(104), dp(50)).apply { marginEnd = dp(10) })
        actions.addView(nextButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.addView(actions)
        root.addView(footer)
        setContentView(root)
        UiFeedback.install(root)
    }

    private fun renderStep() {
        persistDraft()
        content.removeAllViews()
        val shortLabels = listOf("BATCH SETUP", "PROFILE DETAILS", "PRIVACY SETTINGS", "REVIEW")
        progress.text = "STEP ${step + 1} OF 4   ·   ${shortLabels[step]}"
        footerStepLabel.text = "STEP ${String.format("%02d", step + 1)} / 04"
        title.text = "Create profiles"
        subtitle.text = when (step) {
            0 -> "Set up ${draft.count.coerceIn(1, 50)} browser profiles in one workspace."
            1 -> "Review names and customize each profile."
            2 -> "Configure supported browser and privacy preferences."
            else -> "Check your setup before saving the batch."
        }
        for (index in 0 until stepTrack.childCount) {
            val segment = stepTrack.getChildAt(index)
            segment.background = roundedFill(
                if (index <= step) color(R.color.nexora_accent) else color(R.color.nexora_line),
                4
            )
        }
        backButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (step == 3) "Create ${draft.count.coerceIn(1, 50)} profiles" else "Continue"
        when (step) {
            0 -> renderGeneral()
            1 -> renderPerProfile()
            2 -> renderFingerprint()
            3 -> renderAdvancedAndReview()
        }
        UiFeedback.install(content)
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
                subtitle.text = "Set up ${draft.count} browser profiles in one workspace."
                nextButton.text = "Continue"
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
                draft.ramGb = selected.ramOptions.firstOrNull() ?: 0
                draft.storageGb = selected.storageOptions.firstOrNull() ?: 0
                if (draft.globalUserAgentMode == "AUTO") draft.globalUserAgent = buildAutoUa(selected, draft.osVersion)
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
            draft.ramGb = selected.ramOptions.firstOrNull() ?: 0
            draft.storageGb = selected.storageOptions.firstOrNull() ?: 0
            if (draft.globalUserAgentMode == "AUTO") draft.globalUserAgent = buildAutoUa(selected, draft.osVersion)
            renderStep()
        }
        var globalUaField: EditText? = null
        val selectedPreset = availablePresets.firstOrNull { it.id == draft.deviceId }
        if (selectedPreset != null) {
            addSpinner(browser, "OS version", selectedPreset.osVersions, selectedPreset.osVersions.indexOf(draft.osVersion).coerceAtLeast(0)) { index ->
                draft.osVersion = selectedPreset.osVersions.getOrNull(index).orEmpty()
                if (draft.globalUserAgentMode == "AUTO") {
                    draft.globalUserAgent = buildAutoUa(selectedPreset, draft.osVersion)
                    globalUaField?.setText(draft.globalUserAgent)
                }
            }
            addSpinner(browser, "RAM hint (approximate browser bucket)", selectedPreset.ramOptions.map { "$it GB" }.ifEmpty { listOf("Unknown") }, selectedPreset.ramOptions.indexOf(draft.ramGb).coerceAtLeast(0)) { draft.ramGb = selectedPreset.ramOptions.getOrElse(it) { 0 } }
            addSpinner(browser, "Storage label (metadata only)", selectedPreset.storageOptions.map { "$it GB" }.ifEmpty { listOf("Unknown") }, selectedPreset.storageOptions.indexOf(draft.storageGb).coerceAtLeast(0)) { draft.storageGb = selectedPreset.storageOptions.getOrElse(it) { 0 } }
            if (selectedPreset.isIos && selectedPreset.safariVersion.isBlank() && draft.globalUserAgentMode == "AUTO") {
                val field = addField(browser, "Safari version (manual)", "Enter the Safari version", draft.manualSafariVersion, InputType.TYPE_CLASS_TEXT)
                field.addTextChangedListener(watcher {
                    draft.manualSafariVersion = it
                    draft.globalUserAgent = buildAutoUa(selectedPreset, draft.osVersion)
                    globalUaField?.setText(draft.globalUserAgent)
                })
            }
            addDevicePreview(browser, selectedPreset)
            addNote(browser, "Hardware simulation limits", "RAM is exposed only through coarse navigator.deviceMemory buckets (maximum 8 GB). Storage is profile metadata only; it does not change Android storage capacity or browser quota.")
        }
        addSpinner(browser, "Global User-Agent", listOf("Auto — generated from device preset", "Custom — use imported or pasted value"), if (draft.globalUserAgentMode == "CUSTOM") 1 else 0) { index ->
            draft.globalUserAgentMode = if (index == 1) "CUSTOM" else "AUTO"
            if (draft.globalUserAgentMode == "AUTO") {
                val p = presets.firstOrNull { it.id == draft.deviceId }
                if (p != null) draft.globalUserAgent = buildAutoUa(p, draft.osVersion)
            }
            renderStep()
        }
        if (draft.globalUserAgentMode == "AUTO" && draft.globalUserAgent.isBlank()) {
            presets.firstOrNull { it.id == draft.deviceId }?.let { draft.globalUserAgent = buildAutoUa(it, draft.osVersion) }
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
        val existingGroups = readLocalStore { store.loadAll() }.getOrElse { return }.map { it.groupName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
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

        val proxy = section(
            "Proxy assignment",
            "Choose one shared global proxy for profiles that inherit it, or automatically distribute different saved proxies one-to-one. HTTP, HTTPS and SOCKS5 use the NEXORA relay, including authentication; SOCKS4 remains import-only. Only one profile browser session can be active at a time in this WebView build."
        )
        val proxyOptions = listOf("No Proxy") + proxyEntries.map { proxyLabel(it) }
        val proxyIds = listOf("") + proxyEntries.map { it.id }
        addSpinner(
            proxy,
            "Global proxy (shared by profiles that inherit)",
            proxyOptions,
            proxyIds.indexOf(draft.globalProxyId).coerceAtLeast(0)
        ) { index -> draft.globalProxyId = proxyIds.getOrElse(index) { "" } }
        addNote(proxy, "Saved proxy library", "${proxyEntries.size} saved · ${draft.count.coerceIn(1, 50)} profiles requested")
        addButton(proxy, "Auto-assign saved proxies 1:1") {
            if (proxyEntries.isEmpty()) {
                toast("Import proxies first")
            } else {
                val summary = draft.assignSavedProxiesInOrder(proxyEntries)
                draft.globalProxyId = ""
                val message = buildString {
                    append("Assigned ${summary.assignedProfiles} unique saved proxies")
                    if (summary.directProfiles > 0) append(" · ${summary.directProfiles} profiles set to direct")
                    if (summary.unsupportedAssignments > 0) append(" · ${summary.unsupportedAssignments} unsupported")
                    if (summary.unusedSavedProxies > 0) append(" · ${summary.unusedSavedProxies} unused")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                persistDraft()
                step = 1
                renderStep()
            }
        }
        addButton(proxy, "Import Proxies") { showProxyImport() }
        addButton(proxy, "Manage saved proxies") {
            proxyManagerOpened = true
            startActivity(Intent(this, ProxyLibraryActivity::class.java))
        }
    }

    private fun addDevicePreview(parent: LinearLayout, preset: DevicePreset) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_raised))
            strokeWidth = dp(1)
            strokeColor = color(R.color.nexora_line)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        val marker = TextView(this).apply {
            text = "N"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedFill(color(R.color.nexora_accent), 13)
        }
        row.addView(marker, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(TextView(this).apply {
            text = "SELECTED DEVICE"
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.nexora_accent))
        })
        details.addView(TextView(this).apply {
            text = preset.displayName
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.nexora_text))
            setPadding(0, dp(2), 0, 0)
        })
        details.addView(TextView(this).apply {
            val platform = if (preset.isIos) "iOS emulation" else "Android"
            text = "$platform  ·  ${preset.cssWidth} × ${preset.cssHeight} CSS px  ·  ${preset.widthPx} × ${preset.heightPx} physical px"
            textSize = 10f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })
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
        addButton(actions, "Reassign saved proxies in order (1:1)") {
            if (proxyEntries.isEmpty()) {
                toast("Import proxies first")
            } else {
                val summary = draft.assignSavedProxiesInOrder(proxyEntries)
                draft.globalProxyId = ""
                val message = buildString {
                    append("Assigned ${summary.assignedProfiles} unique proxies in library order")
                    if (summary.directProfiles > 0) append(" · ${summary.directProfiles} profiles set to direct")
                    if (summary.unsupportedAssignments > 0) append(" · ${summary.unsupportedAssignments} unsupported")
                    if (summary.unusedSavedProxies > 0) append(" · ${summary.unusedSavedProxies} unused")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                persistDraft()
                renderStep()
            }
        }
        addButton(actions, "Clear all proxy assignments") {
            draft.perProfiles.forEach { it.proxySelection = "NONE" }
            renderStep()
        }
        val currentMap = draft.perProfiles.take(draft.count.coerceIn(1, 50)).mapIndexed { index, row ->
            val proxyId = when (row.proxySelection) {
                "NONE" -> ""
                "GLOBAL" -> draft.globalProxyId
                else -> row.proxySelection
            }
            val assigned = proxyEntries.firstOrNull { it.id == proxyId }
            "${row.name.ifBlank { "Profile ${index + 1}" }} → ${assigned?.name ?: if (proxyId.isBlank()) "No Proxy" else "MISSING PROXY"}"
        }
        addNote(actions, "Current proxy distribution", currentMap.joinToString("\n"))

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
        val network = section("Network identity", "Only the options below are selectable in this WebView build. JavaScript-level settings are labelled separately from native network controls.")
        addSpinner(network, "WebRTC API", listOf("Restrict WebRTC JavaScript API", "Allow WebRTC (provider default)"), if (draft.webrtcMode == "REAL_IP") 1 else 0) {
            draft.webrtcMode = if (it == 1) "REAL_IP" else "DISABLED"
        }
        addNote(network, "Protection scope", "Restrict mode hides the page-visible RTCPeerConnection constructors. It is not native WebRTC routing and does not guarantee prevention of every network leak.")
        addSpinner(network, "Timezone", listOf("Device timezone", "Custom timezone (JavaScript-level partial)"), if (draft.timezoneMode == "CUSTOM") 1 else 0) {
            draft.timezoneMode = if (it == 1) "CUSTOM" else "DEVICE"
            renderStep()
        }
        if (draft.timezoneMode == "CUSTOM") {
            val timezone = addField(network, "IANA timezone", "America/New_York", draft.customTimezone, InputType.TYPE_CLASS_TEXT)
            timezone.addTextChangedListener(watcher { draft.customTimezone = it })
            addNote(network, "Known limitation", "Intl resolvedOptions and Date.getTimezoneOffset are adjusted. Native local Date getters still use the Android device timezone.")
        }
        addSpinner(network, "Language", listOf("Device language", "Custom language (JavaScript-level)"), if (draft.languageMode == "CUSTOM") 1 else 0) {
            draft.languageMode = if (it == 1) "CUSTOM" else "DEVICE"
            renderStep()
        }
        if (draft.languageMode == "CUSTOM") {
            val language = addField(network, "Language tag", "en-US", draft.customLanguage, InputType.TYPE_CLASS_TEXT)
            language.addTextChangedListener(watcher { draft.customLanguage = it })
            addNote(network, "Known limitation", "Changes page-visible navigator.language and navigator.languages. It does not change Android's app locale or the HTTP Accept-Language request header.")
        }

        val noise = section("Privacy options", "These are JavaScript-level transformations in the current fingerprint layer, not native Chromium patches. Site behavior can still vary by API and frame context.")
        addToggle(noise, "Canvas noise", "Seeded Canvas readback and export transformations", draft.canvasNoise) { draft.canvasNoise = it }
        addToggle(noise, "WebGL identity", "Overrides selected WebGL vendor and renderer parameters", draft.webglNoise) { draft.webglNoise = it }
        addToggle(noise, "Audio noise", "Seeded AudioBuffer readback transformations", draft.audioNoise) { draft.audioNoise = it }

        val pending = section("Roadmap — unavailable in this engine", "Visible for planning only. These items are disabled, are not saved as active protections and cannot be selected in this release.")
        addRoadmapItem(pending, "IP-based timezone", "Requires an explicit, proxy-routed IP lookup and a verified timezone result. No direct-network fallback is used.")
        addRoadmapItem(pending, "IP-based language", "Requires an explicit, proxy-routed region lookup. It is not connected in this release.")
        addRoadmapItem(pending, "Per-profile MAC address spoofing", "Android apps cannot change the device Wi-Fi/Ethernet MAC on a stock phone. Websites do not receive the hardware MAC through browser APIs, so a generated value would be misleading.")
        addRoadmapItem(pending, "WebRTC proxy routing", "Requires native engine or network-stack routing; not provided by this WebView adapter.")
        addRoadmapItem(pending, "Media device metadata", "No device enumeration masking is applied.")
        addRoadmapItem(pending, "Client Rects protection", "DOM geometry APIs are not transformed.")
        addRoadmapItem(pending, "Speech synthesis protection", "Voice inventory and synthesis behavior are not transformed.")
        addRoadmapItem(pending, "Dedicated worker injection", "Document-start scripts do not automatically run in dedicated worker globals.")
        addRoadmapItem(pending, "Native Windows / macOS / Linux profiles", "Requires a separate desktop Chromium build; Android WebView cannot become a desktop OS runtime.")
        addRoadmapItem(pending, "Native iOS browser engine", "Requires a separate iOS app and WebKit integration. Current iOS presets are emulation labels only.")
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
        addNote(review, "User-Agent", if (draft.globalUserAgentMode == "AUTO") "Preset device tokens with the installed WebView Chromium version" else "Custom global User-Agent")
        addNote(review, "Startup tabs", "${BulkProfileValidator.parseStartupUrls(draft.startupUrlsText).size} URLs")
        addNote(review, "Group", draft.groupName.ifBlank { "No group" })
        addNote(review, "Global proxy", selectedProxy?.let { proxyLabel(it) } ?: "No Proxy")
        val selectedProxyIds = draft.perProfiles.take(draft.count.coerceIn(0, 50)).mapNotNull { row ->
            when (row.proxySelection) {
                "NONE" -> null
                "GLOBAL" -> draft.globalProxyId.takeIf { it.isNotBlank() }
                else -> row.proxySelection
            }
        }
        val selectedProxyEntries = selectedProxyIds.mapNotNull { id -> proxyEntries.firstOrNull { it.id == id } }
        val unsupportedProxyCount = selectedProxyEntries.count { !it.isSupportedByProxyRelay }
        addNote(review, "Per-profile proxy assignments", "${selectedProxyEntries.size} assigned · ${unsupportedProxyCount} unsupported · ${draft.perProfiles.take(draft.count.coerceIn(0, 50)).count { it.proxySelection == "NONE" || (it.proxySelection == "GLOBAL" && draft.globalProxyId.isBlank()) }} direct")
        val reviewedProxyMap = draft.perProfiles.take(draft.count.coerceIn(0, 50)).mapIndexed { index, row ->
            val proxyId = when (row.proxySelection) {
                "NONE" -> ""
                "GLOBAL" -> draft.globalProxyId
                else -> row.proxySelection
            }
            val assigned = proxyEntries.firstOrNull { it.id == proxyId }
            "${row.name.ifBlank { "Profile ${index + 1}" }} → ${assigned?.name ?: if (proxyId.isBlank()) "Direct" else "MISSING — FIX BEFORE CREATE"}"
        }
        addNote(review, "Proxy assignment map", reviewedProxyMap.joinToString("\n"))
        val webRtcSummary = if (draft.webrtcMode == "DISABLED") "WebRTC JS API restricted" else "WebRTC allowed"
        val timezoneSummary = if (draft.timezoneMode == "CUSTOM") "Custom timezone (partial JS)" else "Device timezone"
        val languageSummary = if (draft.languageMode == "CUSTOM") "Custom language (JS only)" else "Device language"
        addNote(review, "Fingerprint", "$webRtcSummary · $timezoneSummary · $languageSummary")
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
            background = ContextCompat.getDrawable(this@BulkCreateActivity, R.drawable.profile_field_bg)
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
        val typeSpinner = spinner(listOf("HTTP", "SOCKS5", "SOCKS4", "HTTPS"), 0)
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
        val preview = TextView(this).apply { text = "Credentials are encrypted in the local proxy library. NEXORA routes HTTP, HTTPS and SOCKS5 through a loopback relay; SOCKS4 remains import-only. For host:port:username:password lists, choose HTTP unless your provider specifies another protocol."; textSize = 12f; setTextColor(color(R.color.nexora_muted)); setPadding(0, dp(8), 0, 0) }
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
                        draft.globalProxyId = proxyEntries.firstOrNull { it.isSupportedByProxyRelay }?.id.orEmpty()
                    }
                    val unsupported = proxyEntries.count { !it.isSupportedByProxyRelay }
                    toast("Imported ${result.first} proxies · ${result.second} exact duplicates · ${unsupported} saved entries not usable by this WebView")
                    renderStep()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Validation and creation

    private fun validateDraft(): List<String> {
        val profiles = readLocalStore { store.loadAll() }.getOrElse { return listOf(it.message.orEmpty()) }
        return BulkProfileValidator.validate(draft, profiles, presets, proxyEntries)
    }

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
        val proxyCount = draft.perProfiles.take(draft.count).count { row ->
            when (row.proxySelection) {
                "NONE" -> false
                "GLOBAL" -> draft.globalProxyId.isNotBlank()
                else -> true
            }
        }
        val summary = "Create ${draft.count} profiles?\n\nNames: ${draft.perProfiles.first().name} → ${draft.perProfiles.last().name}\nDevice: ${presets.first { it.id == draft.deviceId }.displayName}\nProxy assignments: $proxyCount / ${draft.count}\nStartup tabs: ${BulkProfileValidator.parseStartupUrls(draft.startupUrlsText).size}\nGroup: ${draft.groupName.ifBlank { "No group" }}"
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
        val existing = readLocalStore { store.loadAll() }.getOrElse { return }
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
                val proxy = if (proxyId.isBlank()) {
                    null
                } else {
                    proxyStore.find(proxyId) ?: throw IllegalStateException(
                        "${row.name}: selected saved proxy no longer exists. No profiles were created."
                    )
                }
                if (proxy != null && !proxy.isSupportedByProxyRelay) {
                    throw IllegalStateException(
                        "${row.name}: ${proxy.type} cannot be used by this WebView proxy relay. No profiles were created."
                    )
                }
                if (proxy?.hasCredentials == true) {
                    val credentials = proxyStore.credentials(proxy)
                    if (credentials.first.isEmpty() && credentials.second.isEmpty()) {
                        throw IllegalStateException(
                            "${row.name}: saved proxy credentials could not be decrypted. No profiles were created."
                        )
                    }
                }
                val ua = if (row.userAgentMode == "CUSTOM") row.userAgent.trim()
                    else if (draft.globalUserAgentMode == "CUSTOM") draft.globalUserAgent.trim()
                    else buildAutoUa(preset, draft.osVersion)
                val profile = BrowserProfile(
                    id = id,
                    name = row.name.trim(),
                    deviceId = preset.id,
                    osVersion = draft.osVersion,
                    ramGb = draft.ramGb,
                    storageGb = draft.storageGb,
                    userAgent = ua,
                    manualSafariVersion = if (row.userAgentMode == "AUTO" && draft.globalUserAgentMode == "AUTO" && preset.isIos && preset.safariVersion.isBlank()) draft.manualSafariVersion.trim() else "",
                    proxyType = proxy?.type ?: "NONE",
                    proxyHost = proxy?.host.orEmpty(),
                    proxyPort = proxy?.port ?: 0,
                    proxyUser = "",
                    proxyPass = "",
                    proxyEntryId = proxy?.id.orEmpty(),
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
        // A13: persist the records FIRST, then provision WebView
        // stores; a provisioning failure rolls the records back (see
        // ProfileStudioActivity for the same ordering).
        if (!store.replaceIfUnchanged(existing, existing + newProfiles)) {
            toast("Could not save profiles. No success was reported; check device storage and retry.")
            return
        }
        try {
            val webProfileStore = androidx.webkit.ProfileStore.getInstance()
            newProfiles.forEach { webProfileStore.getOrCreateProfile(it.webViewProfileKey) }
        } catch (e: Exception) {
            store.replaceIfUnchanged(existing + newProfiles, existing)
            toast("Could not provision isolated WebView storage: ${e.message ?: "unknown error"}. No profiles were saved.")
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


    // ---------------------------------------------------------------------
    // UI helpers

    private fun section(heading: String, description: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.nexora_line)
            useCompatPadding = false
            preventCornerOverlap = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(17))
        }
        val h = TextView(this).apply {
            text = heading
            textSize = 16f
            setTextColor(color(R.color.nexora_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val d = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(4), 0, dp(10))
            if (description.isBlank()) visibility = View.GONE
        }
        body.addView(h)
        body.addView(d)
        card.addView(body)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
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
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = ContextCompat.getDrawable(this@BulkCreateActivity, R.drawable.profile_field_bg)
            if (multiline) {
                this.minLines = minLines
                maxLines = maxOf(minLines, 5)
                gravity = Gravity.TOP or Gravity.START
            } else {
                maxLines = 1
                setSingleLine(true)
            }
        }
        parent.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (multiline) ViewGroup.LayoutParams.WRAP_CONTENT else dp(50)).apply { topMargin = dp(5); bottomMargin = dp(9) })
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

    private fun addRoadmapItem(parent: LinearLayout, heading: String, description: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_raised))
            strokeWidth = dp(1)
            strokeColor = color(R.color.nexora_line)
            isClickable = false
            isFocusable = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            alpha = 0.78f
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            this.text = heading
            textSize = 13f
            setTextColor(color(R.color.nexora_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        text.addView(TextView(this).apply {
            this.text = description
            textSize = 11f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = TextView(this).apply {
            this.text = "PLANNED"
            textSize = 9f
            letterSpacing = 0.06f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.nexora_muted))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(color(R.color.nexora_surface))
                setStroke(dp(1), color(R.color.nexora_line))
            }
            isEnabled = false
        }
        row.addView(status)
        card.addView(row)
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun buildAutoUa(preset: DevicePreset, osVersion: String): String {
        val native = WebSettings.getDefaultUserAgent(this)
        return runCatching { UaBuilder.build(preset, osVersion, native, draft.manualSafariVersion) }.getOrDefault("")
    }

    private fun addChoiceRow(parent: LinearLayout, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (label, value) ->
            val button = MaterialButton(
                this,
                null,
                if (value == selected) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minHeight = dp(44)
                cornerRadius = dp(12)
                setPadding(dp(8), 0, dp(8), 0)
                if (value == selected) {
                    backgroundTintList = ColorStateList.valueOf(color(R.color.nexora_accent))
                    strokeWidth = 0
                    setTextColor(Color.WHITE)
                } else {
                    strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
                    setTextColor(color(R.color.nexora_text))
                }
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
            setTextColor(color(R.color.nexora_text))
            cornerRadius = dp(12)
            strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
            setOnClickListener { onClick() }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.02f
        setTextColor(color(R.color.nexora_muted))
        setPadding(0, dp(8), 0, dp(3))
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
        "${entry.name}${if (entry.hasCredentials) " · Auth" else ""}${if (entry.isSupportedByProxyRelay) "" else " · Not supported"}"

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun roundedFill(fillColor: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
        }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_DRAFT = "bulk_draft_json"
        private const val STATE_STEP = "bulk_step"
        private const val DRAFT_PREFS = "nexora_bulk_draft_v1"
        private const val DRAFT_JSON = "draft_json"
        private const val DRAFT_STEP = "draft_step"
    }
}
