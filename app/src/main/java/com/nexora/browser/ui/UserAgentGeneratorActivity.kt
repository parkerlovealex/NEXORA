package com.nexora.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.AutoCompleteTextView
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.GeneratedUserAgent
import com.nexora.browser.data.UserAgentBatch
import com.nexora.browser.data.UserAgentBatchStore
import com.nexora.browser.data.UserAgentTemplate
import com.nexora.browser.util.UserAgentGenerationEngine
import com.nexora.browser.util.UserAgentGenerationRequest
import java.util.Locale
import java.util.UUID

/** Standalone, database-driven User-Agent generator. */
class UserAgentGeneratorActivity : AppCompatActivity() {
    private lateinit var presets: List<DevicePreset>
    private lateinit var inAppTemplates: List<UserAgentTemplate>
    private lateinit var batchStore: UserAgentBatchStore
    private lateinit var root: LinearLayout
    private lateinit var androidCountField: EditText
    private lateinit var iosCountField: EditText
    private lateinit var localeField: EditText
    private lateinit var countryField: AutoCompleteTextView
    private lateinit var androidDeviceButton: MaterialButton
    private lateinit var iosDeviceButton: MaterialButton
    private lateinit var distributionSpinner: Spinner
    private lateinit var androidDeviceModeSpinner: Spinner
    private lateinit var iosDeviceModeSpinner: Spinner
    private lateinit var androidVersionSpinner: Spinner
    private lateinit var iosVersionSpinner: Spinner
    private lateinit var resultPanel: LinearLayout
    private lateinit var savedBatchesPanel: LinearLayout
    private lateinit var resultSummary: TextView
    private lateinit var customDistributionButton: MaterialButton
    private val selectedAndroidIds = linkedSetOf<String>()
    private val selectedIosIds = linkedSetOf<String>()
    private val selectedInAppIds = linkedSetOf<String>()
    private var customDeviceCounts = mutableMapOf<String, Int>()
    private var lastBatch: UserAgentBatch? = null
    private var androidChromeChecked = true
    private var iosSafariChecked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presets = DeviceDb.load(this)
        inAppTemplates = DeviceDb.loadVerifiedUserAgentTemplates(this)
        batchStore = UserAgentBatchStore(this)
        buildUi()
        savedInstanceState?.getString(STATE_BATCH_ID)?.let { id ->
            lastBatch = batchStore.find(id)
            lastBatch?.let { renderResults(it.records, it.name) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::savedBatchesPanel.isInitialized) renderSavedBatches()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_BATCH_ID, lastBatch?.id.orEmpty())
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(color(R.color.nexora_surface))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"; textSize = 24f; isAllCaps = false; minWidth = dp(44); minimumWidth = dp(44)
            cornerRadius = dp(14); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line))
            setTextColor(color(R.color.nexora_text)); setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brand.addView(text("N E X O R A   /   T O O L S", 9f, R.color.nexora_accent, true))
        brand.addView(text("User-Agent Generator", 21f, R.color.nexora_text, true))
        brand.addView(text("Build reusable UA sets from the local verified catalog.", 11f, R.color.nexora_muted))
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(top)
        val dbVersion = DeviceDb.dataVersion(this).ifBlank { "Bundled catalog" }
        header.addView(text("DATABASE  ·  $dbVersion", 10f, R.color.nexora_muted, true).apply { setPadding(dp(60), dp(10), 0, 0) })
        page.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(26)) }
        scroll.addView(root)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val os = section("01  /  Operating systems", "Generate Android and iOS templates in one batch. Each platform has its own quantity.")
        androidCountField = field(os, "Android quantity", "10", "10", InputType.TYPE_CLASS_NUMBER)
        iosCountField = field(os, "iOS quantity", "0", "0", InputType.TYPE_CLASS_NUMBER)
        val total = text("TOTAL  ·  10", 13f, R.color.nexora_accent, true)
        os.addView(total)
        val updateTotal = {
            val a = androidCountField.text.toString().toIntOrNull() ?: 0
            val i = iosCountField.text.toString().toIntOrNull() ?: 0
            total.text = "TOTAL  ·  ${a + i} / 500"
            total.setTextColor(color(if (a + i in 1..500) R.color.nexora_accent else R.color.nexora_cta))
        }
        androidCountField.addTextChangedListener(watcher { updateTotal() })
        iosCountField.addTextChangedListener(watcher { updateTotal() })

        val devices = section("02  /  Device selection", "Use the complete catalog or restrict generation to models you select.")
        androidDeviceModeSpinner = spinner(devices, "Android device pool", listOf("Random devices from database", "Only selected devices"))
        androidDeviceButton = button("Choose Android devices") { chooseDevices(false) }
        devices.addView(androidDeviceButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        iosDeviceModeSpinner = spinner(devices, "iOS device pool", listOf("Random devices from database", "Only selected devices"))
        iosDeviceButton = button("Choose iOS devices") { chooseDevices(true) }
        devices.addView(iosDeviceButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        distributionSpinner = spinner(devices, "Distribution", listOf("Balanced across devices", "Random device selection", "Custom per-device quantities"))
        customDistributionButton = button("Configure custom quantities") { configureCustomDistribution() }
        devices.addView(customDistributionButton)
        customDistributionButton.visibility = View.GONE
        distributionSpinner.setSelection(0)
        distributionSpinner.onItemSelectedListener = selectionListener { position ->
            customDistributionButton.visibility = if (position == 2) View.VISIBLE else View.GONE
        }
        addVersionSpinner(devices, "Android OS version", false).also { androidVersionSpinner = it }
        addVersionSpinner(devices, "iOS version", true).also { iosVersionSpinner = it }

        val browser = section("03  /  Browser family", "Only templates supported by the current database and installed engine can be generated.")
        val chrome = CheckBox(this).apply { text = "Chrome · Android · installed WebView Chromium"; isChecked = true; setTextColor(color(R.color.nexora_text)); setOnCheckedChangeListener { _, checked -> androidChromeChecked = checked } }
        browser.addView(chrome)
        val safari = CheckBox(this).apply { text = "Safari · iOS compatibility template"; isChecked = true; setTextColor(color(R.color.nexora_text)); setOnCheckedChangeListener { _, checked -> iosSafariChecked = checked } }
        browser.addView(safari)
        addNote(browser, "Not yet available", "Firefox, Edge and Opera require verified browser-specific UA templates in the GitHub catalog. NEXORA will not invent browser versions or strings.")

        val embedded = section("04  /  In-app browsers", "Optional. Nothing is included unless you explicitly select an evidence-backed template.")
        if (inAppTemplates.isEmpty()) {
            addNote(embedded, "No verified templates published", "The current device catalog has no evidence-backed in-app UA templates. This section will populate automatically when the database publishes verified records.")
        } else {
            inAppTemplates.distinctBy { it.appId }.forEach { template ->
                val check = CheckBox(this).apply {
                    text = template.displayName
                    setTextColor(color(R.color.nexora_text))
                    setOnCheckedChangeListener { _, checked -> if (checked) selectedInAppIds.add(template.appId) else selectedInAppIds.remove(template.appId) }
                }
                embedded.addView(check)
            }
        }

        val locale = section("05  /  Country & locale", "Search a country to suggest a locale, then edit the language tag if needed. Locale is metadata, not part of the UA string.")
        locale.addView(text("Country / region", 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        val countryPairs = Locale.getISOCountries().map { code -> code to Locale("", code).getDisplayCountry(Locale.getDefault()) }
            .filter { it.second.isNotBlank() }.sortedBy { it.second }
        val countryLabels = listOf("Use device default") + countryPairs.map { "${it.second} (${it.first})" }
        val countryCodes = mapOf("Use device default" to "") + countryPairs.associate { "${it.second} (${it.first})" to it.first }
        countryField = AutoCompleteTextView(this).apply {
            hint = "Search country..."; threshold = 1; setTextColor(color(R.color.nexora_text)); setHintTextColor(color(R.color.nexora_muted))
            background = rounded(color(R.color.nexora_raised), color(R.color.nexora_line)); setPadding(dp(12), dp(10), dp(12), dp(10))
            setAdapter(android.widget.ArrayAdapter(this@UserAgentGeneratorActivity, android.R.layout.simple_dropdown_item_1line, countryLabels))
            setOnItemClickListener { _, _, position, _ ->
                val selected = countryLabels.getOrNull(position).orEmpty()
                val code = countryCodes[selected].orEmpty()
                if (code.isBlank()) localeField.setText(Locale.getDefault().toLanguageTag())
                else {
                    val language = Locale.getDefault().language.takeIf { it.isNotBlank() && it != "und" } ?: "en"
                    localeField.setText(Locale.Builder().setLanguage(language).setRegion(code).build().toLanguageTag())
                }
            }
        }
        locale.addView(countryField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        localeField = field(locale, "Language tag", "en-US", Locale.getDefault().toLanguageTag(), InputType.TYPE_CLASS_TEXT)

        val generate = section("06  /  Generate", "Generate up to 500 records. Results are saved locally as a reusable batch.")
        addButton(generate, "Generate User-Agents", primary = true) { generateBatch() }
        resultPanel = section("Results", "Generated records appear here. You can copy the full batch or send it to Profile Studio.")
        resultSummary = text("No batch generated yet.", 12f, R.color.nexora_muted)
        resultPanel.addView(resultSummary)
        savedBatchesPanel = section("Saved batches", "Locally stored UA batches can be reopened or deleted. Nothing is uploaded.")
        renderSavedBatches()
        addNote(root, "Runtime honesty", "An iOS UA is a compatibility template, not an iOS browser runtime. Applying any UA changes the string only; it does not replace Android WebView, the operating system or device hardware.")
        setContentView(page)
        UiFeedback.install(page)
    }

    private fun generateBatch() {
        lastBatch = null
        val androidCount = androidCountField.text.toString().toIntOrNull() ?: 0
        val iosCount = iosCountField.text.toString().toIntOrNull() ?: 0
        val androidMode = if (androidDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM"
        val iosMode = if (iosDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM"
        val distribution = when (distributionSpinner.selectedItemPosition) { 1 -> "RANDOM"; 2 -> "CUSTOM"; else -> "BALANCED" }
        val request = UserAgentGenerationRequest(
            androidCount = androidCount,
            iosCount = iosCount,
            androidDeviceMode = androidMode,
            iosDeviceMode = iosMode,
            selectedAndroidDeviceIds = selectedAndroidIds.toSet(),
            selectedIosDeviceIds = selectedIosIds.toSet(),
            distributionMode = distribution,
            customDeviceCounts = customDeviceCounts.toMap(),
            androidOsVersion = versionValue(androidVersionSpinner),
            iosOsVersion = versionValue(iosVersionSpinner),
            androidBrowserFamilies = if (androidChromeChecked) setOf("Chrome") else emptySet(),
            iosBrowserFamilies = if (iosSafariChecked) setOf("Safari") else emptySet(),
            inAppBrowserIds = selectedInAppIds.toSet(),
            locale = localeField.text.toString().trim()
        )
        val native = WebSettings.getDefaultUserAgent(this)
        val errors = UserAgentGenerationEngine.validate(request, presets, inAppTemplates, native)
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Check generation settings").setMessage(errors.joinToString("\n\n• ", prefix = "• ")).setPositiveButton("OK", null).show()
            return
        }
        try {
            val records = UserAgentGenerationEngine.generate(
                request, presets, inAppTemplates, native, DeviceDb.dataVersion(this), System.nanoTime().toInt()
            )
            val batch = UserAgentBatch(
                id = UUID.randomUUID().toString(),
                name = "UA batch · ${records.size} records",
                createdAt = System.currentTimeMillis(),
                databaseVersion = DeviceDb.dataVersion(this),
                records = records
            )
            if (!batchStore.save(batch)) {
                toast("Generated ${records.size} records, but the local batch could not be saved. Copy or retry before leaving.")
                renderResults(records, batch.name)
                return
            }
            lastBatch = batch
            renderResults(records, batch.name)
            renderSavedBatches()
            toast("Generated and saved ${records.size} User-Agent records")
        } catch (e: Exception) {
            toast("Generation failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun renderResults(records: List<GeneratedUserAgent>, batchName: String) {
        if (resultPanel.childCount > 3) resultPanel.removeViews(3, resultPanel.childCount - 3)
        resultSummary.text = "$batchName\n${records.size} records · ${records.count { it.targetOs == "ANDROID" }} Android · ${records.count { it.targetOs == "IOS" }} iOS · database ${records.firstOrNull()?.databaseVersion ?: "unknown"}"
        val output = records.take(12).mapIndexed { index, row ->
            "${index + 1}. ${row.targetOs} · ${row.deviceName} · ${row.osVersion} · ${row.browserFamily}\n${row.userAgent}"
        }.joinToString("\n\n")
        val preview = TextView(this).apply {
            text = output + if (records.size > 12) "\n\n… ${records.size - 12} more records. Use Copy all to view the complete batch." else ""
            textSize = 10f
            setTextColor(color(R.color.nexora_text))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(color(R.color.nexora_raised), color(R.color.nexora_line))
        }
        resultPanel.addView(preview)
        addButton(resultPanel, "Copy all User-Agents") {
            val all = records.joinToString("\n") { it.userAgent }
            copyToClipboard("NEXORA User-Agents", all, "Copied ${records.size} User-Agent strings")
        }
        if (records.any { it.targetOs == "ANDROID" }) addButton(resultPanel, "Copy Android UAs") {
            val values = records.filter { it.targetOs == "ANDROID" }.joinToString("\n") { it.userAgent }
            copyToClipboard("NEXORA Android User-Agents", values, "Copied ${records.count { it.targetOs == "ANDROID" }} Android UAs")
        }
        if (records.any { it.targetOs == "IOS" }) addButton(resultPanel, "Copy iOS UA templates") {
            val values = records.filter { it.targetOs == "IOS" }.joinToString("\n") { it.userAgent }
            copyToClipboard("NEXORA iOS UA templates", values, "Copied ${records.count { it.targetOs == "IOS" }} iOS templates")
        }
        addButton(resultPanel, "Export JSON") {
            val json = org.json.JSONArray().apply { records.forEach { put(it.toJson()) } }.toString(2)
            shareText("NEXORA User-Agent batch", json, "application/json")
        }
        addButton(resultPanel, "Use this batch in Profile Studio", primary = true) {
            val batch = lastBatch
            if (batch == null) toast("This batch was not saved. Generate again after checking device storage.")
            else startActivity(Intent(this, ProfileStudioActivity::class.java).putExtra(ProfileStudioActivity.EXTRA_UA_BATCH_ID, batch.id))
        }
        UiFeedback.install(resultPanel)
    }

    private fun renderSavedBatches() {
        if (!::savedBatchesPanel.isInitialized) return
        if (savedBatchesPanel.childCount > 2) savedBatchesPanel.removeViews(2, savedBatchesPanel.childCount - 2)
        val batches = batchStore.loadAll()
        if (batches.isEmpty()) {
            savedBatchesPanel.addView(text("No saved batches yet.", 11f, R.color.nexora_muted))
            UiFeedback.install(savedBatchesPanel)
            return
        }
        batches.forEach { batch ->
            val card = MaterialCardView(this).apply {
                radius = dp(12).toFloat(); cardElevation = 0f
                setCardBackgroundColor(color(R.color.nexora_raised)); strokeWidth = dp(1); strokeColor = color(R.color.nexora_line)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(batch.name, 12f, R.color.nexora_text, true))
            details.addView(text("${batch.records.size} records · ${batch.records.count { it.targetOs == "ANDROID" }} Android / ${batch.records.count { it.targetOs == "IOS" }} iOS · ${batch.databaseVersion}", 9f, R.color.nexora_muted))
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val open = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Open"; isAllCaps = false; cornerRadius = dp(10); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text))
                setOnClickListener { lastBatch = batch; renderResults(batch.records, batch.name) }
            }
            row.addView(open, LinearLayout.LayoutParams(dp(76), dp(40)).apply { marginStart = dp(5) })
            val delete = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "×"; isAllCaps = false; cornerRadius = dp(10); minWidth = dp(40); strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text))
                setOnClickListener {
                    AlertDialog.Builder(this@UserAgentGeneratorActivity).setTitle("Delete saved batch?")
                        .setMessage("Delete ${batch.name} from this device?")
                        .setPositiveButton("Delete") { _, _ ->
                            if (batchStore.delete(batch.id)) {
                                if (lastBatch?.id == batch.id) lastBatch = null
                                renderSavedBatches()
                                toast("Saved batch deleted")
                            } else toast("Could not delete saved batch")
                        }.setNegativeButton("Cancel", null).show()
                }
            }
            row.addView(delete, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginStart = dp(4) })
            card.addView(row)
            savedBatchesPanel.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        UiFeedback.install(savedBatchesPanel)
    }

    private fun chooseDevices(isIos: Boolean) {
        val pool = presets.filter { it.isIos == isIos }
        val selected = if (isIos) selectedIosIds else selectedAndroidIds
        if (pool.isEmpty()) { toast("No ${if (isIos) "iOS" else "Android"} devices are available in the current database"); return }
        val working = selected.toMutableSet()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val search = EditText(this).apply { hint = "Search brand or model"; textSize = 14f; setSingleLine(true) }
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)) }
        container.addView(scroll)
        fun renderRows(query: String) {
            rows.removeAllViews()
            pool.filter { "${it.brand} ${it.model}".contains(query.trim(), ignoreCase = true) }.forEach { device ->
                val check = CheckBox(this).apply {
                    text = device.displayName; isChecked = device.id in working; setTextColor(color(R.color.nexora_text))
                    setOnCheckedChangeListener { _, checked -> if (checked) working.add(device.id) else working.remove(device.id) }
                }
                rows.addView(check)
            }
        }
        renderRows("")
        search.addTextChangedListener(watcher { renderRows(it) })
        AlertDialog.Builder(this)
            .setTitle("Select ${if (isIos) "iOS" else "Android"} devices")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                selected.clear(); selected.addAll(working)
                if (isIos) { iosDeviceModeSpinner.setSelection(1); iosDeviceButton.text = "Choose iOS devices (${selected.size} selected)" }
                else { androidDeviceModeSpinner.setSelection(1); androidDeviceButton.text = "Choose Android devices (${selected.size} selected)" }
                customDeviceCounts.keys.retainAll((selectedAndroidIds + selectedIosIds).toSet())
                updateCustomDistributionDefaults()
                toast("${selected.size} ${if (isIos) "iOS" else "Android"} devices selected")
            }
            .setNeutralButton("Select all") { _, _ ->
                selected.clear(); pool.forEach { selected.add(it.id) }
                if (isIos) { iosDeviceModeSpinner.setSelection(1); iosDeviceButton.text = "Choose iOS devices (${selected.size} selected)" }
                else { androidDeviceModeSpinner.setSelection(1); androidDeviceButton.text = "Choose Android devices (${selected.size} selected)" }
                updateCustomDistributionDefaults()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun configureCustomDistribution() {
        val androidCount = androidCountField.text.toString().toIntOrNull() ?: 0
        val iosCount = iosCountField.text.toString().toIntOrNull() ?: 0
        val androidPool = UserAgentGenerationEngine.eligiblePool(presets, false, if (androidDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM", selectedAndroidIds)
        val iosPool = UserAgentGenerationEngine.eligiblePool(presets, true, if (iosDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM", selectedIosIds)
        val pools = androidPool + iosPool
        if (pools.isEmpty()) { toast("Choose a device pool first"); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val fields = linkedMapOf<String, EditText>()
        pools.forEach { device ->
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "0"
                setText((customDeviceCounts[device.id] ?: 0).toString())
                textSize = 14f
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(device.displayName, 12f, R.color.nexora_text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(field, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(row)
            fields[device.id] = field
        }
        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this).setTitle("Custom device quantities")
            .setMessage("Android quantities must total $androidCount. iOS quantities must total $iosCount.")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val values = fields.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                val a = values.filterKeys { id -> presets.any { it.id == id && !it.isIos } }.values.sum()
                val i = values.filterKeys { id -> presets.any { it.id == id && it.isIos } }.values.sum()
                if (a != androidCount || i != iosCount || values.values.any { it < 0 }) {
                    toast("Quantities must total $androidCount Android and $iosCount iOS. Nothing changed.")
                } else {
                    customDeviceCounts = values.toMutableMap()
                    toast("Custom distribution saved")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun updateCustomDistributionDefaults() {
        val aCount = androidCountField.text.toString().toIntOrNull() ?: 0
        val iCount = iosCountField.text.toString().toIntOrNull() ?: 0
        val aPool = UserAgentGenerationEngine.eligiblePool(presets, false, if (androidDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM", selectedAndroidIds)
        val iPool = UserAgentGenerationEngine.eligiblePool(presets, true, if (iosDeviceModeSpinner.selectedItemPosition == 1) "SELECTED" else "RANDOM", selectedIosIds)
        customDeviceCounts.clear()
        distributeDefaults(aPool, aCount)
        distributeDefaults(iPool, iCount)
    }

    private fun distributeDefaults(pool: List<DevicePreset>, count: Int) {
        if (pool.isEmpty() || count <= 0) return
        pool.forEachIndexed { index, device -> customDeviceCounts[device.id] = count / pool.size + if (index < count % pool.size) 1 else 0 }
    }

    private fun addVersionSpinner(parent: LinearLayout, title: String, isIos: Boolean): Spinner {
        val versions = listOf("Latest supported") + presets.filter { it.isIos == isIos }.flatMap { it.osVersions }.distinct().sortedBy { it.toIntOrNull() ?: 0 }
        return spinner(parent, title, versions)
    }

    private fun versionValue(spinner: Spinner): String = if (spinner.selectedItemPosition == 0) "LATEST" else spinner.selectedItem.toString()

    private fun section(title: String, description: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_surface)); strokeWidth = dp(1); strokeColor = color(R.color.nexora_line)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(15)) }
        content.addView(text(title, 14f, R.color.nexora_text, true))
        content.addView(text(description, 11f, R.color.nexora_muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        card.addView(content)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return content
    }

    private fun field(parent: LinearLayout, title: String, hint: String, value: String, inputType: Int): EditText {
        parent.addView(text(title, 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        return EditText(this).apply {
            this.hint = hint; setText(value); this.inputType = inputType; textSize = 14f
            setTextColor(color(R.color.nexora_text)); setHintTextColor(color(R.color.nexora_muted))
            background = rounded(color(R.color.nexora_raised), color(R.color.nexora_line))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
    }

    private fun spinner(parent: LinearLayout, title: String, values: List<String>): Spinner {
        parent.addView(text(title, 11f, R.color.nexora_muted, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        val spinner = Spinner(this)
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        parent.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return spinner
    }

    private fun addButton(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        parent.addView(button(label, primary, action), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): MaterialButton = MaterialButton(
        this, null, if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label; isAllCaps = false; cornerRadius = dp(13); setOnClickListener { action() }
        if (!primary) { strokeColor = ColorStateList.valueOf(color(R.color.nexora_line)); setTextColor(color(R.color.nexora_text)) }
    }

    private fun addNote(parent: LinearLayout, heading: String, body: String) {
        parent.addView(text(heading, 11f, R.color.nexora_text, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        parent.addView(text(body, 10f, R.color.nexora_muted))
    }

    private fun text(value: String, size: Float, colorId: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color(colorId)); if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat(); setColor(fill); setStroke(dp(1), stroke)
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

    private fun copyToClipboard(label: String, value: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast(message)
    }

    private fun shareText(title: String, text: String, mime: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }, title))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    companion object { private const val STATE_BATCH_ID = "ua_last_batch_id" }
}
