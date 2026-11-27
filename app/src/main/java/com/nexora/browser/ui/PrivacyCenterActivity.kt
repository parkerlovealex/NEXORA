package com.nexora.browser.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.data.BrowserDataStore
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.security.PrivacyPolicy
import com.nexora.browser.util.ProfileIsolationGuard

/** Profile-scoped dashboard for the security defaults actually applied by BrowserActivity. */
class PrivacyCenterActivity : AppCompatActivity() {

    private lateinit var profile: BrowserProfile
    private lateinit var dataStore: BrowserDataStore
    private lateinit var clearDataButton: MaterialButton
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val store = ProfileStore(this)
        val profiles = readLocalStore { store.loadAll() }.getOrElse { finish(); return }
        val loaded = profiles.firstOrNull { it.id == profileId }
        if (loaded == null) {
            Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = loaded
        val collisions = ProfileIsolationGuard.collidingProfiles(profile, profiles)
        if (collisions.size > 1) {
            setContentView(messagePage(
                "Profile storage collision",
                "NEXORA cannot safely inspect or clear this profile because its WebView storage key is shared with another profile. Recreate one of the colliding profiles before browsing."
            ))
            return
        }
        dataStore = BrowserDataStore(this, profile.id)
        setContentView(buildPage())
        UiFeedback.install(window.decorView)
    }

    private fun buildPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nexora_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(color(R.color.nexora_header))
        }
        header.addView(label("‹", 34f, R.color.nexora_accent, false).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, params(dp(42), dp(44)))
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(label("PROFILE SECURITY", 9f, R.color.nexora_accent, true))
        titleStack.addView(label("Privacy Center", 20f, R.color.nexora_text, true))
        header.addView(titleStack, params(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, params(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hero = card()
        val heroBody = vertical(dp(18))
        heroBody.addView(label("PROFILE SECURITY OVERVIEW", 10f, R.color.nexora_accent, true))
        heroBody.addView(label(profile.name, 24f, R.color.nexora_text, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        heroBody.addView(label(
            "This page reports NEXORA's local WebView configuration. It does not claim that the device has passed live IP, DNS, WebRTC or fingerprint leak tests.",
            13f, R.color.nexora_muted, false
        ))
        hero.addView(heroBody)
        body.addView(hero, marginBottom(14))

        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)
        val documentStart = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)

        val engine = card()
        val engineBody = vertical(dp(16))
        engineBody.addView(label("ENGINE & PROFILE", 10f, R.color.nexora_accent, true))
        engineBody.addView(label(
            "Provider: ${provider?.packageName ?: "Unavailable"}\nVersion: ${provider?.versionName ?: "Unknown"}\nAndroid: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\nStorage key: ${profile.webViewProfileKey}\nMulti-profile API: ${if (multiProfile) "Available" else "Unavailable"}",
            12f, R.color.nexora_text, false
        ).apply { setPadding(0, dp(10), 0, 0) })
        engine.addView(engineBody)
        body.addView(engine, marginBottom(14))

        body.addView(label("ACTIVE SECURITY DEFAULTS", 10f, R.color.nexora_muted, true).apply {
            letterSpacing = 0.1f
            setPadding(dp(2), 0, 0, dp(9))
        })
        PrivacyPolicy.controls(multiProfile, documentStart).forEach { control ->
            body.addView(controlCard(control.title, control.status, control.detail), marginBottom(9))
        }

        val network = card()
        val networkBody = vertical(dp(16))
        networkBody.addView(label("NETWORK VERIFICATION", 10f, R.color.nexora_accent, true))
        networkBody.addView(label(
            "NOT RUN\n\nNo external diagnostic endpoint is contacted from this dashboard. Proxy assignment, JavaScript WebRTC restrictions and locale overrides do not prove that all traffic is routed safely. Verify the actual device and provider separately before relying on them.",
            13f, R.color.nexora_text, false
        ).apply { setPadding(0, dp(9), 0, 0) })
        network.addView(networkBody)
        body.addView(network, marginBottom(14))

        val cleanup = card()
        val cleanupBody = vertical(dp(16))
        cleanupBody.addView(label("PROFILE DATA", 10f, R.color.nexora_accent, true))
        cleanupBody.addView(label(
            "Clear this profile's cookies, website storage, browsing history, recently closed tabs and saved open tabs. Bookmarks and downloaded files are kept. The provider-managed WebView resource cache is not cleared by this profile-only action.",
            13f, R.color.nexora_muted, false
        ).apply { setPadding(0, dp(8), 0, dp(12)) })
        statusLabel = label("No cleanup has been requested.", 11f, R.color.nexora_muted, false)
        cleanupBody.addView(statusLabel, marginBottom(8))
        clearDataButton = MaterialButton(this).apply {
            text = "Clear profile browsing data"
            isAllCaps = false
            cornerRadius = dp(12)
            minimumHeight = dp(48)
            setOnClickListener { confirmClearProfileData() }
        }
        cleanupBody.addView(clearDataButton, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        cleanup.addView(cleanupBody)
        body.addView(cleanup, marginBottom(14))

        val compatibilityButton = MaterialButton(this).apply {
            text = "Open Compatibility Lab"
            isAllCaps = false
            cornerRadius = dp(12)
            minimumHeight = dp(50)
            setOnClickListener {
                startActivity(
                    Intent(this@PrivacyCenterActivity, CompatibilityLabActivity::class.java)
                        .putExtra(CompatibilityLabActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
        }
        body.addView(compatibilityButton, marginBottom(10))
        body.addView(label(
            "Compatibility Lab performs local WebView checks. It is not a live network leak-test service.",
            11f, R.color.nexora_muted, false
        ).apply { setPadding(dp(4), 0, dp(4), 0) })
        return root
    }

    private fun controlCard(title: String, status: String, detail: String): View {
        val outer = card()
        val content = vertical(dp(14))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title, 14f, R.color.nexora_text, true), params(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(status, 9f, statusColor(status), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.nexora_raised))
                cornerRadius = dp(18).toFloat()
            }
        })
        content.addView(row)
        content.addView(label(detail, 12f, R.color.nexora_muted, false).apply {
            setPadding(0, dp(8), 0, 0)
        })
        outer.addView(content)
        return outer
    }

    private fun confirmClearProfileData() {
        AlertDialog.Builder(this)
            .setTitle("Clear profile browsing data?")
            .setMessage(
                "This removes cookies, website storage, browsing history, recently closed tabs and saved tabs for ${profile.name}. Bookmarks, downloaded files and the provider-managed WebView resource cache will remain. This cannot be undone."
            )
            .setPositiveButton("Clear data") { _, _ -> clearProfileData() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearProfileData() {
        clearDataButton.isEnabled = false
        clearDataButton.text = "Clearing…"
        statusLabel.text = "Requesting profile cookie and storage cleanup…"

        val webProfile = runCatching {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profile.webViewProfileKey)
        }.getOrNull()
        if (webProfile == null) {
            clearDataButton.isEnabled = true
            clearDataButton.text = "Clear profile browsing data"
            statusLabel.text = "The named WebView profile could not be opened. No cleanup was claimed."
            return
        }

        val cookies = webProfile.cookieManager
        cookies.removeAllCookies { removedAny ->
            runOnUiThread {
                try {
                    cookies.flush()
                    webProfile.webStorage.deleteAllData()
                    dataStore.clearHistory()
                    dataStore.clearRecentlyClosed()
                    dataStore.clearSession()
                    dataStore.markPrivacyResetPending()
                    statusLabel.text = if (removedAny) {
                        "Cleanup completed. Cookies were removed; website storage, history, recently closed tabs and saved tabs were cleared."
                    } else {
                        "Cleanup completed. No cookies were reported as removed; website storage, history, recently closed tabs and saved tabs were cleared."
                    }
                    Toast.makeText(this, "Profile browsing data cleared", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    statusLabel.text = "Cleanup was incomplete: ${error.message ?: error.javaClass.simpleName}. Review this profile before relying on the result."
                } finally {
                    clearDataButton.isEnabled = true
                    clearDataButton.text = "Clear profile browsing data"
                }
            }
        }
    }

    private fun messagePage(title: String, message: String): View {
        val root = vertical(dp(20)).apply {
            setBackgroundColor(color(R.color.nexora_bg))
        }
        root.addView(label(title, 22f, R.color.nexora_text, true))
        root.addView(label(message, 14f, R.color.nexora_muted, false).apply {
            setPadding(0, dp(10), 0, dp(16))
        })
        root.addView(MaterialButton(this).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        return root
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(R.color.nexora_surface))
        strokeWidth = dp(1)
        setStrokeColor(color(R.color.nexora_line))
    }

    private fun vertical(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }

    private fun label(value: String, size: Float, textColor: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color(textColor))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun statusColor(status: String): Int = when (status) {
        "ON", "BLOCKED", "DISABLED", "DENY BY DEFAULT", "GESTURE REQUIRED", "API AVAILABLE" -> R.color.nexora_status_green
        else -> R.color.nexora_accent
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun params(width: Int, height: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)
    private fun marginBottom(value: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(value)
        }

    companion object {
        const val EXTRA_PROFILE_ID = "com.nexora.browser.extra.PRIVACY_PROFILE_ID"
    }
}
