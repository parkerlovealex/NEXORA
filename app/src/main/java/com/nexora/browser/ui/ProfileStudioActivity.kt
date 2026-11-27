package com.nexora.browser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
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
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.GeneratedUserAgent
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyEntry
import com.nexora.browser.data.ProxyLibraryStore
import com.nexora.browser.data.UserAgentBatch
import com.nexora.browser.data.UserAgentBatchStore
import com.nexora.browser.util.UserAgentGenerationEngine
import com.nexora.browser.util.UserAgentGenerationRequest
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/** Three-step profile workflow. iOS entries are explicitly compatibility templates on Android. */
class ProfileStudioActivity : AppCompatActivity() {
    private lateinit var presets: List<DevicePreset>
    private lateinit var inAppTemplates: List<com.nexora.browser.data.UserAgentTemplate>
    private lateinit var profileStore: ProfileStore
    private lateinit var proxyStore: ProxyLibraryStore
    private lateinit var batchStore: UserAgentBatchStore
    private var savedBatches: List<UserAgentBatch> = emptyList()
    private var proxies: List<ProxyEntry> = emptyList()

    private var step = 0
    private var androidCount = 5
    private var iosCount = 0
    private var namePrefix = "Profile"
    private var androidDeviceMode = "RANDOM"
    private var iosDeviceMode = "RANDOM"
    private val selectedAndroidIds = linkedSetOf<String>()
    private val selectedIosIds = linkedSetOf<String>()
    private var distributionMode = "BALANCED"
    private var customDeviceCounts = mutableMapOf<String, Int>()
    private var uaSource = "AUTO"
    private var selectedBatchId = ""
    private var androidCustomUaText = ""
    private var iosCustomUaText = ""
    private var groupName = ""
    private var remark = ""
    private var assignProxies = false
    private var proxyManagerOpened = false

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: TextView
    private lateinit var stepTrack: LinearLayout
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var footerLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        presets = DeviceDb.load(this)
        inAppTemplates = DeviceDb.loadVerifiedUserAgentTemplates(this)
        profileStore = ProfileStore(this)
        proxyStore = ProxyLibraryStore(this)
        batchStore = UserAgentBatchStore(this)
        savedBatches = readLocalStore { batchStore.loadAll() }.getOrElse { finish(); return }
        proxies = proxyStore.loadAll()
        if (savedInstanceState != null) restoreState(savedInstanceState)
        intent.getStringExtra(EXTRA_UA_BATCH_ID)?.takeIf { it.isNotBlank() }?.let { id ->
            if (savedBatches.any { it.id == id }) { uaSource = "BATCH"; selectedBatchId = id }
        }
        buildShell()
        renderStep()
    }

    override fun onResume() {
        super.onResume()
        if (::batchStore.isInitialized) {
            val latest = readLocalStore { batchStore.loadAll() }.getOrElse { return }
            val batchChanged = latest.map { it.id } != savedBatches.map { it.id }
            savedBatches = latest
            if (proxyManagerOpened && ::proxyStore.isInitialized) {
                proxyManagerOpened = false
                val latestProxies = proxyStore.loadAll()
                val proxyChanged = latestProxies.map { it.id } != proxies.map { it.id }
                proxies = latestProxies
                if (proxyChanged && ::content.isInitialized && step == 2) renderStep()
            }
            if (batchChanged && ::content.isInitialized && step == 1 && uaSource == "BATCH") renderStep()
        }
    }

    @Deprecated("Deprecated in AndroidX; retained for consistent wizard navigation")
    override fun onBackPressed() {
        if (step > 0) { step--; renderStep() } else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("step", step)
        outState.putInt("androidCount", androidCount)
        outState.putInt("iosCount", iosCount)
        outState.putString("namePrefix", namePrefix)
        outState.putString("androidDeviceMode", androidDeviceMode)
        outState.putString("iosDeviceMode", iosDeviceMode)
        outState.putStringArrayList("selectedAndroidIds", ArrayList(selectedAndroidIds))
        outState.putStringArrayList("selectedIosIds", ArrayList(selectedIosIds))
        outState.putString("distributionMode", distributionMode)
        outState.putString("customDeviceCounts", org.json.JSONObject().apply { customDeviceCounts.forEach { (key, value) -> put(key, value) } }.toString())
        outState.putString("uaSource", uaSource)
        outState.putString("selectedBatchId", selectedBatchId)
        outState.putString("androidCustomUaText", androidCustomUaText)
        outState.putString("iosCustomUaText", iosCustomUaText)
        outState.putString("groupName", groupName)
        outState.putString("remark", remark)
        outState.putBoolean("assignProxies", assignProxies)
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(state: Bundle) {
        step = state.getInt("step", 0).coerceIn(0, 2)
        androidCount = state.getInt("androidCount", 5)
        iosCount = state.getInt("iosCount", 0)
        namePrefix = state.getString("namePrefix", "Profile") ?: "Profile"
        androidDeviceMode = state.getString("androidDeviceMode", "RANDOM") ?: "RANDOM"
        iosDeviceMode = state.getString("iosDeviceMode", "RANDOM") ?: "RANDOM"
        selectedAndroidIds.addAll(state.getStringArrayList("selectedAndroidIds") ?: emptyList())
        selectedIosIds.addAll(state.getStringArrayList("selectedIosIds") ?: emptyList())
        distributionMode = state.getString("distributionMode", "BALANCED") ?: "BALANCED"
        customDeviceCounts = try {
            val json = org.json.JSONObject(state.getString("customDeviceCounts", "{}") ?: "{}")
            buildMap<String, Int> { json.keys().forEach { key -> put(key, json.optInt(key, 0)) } }.toMutableMap()
        } catch (_: Exception) { mutableMapOf() }
        uaSource = state.getString("uaSource", "AUTO") ?: "AUTO"
        selectedBatchId = state.getString("selectedBatchId", "") ?: ""
        androidCustomUaText = state.getString("androidCustomUaText", "") ?: ""
        iosCustomUaText = state.getString("iosCustomUaText", "") ?: ""
        groupName = state.getString("groupName", "") ?: ""
        remark = state.getString("remark", "") ?: ""
        assignProxies = state.getBoolean("assignProxies", false)
    }

    private fun buildShell() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.nexora_bg)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(15)); setBackgroundColor(color(R.color.nexora_surface)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "×"; textSize = 22f; isAllCaps = false; minWidth = dp(44); minimumWidth = dp(44)
            cornerRadius = dp(14); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text))
            setOnClickListener { finish() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brand.addView(text("N E X O R A   /   P R O F I L E   S T U D I O", 9f, R.color.nexora_accent, true))
        title = text("Create profiles", 21f, R.color.nexora_text, true)
        subtitle = text("Build a reusable browser workspace.", 11f, R.color.nexora_muted)
        brand.addView(title); brand.addView(subtitle)
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(top)
        progress = text("STEP 1 OF 3", 10f, R.color.nexora_muted, true).apply { setPadding(dp(2), dp(18), 0, dp(8)) }
        header.addView(progress)
        stepTrack = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(3) { index ->
            val segment = View(this).apply { background = roundedFill(if (index == 0) color(R.color.nexora_accent) else color(R.color.nexora_line)) }
            stepTrack.addView(segment, LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (index > 0) marginStart = dp(5) })
        }
        header.addView(stepTrack)
        page.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(26)) }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.addView(View(this).apply { setBackgroundColor(color(R.color.nexora_line)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(14)); setBackgroundColor(color(R.color.nexora_surface)) }
        footerLabel = text("STEP 01 / 03", 10f, R.color.nexora_muted, true)
        footer.addView(footerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Back"; isAllCaps = false; cornerRadius = dp(13); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text))
            setOnClickListener { if (step > 0) { step--; renderStep() } }
        }
        footer.addView(backButton, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd = dp(8) })
        nextButton = MaterialButton(this).apply {
            text = "Continue"; isAllCaps = false; cornerRadius = dp(13); setOnClickListener { onNext() }
        }
        footer.addView(nextButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(footer)
        setContentView(page)
        UiFeedback.install(page)
    }

    private fun renderStep() {
        content.removeAllViews()
        val labels = listOf("BATCH SIZE", "DEVICE & USER-AGENT", "REVIEW & CREATE")
        progress.text = "STEP ${step + 1} OF 3   ·   ${labels[step]}"
        footerLabel.text = "STEP ${String.format("%02d", step + 1)} / 03"
        backButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (step == 2) "Create ${androidCount + iosCount} profiles" else "Continue"
        for (i in 0 until stepTrack.childCount) stepTrack.getChildAt(i).background = roundedFill(color(if (i <= step) R.color.nexora_accent else R.color.nexora_line))
        when (step) {
            0 -> renderBatchSize()
            1 -> renderDeviceAndUa()
            else -> renderReview()
        }
        UiFeedback.install(content)
    }

    private fun renderBatchSize() {
        subtitle.text = "Choose Android and iOS quantities independently."
        val batch = section("Batch size", "The initial Android release provisions up to 50 isolated WebView profiles per batch.")
        val androidField = field(batch, "Android profiles", "5", androidCount.toString(), InputType.TYPE_CLASS_NUMBER)
        androidField.addTextChangedListener(watcher { androidCount = it.toIntOrNull() ?: 0; updateTotal(batch) })
        val iosField = field(batch, "iOS UA templates (emulation)", "0", iosCount.toString(), InputType.TYPE_CLASS_NUMBER)
        iosField.addTextChangedListener(watcher { iosCount = it.toIntOrNull() ?: 0; updateTotal(batch) })
        batch.addView(text("iOS entries are Android WebView profiles using iOS compatibility UA templates. They are not native iOS browser profiles.", 10f, R.color.nexora_muted))
        updateTotal(batch)
        val naming = section("Naming", "Profiles receive unique names in one sequence.")
        val prefix = field(naming, "Profile name prefix", "Profile", namePrefix, InputType.TYPE_CLASS_TEXT)
        prefix.addTextChangedListener(watcher { namePrefix = it.trim() })
        addNote(naming, "Example", "${namePrefix.ifBlank { "Profile" }} 1 … ${namePrefix.ifBlank { "Profile" }} ${androidCount + iosCount}")
    }

    private fun renderDeviceAndUa() {
        subtitle.text = "Choose device pools and decide where the User-Agent data comes from."
        val deviceSection = section("Device selection", "Choose a random pool or restrict generation to specific models from the current database.")
        if (androidCount > 0) {
            addChoiceRow(deviceSection, "Android device pool", listOf("Random" to "RANDOM", "Specific" to "SELECTED"), androidDeviceMode) { androidDeviceMode = it; renderStep() }
            addButton(deviceSection, "Choose Android models (${selectedAndroidIds.size} selected)") { chooseDevices(false) }
        }
        if (iosCount > 0) {
            addChoiceRow(deviceSection, "iOS template device pool", listOf("Random" to "RANDOM", "Specific" to "SELECTED"), iosDeviceMode) { iosDeviceMode = it; renderStep() }
            addButton(deviceSection, "Choose iPhone templates (${selectedIosIds.size} selected)") { chooseDevices(true) }
        }
        val distribution = section("Device distribution", "Balanced spreads records evenly. Random chooses from the eligible pool for each record.")
        addSpinner(distribution, "Distribution mode", listOf("Balanced across selected devices", "Random device selection", "Custom per-device quantities"), when (distributionMode) { "RANDOM" -> 1; "CUSTOM" -> 2; else -> 0 }) { position ->
            val nextMode = when (position) { 1 -> "RANDOM"; 2 -> "CUSTOM"; else -> "BALANCED" }
            if (nextMode != distributionMode) {
                distributionMode = nextMode
                if (distributionMode == "CUSTOM") configureCustomDistribution()
            }
        }
        if (distributionMode == "CUSTOM") addButton(distribution, "Configure quantities") { configureCustomDistribution() }
        addNote(distribution, "OS version", "Latest supported version is selected per device unless a saved User-Agent batch supplies a specific version.")
        if (uaSource == "BATCH") addNote(distribution, "Saved batch behavior", "The saved batch's device and OS distribution is preserved; the distribution selector applies only to automatic generation.")

        val ua = section("User-Agent source", "Automatic generation is the fastest path. Reuse a saved standalone generator batch when you need exact pre-generated strings.")
        addChoiceRow(ua, "Source", listOf("Automatic" to "AUTO", "Saved batch" to "BATCH", "Paste UA strings" to "PASTE"), uaSource) { uaSource = it; renderStep() }
        if (uaSource == "BATCH") {
            if (savedBatches.isEmpty()) {
                addNote(ua, "No saved batches", "Create a batch in User-Agent Generator first, then return here.")
            } else {
                val labels = savedBatches.map { "${it.name} · ${it.records.size} records · ${it.records.count { r -> r.targetOs == "ANDROID" }} Android / ${it.records.count { r -> r.targetOs == "IOS" }} iOS" }
                val index = savedBatches.indexOfFirst { it.id == selectedBatchId }.coerceAtLeast(0)
                addSpinner(ua, "Saved User-Agent batch", labels, index) { selectedBatchId = savedBatches.getOrNull(it)?.id.orEmpty() }
            }
        } else if (uaSource == "PASTE") {
            addNote(ua, "Android User-Agent strings", "Paste one Android UA per line. The first requested number of lines is assigned in order.")
            val androidPaste = field(ua, "Android UA list", "Mozilla/5.0 (Linux; Android …)", androidCustomUaText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 3; gravity = android.view.Gravity.TOP }
            androidPaste.addTextChangedListener(watcher { androidCustomUaText = it })
            addNote(ua, "iOS User-Agent strings", "Paste one iOS UA template per line. These remain templates inside Android WebView.")
            val iosPaste = field(ua, "iOS UA list", "Mozilla/5.0 (iPhone; CPU iPhone OS …)", iosCustomUaText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 3; gravity = android.view.Gravity.TOP }
            iosPaste.addTextChangedListener(watcher { iosCustomUaText = it })
            addNote(ua, "Paste behavior", "Use Copy all in User-Agent Generator, then paste the Android and iOS lines into their matching fields. Strings are checked for basic OS markers before profile creation.")
        }
        addButton(ua, "Open User-Agent Generator") {
            startActivity(Intent(this, UserAgentGeneratorActivity::class.java))
        }
        addNote(ua, "In-app browser templates", if (inAppTemplates.isEmpty()) "None are currently published with source evidence. No in-app UA is included by default." else "${inAppTemplates.size} verified templates available. They are opt-in in the standalone generator.")
        addNote(ua, "Runtime limitation", "Android profiles run Android System WebView. iOS entries are UA compatibility templates only; they do not create an iOS engine or change device hardware.")
    }

    private fun renderReview() {
        subtitle.text = "Review the exact device and User-Agent allocation before saving."
        val organization = section("Organization", "Optional local labels for your profile workspace.")
        val group = field(organization, "Group name", "e.g. QA / Research", groupName, InputType.TYPE_CLASS_TEXT)
        group.addTextChangedListener(watcher { groupName = it })
        val note = field(organization, "Remark", "Optional note", remark, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        note.addTextChangedListener(watcher { remark = it })

        val network = section("Proxy distribution", "Optional one-to-one assignment from the saved proxy library. Profiles beyond available supported proxies remain direct.")
        addToggle(network, "Assign saved proxies sequentially", "Assign one distinct supported saved proxy per profile in library order.", assignProxies) { assignProxies = it }
        val supported = proxies.filter { it.isSupportedByProxyRelay }
        addNote(network, "Available proxies", "${supported.size} supported · ${proxies.size - supported.size} unsupported/import-only · ${androidCount + iosCount} profiles requested")
        addButton(network, "Manage saved proxies") {
            proxyManagerOpened = true
            startActivity(Intent(this, ProxyLibraryActivity::class.java))
        }

        val summary = section("Creation summary", "These settings will be persisted locally on this device.")
        addNote(summary, "Profiles", "${androidCount + iosCount} total · $androidCount Android · $iosCount iOS UA templates")
        addNote(summary, "Devices", deviceSummary())
        addNote(summary, "Distribution", distributionMode.lowercase().replaceFirstChar { it.uppercase() })
        addNote(summary, "User-Agent source", when (uaSource) {
            "AUTO" -> "Automatic from current database"
            "BATCH" -> savedBatches.firstOrNull { it.id == selectedBatchId }?.name ?: "No batch selected"
            else -> "Pasted custom UA strings"
        })
        addNote(summary, "Database", DeviceDb.dataVersion(this).ifBlank { "Bundled catalog" })
        addNote(summary, "Isolation", "Each created record receives its own AndroidX WebKit named profile when MULTI_PROFILE is supported.")
    }

    private fun onNext() {
        when (step) {
            0 -> {
                if (androidCount < 0 || iosCount < 0 || androidCount + iosCount !in 1..50) {
                    toast("Create between 1 and 50 profiles per batch. User-Agent-only batches can contain up to 500 records.")
                    return
                }
                if (namePrefix.isBlank() || namePrefix.length > 45) { toast("Enter a profile name prefix up to 45 characters"); return }
            }
            1 -> {
                val errors = validateDeviceAndUa()
                if (errors.isNotEmpty()) { showErrors(errors); return }
            }
            2 -> {
                createProfiles()
                return
            }
        }
        step++
        renderStep()
    }

    private fun validateDeviceAndUa(): List<String> {
        val errors = mutableListOf<String>()
        val native = WebSettings.getDefaultUserAgent(this)
        val request = generationRequest()
        if (uaSource == "AUTO") {
            errors += UserAgentGenerationEngine.validate(request, presets, inAppTemplates, native)
        } else if (uaSource == "BATCH") {
            val batch = savedBatches.firstOrNull { it.id == selectedBatchId }
            if (batch == null) errors += "Choose a saved User-Agent batch."
            else {
                val androidRows = batch.records.filter { it.targetOs == "ANDROID" }
                val iosRows = batch.records.filter { it.targetOs == "IOS" }
                if (androidRows.size < androidCount) errors += "The saved batch has ${androidRows.size} Android records; $androidCount are required."
                if (iosRows.size < iosCount) errors += "The saved batch has ${iosRows.size} iOS records; $iosCount are required."
                val requiredRows = androidRows.take(androidCount) + iosRows.take(iosCount)
                if (requiredRows.any { row -> presets.none { it.id == row.deviceId && it.isIos == (row.targetOs == "IOS") } }) errors += "The saved batch contains a device that is missing from the active catalog. Regenerate the batch."
                if (requiredRows.any { it.userAgent.length !in 20..2048 || !it.userAgent.contains("Mozilla/", ignoreCase = true) }) errors += "The saved batch contains an invalid User-Agent string."
                if (androidDeviceMode == "SELECTED" && androidRows.take(androidCount).any { it.deviceId !in selectedAndroidIds }) errors += "The saved Android UA batch contains devices outside your selected pool."
                if (iosDeviceMode == "SELECTED" && iosRows.take(iosCount).any { it.deviceId !in selectedIosIds }) errors += "The saved iOS UA batch contains devices outside your selected pool."
            }
        } else {
            errors += UserAgentGenerationEngine.validate(request, presets, inAppTemplates, native)
            val androidLines = parseUaLines(androidCustomUaText)
            val iosLines = parseUaLines(iosCustomUaText)
            if (androidLines.size < androidCount) errors += "Paste at least $androidCount Android User-Agent strings."
            if (iosLines.size < iosCount) errors += "Paste at least $iosCount iOS User-Agent strings."
            if (androidLines.take(androidCount).any { !validPastedUa(it, "ANDROID") }) errors += "Every selected Android UA must contain Mozilla/ and an Android marker."
            if (iosLines.take(iosCount).any { !validPastedUa(it, "IOS") }) errors += "Every selected iOS UA must contain Mozilla/ and an iPhone/iPad marker."
        }
        if (distributionMode == "CUSTOM" && uaSource == "AUTO") errors += validateCustomDistribution()
        val names = (1..(androidCount + iosCount)).map { "$namePrefix $it".trim().lowercase() }
        if (names.size != names.toSet().size) errors += "Generated profile names are not unique."
        val existing = readLocalStore { profileStore.loadAll() }.getOrElse { return listOf(it.message.orEmpty()) }.map { it.name.trim().lowercase() }.toSet()
        if (names.any { it in existing }) errors += "A profile name already exists. Change the prefix."
        return errors.filter { it.isNotBlank() }.distinct()
    }

    private fun createProfiles() {
        val errors = validateDeviceAndUa()
        if (errors.isNotEmpty()) { showErrors(errors); step = 1; renderStep(); return }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this).setTitle("WebView update required")
                .setMessage("This device's Android System WebView does not support isolated named profiles (MULTI_PROFILE). No profiles were created. Update Android System WebView or Chrome and retry.")
                .setPositiveButton("OK", null).show()
            return
        }
        val request = generationRequest()
        val records = try {
            if (uaSource == "AUTO") {
                UserAgentGenerationEngine.generate(request, presets, inAppTemplates, WebSettings.getDefaultUserAgent(this), DeviceDb.dataVersion(this), System.nanoTime().toInt())
            } else if (uaSource == "BATCH") {
                val batch = savedBatches.first { it.id == selectedBatchId }
                batch.records.filter { it.targetOs == "ANDROID" }.take(androidCount) + batch.records.filter { it.targetOs == "IOS" }.take(iosCount)
            } else {
                val base = UserAgentGenerationEngine.generate(request, presets, inAppTemplates, WebSettings.getDefaultUserAgent(this), DeviceDb.dataVersion(this), System.nanoTime().toInt())
                val androidLines = parseUaLines(androidCustomUaText)
                val iosLines = parseUaLines(iosCustomUaText)
                var androidIndex = 0
                var iosIndex = 0
                base.map { record ->
                    val value = if (record.targetOs == "ANDROID") androidLines[androidIndex++] else iosLines[iosIndex++]
                    record.copy(userAgent = value, browserFamily = "Custom UA", runtimeNote = "User-provided UA string; device/runtime compatibility must be reviewed by the user.")
                }
            }
        } catch (e: Exception) {
            toast("Could not prepare User-Agent records: ${e.message ?: "unknown error"}")
            return
        }
        if (records.size != androidCount + iosCount) { toast("The selected UA batch did not provide enough records. No profiles were created."); return }

        val existing = readLocalStore { profileStore.loadAll() }.getOrElse { return }
        val now = System.currentTimeMillis()
        val usableProxies = if (assignProxies) proxies.filter { it.isSupportedByProxyRelay } else emptyList()
        val newProfiles = mutableListOf<BrowserProfile>()
        try {
            records.forEachIndexed { index, record ->
                val preset = presets.firstOrNull { it.id == record.deviceId }
                    ?: throw IllegalStateException("${record.deviceName} is no longer in the active database.")
                val proxy = usableProxies.getOrNull(index)
                newProfiles += BrowserProfile(
                    id = UUID.randomUUID().toString(),
                    name = "$namePrefix ${index + 1}".trim(),
                    deviceId = preset.id,
                    osVersion = record.osVersion,
                    ramGb = preset.ramOptions.firstOrNull() ?: 8,
                    storageGb = preset.storageOptions.firstOrNull() ?: 128,
                    userAgent = record.userAgent,
                    proxyType = proxy?.type ?: "NONE",
                    proxyHost = proxy?.host.orEmpty(),
                    proxyPort = proxy?.port ?: 0,
                    proxyUser = "",
                    proxyPass = "",
                    proxyEntryId = proxy?.id.orEmpty(),
                    timezoneId = "",
                    language = record.locale.ifBlank { Locale.getDefault().toLanguageTag() },
                    webrtcBlock = true,
                    canvasNoise = true,
                    webglSpoof = true,
                    audioNoise = true,
                    startupUrl = "",
                    startupUrls = emptyList(),
                    groupName = groupName.trim(),
                    remark = remark.trim(),
                    targetOs = record.targetOs,
                    userAgentMode = if (uaSource == "AUTO") "AUTO" else "CUSTOM",
                    timezoneMode = "DEVICE",
                    languageMode = if (record.locale == Locale.getDefault().toLanguageTag()) "DEVICE" else "CUSTOM",
                    webrtcMode = "DISABLED",
                    mediaDevicesNoise = false,
                    clientRectsNoise = false,
                    speechSynthesisNoise = false,
                    dntMode = "DEFAULT",
                    hardwareAcceleration = true,
                    blockImages = false,
                    seed = Random.nextInt(1, Int.MAX_VALUE),
                    createdAt = now + index,
                    lastUsedAt = 0L
                )
            }
        } catch (e: Exception) {
            toast("Could not prepare profiles: ${e.message ?: "unknown error"}. Nothing was saved.")
            return
        }

        try {
            val webProfileStore = androidx.webkit.ProfileStore.getInstance()
            newProfiles.forEach { webProfileStore.getOrCreateProfile(it.webViewProfileKey) }
        } catch (e: Exception) {
            toast("Could not provision isolated WebView storage: ${e.message ?: "unknown error"}. No profiles were saved.")
            return
        }
        if (!profileStore.saveAllChecked(existing + newProfiles)) {
            toast("Could not save profiles. No success was reported; check storage and retry.")
            return
        }
        val proxyCount = newProfiles.count { it.proxyEntryId.isNotBlank() }
        AlertDialog.Builder(this).setTitle("Profiles created")
            .setMessage("Saved ${newProfiles.size} profiles.\n\nAndroid: $androidCount\niOS UA templates: $iosCount\nProxy assignments: $proxyCount\n\nAll profile metadata is stored locally. iOS entries remain Android WebView compatibility templates.")
            .setPositiveButton("Open profiles") { _, _ -> setResult(RESULT_OK); finish() }.show()
    }

    private fun parseUaLines(value: String): List<String> = value.lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun validPastedUa(value: String, targetOs: String): Boolean {
        if (value.length !in 20..2048 || !value.contains("Mozilla/", ignoreCase = true)) return false
        return if (targetOs == "ANDROID") value.contains("Android", ignoreCase = true)
        else value.contains("iPhone", ignoreCase = true) || value.contains("iPad", ignoreCase = true)
    }

    private fun generationRequest() = UserAgentGenerationRequest(
        androidCount = androidCount,
        iosCount = iosCount,
        androidDeviceMode = androidDeviceMode,
        iosDeviceMode = iosDeviceMode,
        selectedAndroidDeviceIds = selectedAndroidIds.toSet(),
        selectedIosDeviceIds = selectedIosIds.toSet(),
        distributionMode = distributionMode,
        customDeviceCounts = customDeviceCounts.toMap(),
        androidBrowserFamilies = if (androidCount > 0) setOf("Chrome") else emptySet(),
        iosBrowserFamilies = if (iosCount > 0) setOf("Safari") else emptySet(),
        locale = Locale.getDefault().toLanguageTag()
    )

    private fun validateCustomDistribution(): String {
        val eligibleAndroid = UserAgentGenerationEngine.eligiblePool(presets, false, androidDeviceMode, selectedAndroidIds).map { it.id }.toSet()
        val eligibleIos = UserAgentGenerationEngine.eligiblePool(presets, true, iosDeviceMode, selectedIosIds).map { it.id }.toSet()
        val a = customDeviceCounts.filterKeys { it in eligibleAndroid }.values.sum()
        val i = customDeviceCounts.filterKeys { it in eligibleIos }.values.sum()
        return when {
            androidCount > 0 && a != androidCount -> "Custom Android quantities must total $androidCount."
            androidCount == 0 && a != 0 -> "Custom Android quantities must be zero when Android count is zero."
            iosCount > 0 && i != iosCount -> "Custom iOS quantities must total $iosCount."
            iosCount == 0 && i != 0 -> "Custom iOS quantities must be zero when iOS count is zero."
            customDeviceCounts.values.any { it < 0 } -> "Custom quantities cannot be negative."
            else -> ""
        }
    }

    private fun chooseDevices(isIos: Boolean) {
        val pool = presets.filter { it.isIos == isIos }
        val selected = if (isIos) selectedIosIds else selectedAndroidIds
        if (pool.isEmpty()) { toast("No matching device records are in the database"); return }
        val working = selected.toMutableSet()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val search = EditText(this).apply { hint = "Search brand or model"; textSize = 14f; setSingleLine(true) }
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(ScrollView(this).apply { addView(rows); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)) })
        fun renderRows(query: String) {
            rows.removeAllViews()
            pool.filter { "${it.brand} ${it.model}".contains(query.trim(), ignoreCase = true) }.forEach { device ->
                val check = android.widget.CheckBox(this).apply {
                    text = device.displayName; isChecked = device.id in working; setTextColor(color(R.color.nexora_text))
                    setOnCheckedChangeListener { _, checked -> if (checked) working.add(device.id) else working.remove(device.id) }
                }
                rows.addView(check)
            }
        }
        renderRows("")
        search.addTextChangedListener(watcher { renderRows(it) })
        AlertDialog.Builder(this).setTitle("Select ${if (isIos) "iOS" else "Android"} models")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                selected.clear(); selected.addAll(working)
                if (isIos) iosDeviceMode = "SELECTED" else androidDeviceMode = "SELECTED"
                customDeviceCounts.keys.retainAll((selectedAndroidIds + selectedIosIds).toSet())
                renderStep()
            }
            .setNeutralButton("Select all") { _, _ ->
                selected.clear(); pool.forEach { selected.add(it.id) }
                if (isIos) iosDeviceMode = "SELECTED" else androidDeviceMode = "SELECTED"
                renderStep()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun configureCustomDistribution() {
        val androidPool = UserAgentGenerationEngine.eligiblePool(presets, false, androidDeviceMode, selectedAndroidIds)
        val iosPool = UserAgentGenerationEngine.eligiblePool(presets, true, iosDeviceMode, selectedIosIds)
        val pool = androidPool + iosPool
        if (pool.isEmpty()) { toast("Choose device pools first"); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val fields = linkedMapOf<String, EditText>()
        pool.forEach { device ->
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText((customDeviceCounts[device.id] ?: 0).toString()); textSize = 14f }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(device.displayName, 12f, R.color.nexora_text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(input, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(row); fields[device.id] = input
        }
        AlertDialog.Builder(this).setTitle("Custom device quantities")
            .setMessage("Android total: $androidCount · iOS total: $iosCount")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val values = fields.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                val a = values.filterKeys { id -> presets.any { it.id == id && !it.isIos } }.values.sum()
                val i = values.filterKeys { id -> presets.any { it.id == id && it.isIos } }.values.sum()
                if (a != androidCount || i != iosCount || values.values.any { it < 0 }) toast("Quantities must total $androidCount Android and $iosCount iOS. Nothing changed.")
                else { customDeviceCounts = values.toMutableMap(); toast("Custom distribution saved") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deviceSummary(): String {
        val a = UserAgentGenerationEngine.eligiblePool(presets, false, androidDeviceMode, selectedAndroidIds).joinToString { it.displayName }
        val i = UserAgentGenerationEngine.eligiblePool(presets, true, iosDeviceMode, selectedIosIds).joinToString { it.displayName }
        return listOf(if (androidCount > 0) "Android: $a" else "", if (iosCount > 0) "iOS: $i" else "").filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun updateTotal(parent: LinearLayout) {
        val total = parent.findViewWithTag<TextView>(TOTAL_TAG)
        val text = "TOTAL  ·  ${androidCount.coerceAtLeast(0) + iosCount.coerceAtLeast(0)} / 50"
        if (total != null) total.text = text else parent.addView(text(text, 13f, R.color.nexora_accent, true).apply { tag = TOTAL_TAG; setPadding(0, dp(8), 0, 0) })
    }

    private fun section(heading: String, description: String): LinearLayout {
        val card = MaterialCardView(this).apply { radius = dp(17).toFloat(); cardElevation = 0f; setCardBackgroundColor(color(R.color.nexora_surface)); strokeWidth = dp(1); strokeColor = color(R.color.nexora_line) }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(15)) }
        inner.addView(text(heading, 14f, R.color.nexora_text, true))
        inner.addView(text(description, 11f, R.color.nexora_muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        card.addView(inner)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return inner
    }

    private fun field(parent: LinearLayout, label: String, hint: String, value: String, type: Int): EditText {
        parent.addView(text(label, 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        return EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; textSize = 14f
            setTextColor(color(R.color.nexora_text)); setHintTextColor(color(R.color.nexora_muted)); background = roundedBackground(color(R.color.nexora_raised))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }
    }

    private fun addSpinner(parent: LinearLayout, label: String, values: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        parent.addView(text(label, 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        val spinner = Spinner(this).apply { adapter = android.widget.ArrayAdapter(this@ProfileStudioActivity, android.R.layout.simple_spinner_dropdown_item, values); setSelection(selected.coerceIn(0, values.lastIndex)) }
        spinner.onItemSelectedListener = selectionListener(onSelected)
        parent.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun addChoiceRow(parent: LinearLayout, label: String, choices: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
        parent.addView(text(label, 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(4)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        choices.forEach { (name, value) ->
            val selectedNow = selected == value
            val button = MaterialButton(this, null, if (selectedNow) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = name; isAllCaps = false; cornerRadius = dp(12); setOnClickListener { onSelected(value) }
                if (!selectedNow) { strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text)) }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (row.childCount > 0) marginStart = dp(7) })
        }
        parent.addView(row)
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; cornerRadius = dp(12); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text)); setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
    }

    private fun addToggle(parent: LinearLayout, heading: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(heading, 12f, R.color.nexora_text, true))
        column.addView(text(description, 10f, R.color.nexora_muted))
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = android.widget.Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> onChanged(value) } }
        row.addView(check)
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
    }

    private fun addNote(parent: LinearLayout, heading: String, body: String) {
        parent.addView(text(heading, 11f, R.color.nexora_text, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        parent.addView(text(body, 10f, R.color.nexora_muted))
    }

    private fun text(value: String, size: Float, colorId: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color(colorId)); if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun watcher(onChanged: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChanged(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun selectionListener(onSelected: (Int) -> Unit): android.widget.AdapterView.OnItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { onSelected(position) }
    }

    private fun roundedBackground(fill: Int) = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(fill); setStroke(dp(1), color(R.color.nexora_line)) }
    private fun roundedFill(fill: Int) = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat(); setColor(fill) }
    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    private fun showErrors(errors: List<String>) = AlertDialog.Builder(this).setTitle("Fix before continuing").setMessage(errors.joinToString("\n\n• ", prefix = "• ")).setPositiveButton("OK", null).show()

    companion object {
        const val EXTRA_UA_BATCH_ID = "com.nexora.browser.extra.UA_BATCH_ID"
        private const val TOTAL_TAG = "profile_total"
    }
}
