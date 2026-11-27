package com.nexora.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.automation.WorkflowAuditRecord
import com.nexora.browser.automation.WorkflowAuditStore
import com.nexora.browser.automation.WorkflowPlanner
import com.nexora.browser.automation.WorkflowPreview
import com.nexora.browser.automation.WorkflowProfileSnapshot
import com.nexora.browser.automation.WorkflowProxySnapshot
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyEntry
import com.nexora.browser.data.ProxyLibraryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-confirmed local workflows. No website macros or background automation are implied. */
class AutomationCenterActivity : AppCompatActivity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var proxyStore: ProxyLibraryStore
    private lateinit var auditStore: WorkflowAuditStore
    private lateinit var auditText: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(this)
        proxyStore = ProxyLibraryStore(this)
        auditStore = WorkflowAuditStore(this)
        buildUi()
        refreshAuditLog()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val toolbar = Toolbar(this).apply {
            title = "Automation Center"
            setTitleTextColor(color(R.color.nexora_text))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            setBackgroundColor(color(R.color.nexora_surface))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(text(
            "Repeatable profile operations with a preview before every change. Workflows run only when you confirm them; nothing runs in the background.",
            13f, R.color.nexora_muted
        ))
        content.addView(workflowCard(
            "Profile configuration audit",
            "Read-only checks for duplicate names, missing device presets, incomplete proxy endpoints and proxy types unavailable in the current relay.",
            "Preview audit"
        ) { previewProfileAudit() })
        content.addView(workflowCard(
            "Assign saved proxies",
            "Pairs valid HTTP, HTTPS and SOCKS5 library entries one-to-one with profiles using Direct connection. Existing proxy assignments are never overwritten.",
            "Preview assignment"
        ) { previewProxyAssignment() })

        statusText = text("No workflow has run in this session.", 12f, R.color.nexora_muted)
        statusText.setPadding(dp(2), dp(12), dp(2), dp(8))
        content.addView(statusText)

        val logCard = card()
        val logContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        logContent.addView(text("Recent workflow history", 16f, R.color.nexora_text, true))
        auditText = text("No workflow history yet.", 12f, R.color.nexora_muted)
        auditText.setPadding(0, dp(10), 0, 0)
        logContent.addView(auditText)
        logCard.addView(logContent)
        content.addView(logCard, marginTop(14))

        setContentView(root)
        UiFeedback.install(root)
    }

    private fun previewProfileAudit() {
        val profiles = readLocalStore { profileStore.loadAll() }.getOrElse { return }
        val preview = WorkflowPlanner.auditProfiles(profiles.map(::snapshot))
        val findings = preview.findings.take(15).joinToString("\n")
        val extra = if (preview.findings.size > 15) "\n…and ${preview.findings.size - 15} more finding(s)." else ""
        val message = buildString {
            append(preview.summary)
            if (findings.isNotBlank()) append("\n\n").append(findings).append(extra)
            append("\n\nThis audit reads local profile settings only. It does not test proxy connectivity or fingerprint resistance.")
        }
        AlertDialog.Builder(this)
            .setTitle("Preview: profile audit")
            .setMessage(message)
            .setPositiveButton("Run audit") { _, _ ->
                val record = WorkflowAuditRecord(
                    timestamp = System.currentTimeMillis(),
                    workflowId = preview.workflowId,
                    status = "COMPLETED",
                    affectedCount = 0,
                    summary = "${preview.totalProfiles} profile(s) checked; ${preview.findings.size} warning(s)."
                )
                val saved = auditStore.append(record)
                statusText.text = if (saved) record.summary else "Audit completed, but the audit log could not be saved."
                refreshAuditLog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewProxyAssignment() {
        val profiles = readLocalStore { profileStore.loadAll() }.getOrElse { return }
        val proxies = proxyStore.loadAll()
        val preview = WorkflowPlanner.assignUnassignedProxies(
            profiles.map(::snapshot), proxies.map(::snapshot)
        )
        val profileNames = profiles.associate { it.id to it.name }
        val proxyNames = proxies.associate { it.id to "${it.name} (${it.type} ${it.host}:${it.port})" }
        val lines = preview.assignments.take(20).joinToString("\n") { assignment ->
            "${profileNames[assignment.profileId] ?: assignment.profileId}  ←  ${proxyNames[assignment.proxyId] ?: assignment.proxyId}"
        }
        val overflow = if (preview.assignments.size > 20) "\n…and ${preview.assignments.size - 20} more assignment(s)." else ""
        val message = buildString {
            append(preview.summary)
            if (lines.isNotBlank()) append("\n\n").append(lines).append(overflow)
            append("\n\nNo connection test is performed. Assignment changes profile configuration only; it does not prove that browsing traffic is routed successfully.")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Preview: proxy assignment")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
        if (preview.assignments.isNotEmpty()) {
            builder.setPositiveButton("Assign proxies") { _, _ -> applyProxyAssignment(preview) }
        } else {
            builder.setPositiveButton("Close", null)
        }
        builder.show()
    }

    private fun applyProxyAssignment(previousPreview: WorkflowPreview) {
        val profiles = readLocalStore { profileStore.loadAll() }.getOrElse { return }
        val proxies = proxyStore.loadAll()
        val currentPreview = WorkflowPlanner.assignUnassignedProxies(
            profiles.map(::snapshot), proxies.map(::snapshot)
        )
        if (currentPreview.assignments != previousPreview.assignments) {
            Toast.makeText(this, "Profiles or proxies changed. Please preview the workflow again.", Toast.LENGTH_LONG).show()
            return
        }

        val profileById = profiles.associateBy { it.id }
        val proxyById = proxies.associateBy { it.id }
        val updates = mutableMapOf<String, BrowserProfile>()
        for (assignment in currentPreview.assignments) {
            val profile = profileById[assignment.profileId] ?: return
            val proxy = proxyById[assignment.proxyId] ?: return
            val credentials = proxyStore.credentials(proxy)
            if (proxy.hasCredentials && credentials.first.isEmpty() && credentials.second.isEmpty()) {
                auditStore.append(
                    WorkflowAuditRecord(
                        System.currentTimeMillis(), currentPreview.workflowId, "FAILED", 0,
                        "Saved credentials for ${proxy.name} could not be decrypted; no profiles were changed."
                    )
                )
                Toast.makeText(this, "A saved proxy credential could not be read. No changes were made.", Toast.LENGTH_LONG).show()
                refreshAuditLog()
                return
            }
            updates[profile.id] = profile.copy(
                proxyType = proxy.type,
                proxyHost = proxy.host,
                proxyPort = proxy.port,
                proxyUser = credentials.first,
                proxyPass = credentials.second,
                proxyEntryId = proxy.id
            )
        }
        val result = profiles.map { updates[it.id] ?: it }
        val saved = profileStore.saveAllChecked(result)
        val summary = if (saved) {
            "Assigned ${updates.size} saved proxy/proxy pair(s). No network test was run."
        } else {
            "Assignment failed while saving profiles. Existing profile data was retained."
        }
        auditStore.append(
            WorkflowAuditRecord(
                timestamp = System.currentTimeMillis(),
                workflowId = currentPreview.workflowId,
                status = if (saved) "COMPLETED" else "FAILED",
                affectedCount = if (saved) updates.size else 0,
                summary = summary
            )
        )
        statusText.text = summary
        refreshAuditLog()
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }

    private fun refreshAuditLog() {
        val rows = auditStore.load().take(10)
        auditText.text = if (rows.isEmpty()) {
            "No workflow history yet."
        } else {
            rows.joinToString("\n\n") { row ->
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(row.timestamp))
                "$date · ${row.workflowId.replace('-', ' ')} · ${row.status}\n${row.summary}"
            }
        }
    }

    private fun snapshot(profile: BrowserProfile) = WorkflowProfileSnapshot(
        id = profile.id,
        name = profile.name,
        proxyType = profile.proxyType,
        proxyHost = profile.proxyHost,
        proxyPort = profile.proxyPort,
        proxyEntryId = profile.proxyEntryId,
        deviceId = profile.deviceId
    )

    private fun snapshot(proxy: ProxyEntry) = WorkflowProxySnapshot(
        id = proxy.id,
        name = proxy.name,
        type = proxy.type,
        host = proxy.host,
        port = proxy.port,
        createdAt = proxy.createdAt
    )

    private fun workflowCard(title: String, description: String, action: String, onClick: () -> Unit): MaterialCardView {
        val card = card()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
        }
        content.addView(text(title, 16f, R.color.nexora_text, true))
        val detail = text(description, 12f, R.color.nexora_muted)
        detail.setPadding(0, dp(7), 0, dp(10))
        content.addView(detail)
        val button = MaterialButton(this).apply {
            text = action
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        content.addView(button, LinearLayout.LayoutParams(-1, dp(46)))
        card.addView(content)
        return card.apply { layoutParams = marginTop(12) }
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(R.color.nexora_surface))
        strokeWidth = dp(1)
        setStrokeColor(color(R.color.nexora_line))
    }

    private fun text(value: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun color(resource: Int) = ContextCompat.getColor(this, resource)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun marginTop(value: Int) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(value)
    }
}
