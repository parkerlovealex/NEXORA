package com.nexora.browser.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.backup.PortableBackupCrypto
import com.nexora.browser.backup.ProfileBackupManager
import com.nexora.browser.backup.ProfileBackupPreview
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyLibraryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local password-encrypted backup/restore for profile records and saved proxy entries. */
class BackupRestoreActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private var pendingBackupPassword: CharArray? = null
    private var pendingRestoreBytes: ByteArray? = null
    private var pendingRestorePassword: CharArray? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingBackupPassword
        pendingBackupPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
            updateStatus("Backup cancelled. No file was created.")
            return@registerForActivityResult
        }
        writeBackup(uri, password)
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            updateStatus("Restore cancelled. No data was changed.")
        } else {
            readBackup(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        pendingBackupPassword?.fill('\u0000')
        pendingRestorePassword?.fill('\u0000')
        pendingRestoreBytes?.fill(0)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val toolbar = Toolbar(this).apply {
            title = "Backup & Restore"
            setTitleTextColor(color(R.color.nexora_text))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            setBackgroundColor(color(R.color.nexora_surface))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(text(
            "Create a portable backup protected by a password you choose. Profile proxy credentials and saved proxy-library credentials are included only inside the encrypted backup.",
            13f, R.color.nexora_muted
        ))
        content.addView(actionCard(
            "Create encrypted backup",
            "Exports profile configuration and the saved proxy library. Your backup password is required to restore it on this or another installation.",
            "Create backup"
        ) { askBackupPassword() })
        content.addView(actionCard(
            "Restore encrypted backup",
            "Restoration is additive. Existing profile IDs are skipped rather than overwritten. Proxy credentials are re-encrypted with this installation's Android Keystore key.",
            "Choose backup file"
        ) { openDocument.launch(arrayOf("application/octet-stream", "application/x-nexora-backup", "*/*")) })

        val warning = card()
        val warningContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        warningContent.addView(text("What is not included", 15f, R.color.nexora_text, true))
        warningContent.addView(text(
            "Website cookies, saved website logins, browsing history, bookmarks, open tabs, download records, downloaded files, WebView cache and device-bound app settings are not included. This is a profile-and-proxy configuration backup, not a full device backup.",
            12f, R.color.nexora_muted
        ).apply { setPadding(0, dp(8), 0, 0) })
        warningContent.addView(text(
            "Keep the password safe. NEXORA cannot recover a backup if the password is lost.",
            12f, R.color.nexora_accent, true
        ).apply { setPadding(0, dp(10), 0, 0) })
        warning.addView(warningContent)
        content.addView(warning, marginTop(14))

        statusText = text("Ready. No backup operation has been started.", 12f, R.color.nexora_muted)
        statusText.setPadding(dp(2), dp(14), dp(2), 0)
        content.addView(statusText)
        setContentView(root)
        UiFeedback.install(root)
    }

    private fun askBackupPassword() {
        val password = passwordField("Create a backup password")
        val confirm = passwordField("Confirm password").apply {
            setPadding(paddingLeft, dp(12), paddingRight, paddingBottom)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(password)
            addView(confirm)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Protect your backup")
            .setMessage("Use at least ${PortableBackupCrypto.MIN_PASSWORD_LENGTH} characters. This password encrypts proxy credentials and profile proxy settings.")
            .setView(form)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = password.text?.toString().orEmpty()
                val second = confirm.text?.toString().orEmpty()
                when {
                    first.length < PortableBackupCrypto.MIN_PASSWORD_LENGTH -> password.error = "Use at least ${PortableBackupCrypto.MIN_PASSWORD_LENGTH} characters"
                    first != second -> confirm.error = "Passwords do not match"
                    else -> {
                        pendingBackupPassword = first.toCharArray()
                        dialog.dismiss()
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        createDocument.launch("NEXORA-backup-$stamp.nxb")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun writeBackup(uri: Uri, password: CharArray) {
        updateStatus("Encrypting profile and proxy data…")
        Thread({
            try {
                val bytes = ProfileBackupManager.createBackup(
                    ProfileStore(applicationContext), ProxyLibraryStore(applicationContext), password
                )
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { output -> output.write(bytes) }
                        ?: throw IllegalStateException("Could not open the selected backup destination.")
                    runOnUiThread { updateStatus("Encrypted backup saved successfully.") }
                } finally {
                    bytes.fill(0)
                }
            } catch (error: Exception) {
                runOnUiThread { updateStatus("Backup failed: ${error.message ?: "unknown error"}") }
            } finally {
                password.fill('\u0000')
            }
        }, "nexora-backup-export").start()
    }

    private fun readBackup(uri: Uri) {
        updateStatus("Reading selected backup…")
        Thread({
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= PortableBackupCrypto.MAX_BACKUP_BYTES + 128) {
                            "Backup file exceeds the 20 MB size limit."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: throw IllegalStateException("Could not read the selected file.")
                runOnUiThread {
                    pendingRestoreBytes?.fill(0)
                    pendingRestoreBytes = bytes
                    askRestorePassword()
                }
            } catch (error: Exception) {
                runOnUiThread { updateStatus("Could not read backup: ${error.message ?: "unknown error"}") }
            }
        }, "nexora-backup-read").start()
    }

    private fun askRestorePassword() {
        val bytes = pendingRestoreBytes ?: return
        val password = passwordField("Backup password")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unlock backup")
            .setMessage("Enter the password used when this backup was created.")
            .setView(password)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel") { _, _ -> clearPendingRestore() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = password.text?.toString().orEmpty()
                if (value.length < PortableBackupCrypto.MIN_PASSWORD_LENGTH) {
                    password.error = "Enter the backup password"
                    return@setOnClickListener
                }
                val chars = value.toCharArray()
                dialog.dismiss()
                inspectAndConfirmRestore(bytes, chars)
            }
        }
        dialog.setOnCancelListener { clearPendingRestore() }
        dialog.show()
    }

    private fun inspectAndConfirmRestore(bytes: ByteArray, password: CharArray) {
        pendingRestorePassword?.fill('\u0000')
        pendingRestorePassword = password
        updateStatus("Checking backup integrity…")
        Thread({
            try {
                val preview = ProfileBackupManager.inspect(bytes, password)
                runOnUiThread { showRestorePreview(preview, bytes, password) }
            } catch (error: Exception) {
                password.fill('\u0000')
                runOnUiThread {
                    clearPendingRestore()
                    updateStatus("Backup could not be unlocked: ${error.message ?: "unknown error"}")
                }
            }
        }, "nexora-backup-inspect").start()
    }

    private fun showRestorePreview(preview: ProfileBackupPreview, bytes: ByteArray, password: CharArray) {
        pendingRestorePassword = password
        val created = if (preview.createdAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.createdAt))
        } else "Unknown"
        AlertDialog.Builder(this)
            .setTitle("Confirm backup restore")
            .setMessage(
                "Backup created: $created\n\n" +
                    "Profiles: ${preview.profileCount}\n" +
                    "Profiles with proxy settings: ${preview.proxiedProfileCount}\n" +
                    "Saved proxies: ${preview.proxyCount}\n\n" +
                    "Restore will add new profile IDs and merge proxy entries. Existing profile IDs will be skipped, not overwritten. A profile ID may be regenerated if its short WebView storage key would collide with one already on this device. Continue?"
            )
            .setPositiveButton("Restore") { _, _ -> performRestore(bytes, password) }
            .setNegativeButton("Cancel") { _, _ ->
                password.fill('\u0000')
                clearPendingRestore()
                updateStatus("Restore cancelled. No data was changed.")
            }
            .setOnCancelListener {
                password.fill('\u0000')
                clearPendingRestore()
                updateStatus("Restore cancelled. No data was changed.")
            }
            .show()
    }

    private fun performRestore(bytes: ByteArray, password: CharArray) {
        updateStatus("Restoring profiles and re-encrypting proxy credentials…")
        Thread({
            try {
                val result = ProfileBackupManager.restore(
                    bytes,
                    password,
                    ProfileStore(applicationContext),
                    ProxyLibraryStore(applicationContext)
                )
                runOnUiThread {
                    updateStatus(
                        "Restore complete. ${result.profilesAdded} profile(s) added, " +
                            "${result.profilesSkipped} existing profile(s) skipped, " +
                            "${result.profileIdsRemapped} profile ID(s) changed to avoid WebView storage-key collisions, " +
                            "${result.proxiesAdded} new proxy entr(y/ies) added."
                    )
                    Toast.makeText(this, "Backup restored", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread { updateStatus("Restore failed: ${error.message ?: "unknown error"}") }
            } finally {
                password.fill('\u0000')
                bytes.fill(0)
                runOnUiThread { clearPendingRestore() }
            }
        }, "nexora-backup-restore").start()
    }

    private fun clearPendingRestore() {
        pendingRestoreBytes?.fill(0)
        pendingRestoreBytes = null
        pendingRestorePassword?.fill('\u0000')
        pendingRestorePassword = null
    }

    private fun passwordField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
        setTextColor(color(R.color.nexora_text))
        setHintTextColor(color(R.color.nexora_muted))
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }

    private fun actionCard(title: String, description: String, action: String, onClick: () -> Unit): MaterialCardView {
        val card = card()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
        }
        content.addView(text(title, 16f, R.color.nexora_text, true))
        content.addView(text(description, 12f, R.color.nexora_muted).apply {
            setPadding(0, dp(7), 0, dp(10))
        })
        content.addView(MaterialButton(this).apply {
            text = action
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        card.addView(content)
        card.layoutParams = marginTop(12)
        return card
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

    private fun updateStatus(value: String) {
        if (::statusText.isInitialized) statusText.text = value
    }

    private fun color(resource: Int) = ContextCompat.getColor(this, resource)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun marginTop(value: Int) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(value)
    }
}
