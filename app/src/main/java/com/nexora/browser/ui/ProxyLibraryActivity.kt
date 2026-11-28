package com.nexora.browser.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyEntry
import com.nexora.browser.data.ProxyLibraryStore
import com.nexora.browser.util.ProxyImporter
import com.nexora.browser.util.ProxyTester

/** Dedicated local proxy-library manager. Credentials are decrypted only in memory for an explicit test. */
class ProxyLibraryActivity : AppCompatActivity() {

    private lateinit var proxyStore: ProxyLibraryStore
    private lateinit var profileStore: ProfileStore
    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxyStore = ProxyLibraryStore(this)
        profileStore = ProfileStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Proxy Manager"
            setTitleTextColor(color(R.color.nexora_text))
            setBackgroundColor(color(R.color.nexora_header))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "SAVED CONNECTIONS"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(color(R.color.nexora_accent))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Manage proxy endpoints"
            textSize = 22f
            setTextColor(color(R.color.nexora_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        header.addView(TextView(this).apply {
            text = "Import, test, rename and organize proxies. Credentials stay encrypted in Android Keystore-backed local storage."
            textSize = 12f
            setTextColor(color(R.color.nexora_muted))
        })
        header.addView(TextView(this).apply {
            text = "Important: Saved does not mean connected. Tap Test to verify authentication and the proxy exit IP before assigning it to a profile."
            textSize = 11f
            setTextColor(color(R.color.nexora_accent))
            setPadding(0, dp(8), 0, 0)
        })
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        countLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.nexora_muted))
        }
        actionRow.addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(actionButton("Import proxies") { showImportDialog() })
        header.addView(actionRow)
        header.addView(actionButton("Remove unused proxies") { confirmRemoveUnused() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
        root.addView(header)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        UiFeedback.install(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) refreshList()
    }

    private fun refreshList() {
        val entries = proxyStore.loadAll()
        countLabel.text = "${entries.size} / 500 saved"
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            val empty = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(color(R.color.nexora_surface))
                strokeWidth = dp(1)
                strokeColor = color(R.color.nexora_line)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(28), dp(22), dp(28))
            }
            body.addView(TextView(this).apply {
                text = "No saved proxies"
                textSize = 16f
                setTextColor(color(R.color.nexora_text))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            body.addView(TextView(this).apply {
                text = "Import a proxy list to build your local library."
                textSize = 12f
                setTextColor(color(R.color.nexora_muted))
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(12))
            })
            body.addView(actionButton("Import proxies") { showImportDialog() })
            empty.addView(body)
            listContainer.addView(empty)
            UiFeedback.install(listContainer)
            return
        }
        entries.forEach { listContainer.addView(proxyCard(it)) }
        UiFeedback.install(listContainer)
    }

    private fun proxyCard(entry: ProxyEntry): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.nexora_surface))
            strokeWidth = dp(1)
            strokeColor = color(R.color.nexora_line)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = entry.name
            textSize = 15f
            setTextColor(color(R.color.nexora_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(TextView(this).apply {
            text = entry.type
            textSize = 10f
            setTextColor(color(R.color.nexora_accent))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedFill(color(R.color.nexora_raised), 8)
        })
        body.addView(heading)
        body.addView(TextView(this).apply {
            text = "${entry.host}:${entry.port}"
            textSize = 13f
            setTextColor(color(R.color.nexora_text))
            setPadding(0, dp(6), 0, dp(2))
        })
        val status = when {
            entry.isSupportedByProxyRelay -> "Saved · connection not yet verified"
            entry.hasCredentials -> "Credentials encrypted · cannot be assigned to this WebView"
            else -> "Imported · ${entry.type} is not supported for browsing by this WebView"
        }
        body.addView(TextView(this).apply {
            text = status
            textSize = 11f
            setTextColor(color(R.color.nexora_muted))
        })
        if (entry.hasCredentials) {
            body.addView(TextView(this).apply {
                text = "Username and password are never displayed. They are decrypted only in memory when you explicitly test this proxy."
                textSize = 10f
                setTextColor(color(R.color.nexora_muted))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val result = TextView(this).apply {
            textSize = 11f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(8), 0, 0)
        }
        body.addView(result)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val test = actionButton("Test") {}
        test.setOnClickListener {
            if (entry.type !in setOf("HTTP", "HTTPS", "SOCKS5")) {
                result.text = "Connectivity testing for ${entry.type} is not available in this build."
                return@setOnClickListener
            }
            test.isEnabled = false
            result.text = "Testing proxy connection…"
            val credentials = proxyStore.credentials(entry)
            if (entry.hasCredentials && credentials.first.isEmpty() && credentials.second.isEmpty()) {
                result.text = "Could not decrypt the saved credentials. Check Android Keystore availability."
                test.isEnabled = true
                return@setOnClickListener
            }
            val temp = BrowserProfile(
                id = "proxy-library-test",
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
            ProxyTester.test(temp) { message ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        result.text = message
                        test.isEnabled = true
                    }
                }
            }
        }
        val rename = actionButton("Rename") { showRenameDialog(entry) }
        val delete = actionButton("Delete") { confirmDelete(entry) }
        actions.addView(test, weightedButtonParams())
        actions.addView(rename, weightedButtonParams())
        actions.addView(delete, weightedButtonParams())
        body.addView(actions)
        card.addView(body)
        return card.apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(10)
            layoutParams = params
        }
    }

    private fun showImportDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val typeSpinner = Spinner(this)
        val types = listOf("HTTP", "SOCKS5", "SOCKS4", "HTTPS")
        typeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, types
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        container.addView(label("Default protocol for lines without a scheme · HTTP is the common provider default"))
        container.addView(typeSpinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))
        val input = EditText(this).apply {
            hint = "host:port\nhost:port:username:password\nsocks5://user:pass@host:port"
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(color(R.color.nexora_text))
            setHintTextColor(color(R.color.nexora_muted))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@ProxyLibraryActivity, R.drawable.omnibox_bg)
        }
        container.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
        ).apply { topMargin = dp(10) })
        val preview = TextView(this).apply {
            textSize = 11f
            setTextColor(color(R.color.nexora_muted))
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(preview)

        fun updatePreview() {
            val rows = ProxyImporter.parse(input.text.toString(), typeSpinner.selectedItem.toString())
            val valid = rows.count { it.valid }
            preview.text = "$valid valid · ${rows.size - valid} invalid\n" +
                rows.take(5).joinToString("\n") {
                    "Line ${it.lineNumber}: ${it.type} ${it.host}:${it.port} " +
                        "${if (it.hasCredentials) "· credentials" else ""} " +
                        if (it.valid) "✓" else "— ${it.error}"
                }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) {}
        })
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        updatePreview()

        AlertDialog.Builder(this)
            .setTitle("Import proxies")
            .setView(container)
            .setPositiveButton("Import valid") { _, _ ->
                val rows = ProxyImporter.parse(input.text.toString(), typeSpinner.selectedItem.toString())
                val valid = rows.filter { it.valid }
                if (valid.isEmpty()) {
                    toast("No valid proxies found")
                } else {
                    val result = proxyStore.import(valid)
                    refreshList()
                    val skipped = result.skippedCapacity + result.skippedEncryption
                    if (result.added == 0 && result.duplicates == 0 && skipped == 0) {
                        toast("No proxies imported. The library may be full or local encryption/storage failed.")
                    } else {
                        val skippedText = if (skipped > 0) " · $skipped skipped (library full or encryption failed)" else ""
                        toast("Imported ${result.added} proxies · ${result.duplicates} duplicates$skippedText")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(entry: ProxyEntry) {
        val input = EditText(this).apply {
            setText(entry.name)
            filters = arrayOf(InputFilter.LengthFilter(60))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename proxy")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (proxyStore.rename(entry.id, input.text.toString())) {
                    refreshList()
                    toast("Proxy name updated")
                } else {
                    toast("Enter a name between 1 and 60 characters")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveUnused() {
        val profiles = profilesForReferenceCheck()
        if (profiles == null) {
            toast("Could not verify profile assignments. No proxies were removed.")
            return
        }
        val assignedIds = profiles.map { it.proxyEntryId }.filter { it.isNotBlank() }.toSet()
        val unused = proxyStore.loadAll().filterNot { it.id in assignedIds }
        if (unused.isEmpty()) {
            toast("Every saved proxy is assigned to a profile")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove unused proxies?")
            .setMessage("${unused.size} unassigned proxy entr${if (unused.size == 1) "y" else "ies"} will be removed. Proxies referenced by profiles are preserved.")
            .setPositiveButton("Remove ${unused.size}") { _, _ ->
                if (proxyStore.deleteMany(unused.map { it.id }.toSet())) {
                    refreshList()
                    toast("Removed ${unused.size} unused proxies")
                } else {
                    toast("Could not remove unused proxies")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entry: ProxyEntry) {
        val profiles = profilesForReferenceCheck()
        if (profiles == null) {
            toast("Could not verify profile assignments. Proxy was not deleted.")
            return
        }
        val assigned = profiles.count { it.proxyEntryId == entry.id }
        if (assigned > 0) {
            AlertDialog.Builder(this)
                .setTitle("Proxy is assigned")
                .setMessage("This proxy is assigned to $assigned profile(s). Reassign those profiles before deleting it. This prevents a saved profile from silently connecting without its selected proxy.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete saved proxy?")
            .setMessage("${entry.type} ${entry.host}:${entry.port} will be removed from the local proxy library.")
            .setPositiveButton("Delete") { _, _ ->
                if (proxyStore.delete(entry.id)) {
                    refreshList()
                    toast("Proxy deleted")
                } else {
                    toast("Could not delete proxy")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun profilesForReferenceCheck(): List<BrowserProfile>? =
        readLocalStore { profileStore.loadAll() }.getOrNull()

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        setTextColor(color(R.color.nexora_muted))
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun actionButton(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 11f
            isAllCaps = false
            minHeight = dp(40)
            minimumHeight = dp(40)
            minWidth = dp(0)
            minimumWidth = dp(0)
            setPadding(dp(8), 0, dp(8), 0)
            cornerRadius = dp(10)
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.nexora_line))
            setTextColor(color(R.color.nexora_text))
            setOnClickListener { action() }
        }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0, dp(40), 1f
    ).apply { marginEnd = dp(5) }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun roundedFill(fillColor: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
        }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
