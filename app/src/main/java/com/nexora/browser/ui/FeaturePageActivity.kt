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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.features.FeatureAction
import com.nexora.browser.features.FeatureCatalog
import com.nexora.browser.features.FeatureModule
import com.nexora.browser.features.FeatureStatus

class FeaturePageActivity : AppCompatActivity() {

    private lateinit var module: FeatureModule
    private val preferredProfileId: String
        get() = intent.getStringExtra(FeatureCenterActivity.EXTRA_PROFILE_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        module = FeatureCatalog.find(intent.getStringExtra(EXTRA_FEATURE_ID)) ?: run {
            Toast.makeText(this, "Feature page not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
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
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(color(R.color.nexora_accent))
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        header.addView(back, LinearParams(dp(42), dp(44)))
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(label("CONTROL MODULE", 9f, R.color.nexora_accent, true))
        titleStack.addView(label(module.title, 19f, R.color.nexora_text, true))
        header.addView(titleStack, LinearParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hero = card()
        val heroBody = vertical(dp(18))
        heroBody.addView(statusPill(module.status))
        heroBody.addView(label(module.category, 10f, R.color.nexora_muted, true).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        heroBody.addView(label(module.title, 27f, R.color.nexora_text, true))
        heroBody.addView(label(module.summary, 14f, R.color.nexora_muted, false).apply {
            setPadding(0, dp(8), 0, 0)
        })
        hero.addView(heroBody)
        body.addView(hero, marginBottom(14))

        body.addView(section("WORKING IN THIS BUILD", module.workingNow, emptyMessage =
            "No active implementation is included in this build. This module is roadmap-only."
        ), marginBottom(12))
        body.addView(section("LIMITATIONS & HONEST STATUS", module.limitations, emptyMessage =
            "No additional limitations were recorded."
        ), marginBottom(12))

        val next = card()
        val nextBody = vertical(dp(16))
        nextBody.addView(label("NEXT ENGINEERING STEP", 10f, R.color.nexora_accent, true))
        nextBody.addView(label(module.nextStep, 13f, R.color.nexora_text, false).apply {
            setPadding(0, dp(7), 0, 0)
        })
        next.addView(nextBody)
        body.addView(next, marginBottom(14))

        val actionButton = MaterialButton(this).apply {
            isAllCaps = false
            textSize = 14f
            cornerRadius = dp(12)
            minimumHeight = dp(50)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (module.status == FeatureStatus.ROADMAP || module.action == FeatureAction.NONE) {
            actionButton.text = "Not available in this build"
            actionButton.isEnabled = false
            actionButton.alpha = 0.55f
        } else {
            actionButton.text = module.actionLabel.ifBlank { "Open feature" }
            actionButton.setOnClickListener { openModuleAction() }
        }
        body.addView(actionButton, marginBottom(10))

        body.addView(label(
            "NEXORA does not count a visible control as a working feature. Status changes only after implementation and tests.",
            11f, R.color.nexora_muted, false
        ).apply { setPadding(dp(4), dp(4), dp(4), 0) })
        return root
    }

    private fun section(title: String, rows: List<String>, emptyMessage: String): View {
        val outer = card()
        val content = vertical(dp(16))
        content.addView(label(title, 10f, R.color.nexora_accent, true))
        if (rows.isEmpty()) {
            content.addView(label(emptyMessage, 13f, R.color.nexora_muted, false).apply {
                setPadding(0, dp(9), 0, 0)
            })
        } else {
            rows.forEach { item ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(0, dp(10), 0, 0)
                }
                line.addView(label("•", 16f, R.color.nexora_accent, true), LinearParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(label(item, 13f, R.color.nexora_text, false), LinearParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(line)
            }
        }
        outer.addView(content)
        return outer
    }

    private fun openModuleAction() {
        when (module.action) {
            FeatureAction.CREATE_PROFILE -> startActivity(Intent(this, EditProfileActivity::class.java))
            FeatureAction.BULK_CREATE -> startActivity(Intent(this, BulkCreateActivity::class.java))
            FeatureAction.PROXY_MANAGER -> startActivity(Intent(this, ProxyLibraryActivity::class.java))
            FeatureAction.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            FeatureAction.OPEN_BROWSER -> {
                val profile = readLocalStore { preferredProfile() }.getOrElse { return } ?: return createProfileFirst()
                startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_PROFILE_ID, profile.id))
            }
            FeatureAction.PROFILE_EDITOR -> {
                val profile = readLocalStore { preferredProfile() }.getOrElse { return }
                val intent = Intent(this, EditProfileActivity::class.java)
                if (profile != null) intent.putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id)
                startActivity(intent)
            }
            FeatureAction.COMPATIBILITY_LAB -> {
                val profile = readLocalStore { preferredProfile() }.getOrElse { return } ?: return createProfileFirst()
                startActivity(
                    Intent(this, CompatibilityLabActivity::class.java)
                        .putExtra(CompatibilityLabActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
            FeatureAction.PRIVACY_CENTER -> {
                val profile = readLocalStore { preferredProfile() }.getOrElse { return } ?: return createProfileFirst()
                startActivity(
                    Intent(this, PrivacyCenterActivity::class.java)
                        .putExtra(PrivacyCenterActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
            FeatureAction.AUTOMATION_CENTER -> startActivity(Intent(this, AutomationCenterActivity::class.java))
            FeatureAction.NONE -> Unit
        }
    }

    private fun preferredProfile() = ProfileStore(this).loadAll().let { profiles ->
        profiles.firstOrNull { it.id == preferredProfileId } ?: profiles.maxByOrNull { it.lastUsedAt }
    }

    private fun createProfileFirst() {
        Toast.makeText(this, "Create a browser profile first.", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, EditProfileActivity::class.java))
    }

    private fun statusPill(status: FeatureStatus): TextView {
        val (textColor, bgColor) = when (status) {
            FeatureStatus.AVAILABLE -> R.color.nexora_status_green to R.color.nexora_status_green_bg
            FeatureStatus.PARTIAL -> R.color.nexora_accent to R.color.nexora_accent_soft
            FeatureStatus.ROADMAP -> R.color.nexora_muted to R.color.nexora_raised
        }
        return TextView(this).apply {
            text = status.label
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(textColor))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(bgColor))
                cornerRadius = dp(20).toFloat()
            }
        }
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
        }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun LinearParams(width: Int, height: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)
    private fun marginBottom(value: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(value)
        }

    companion object {
        const val EXTRA_FEATURE_ID = "com.nexora.browser.extra.FEATURE_ID"
    }
}
