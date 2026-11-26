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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.nexora.browser.R
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.features.FeatureCatalog
import com.nexora.browser.features.FeatureModule
import com.nexora.browser.features.FeatureStatus

class FeatureCenterActivity : AppCompatActivity() {

    private val profileId: String
        get() = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("IDENTITY & PRIVACY", 10f, R.color.nexora_accent, true))
        heading.addView(label("Anti-detect Center", 20f, R.color.nexora_text, true))
        header.addView(heading, LinearParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        heroBody.addView(label("CONTROL YOUR BROWSER IDENTITY", 10f, R.color.nexora_accent, true))
        heroBody.addView(label("Your control center.", 27f, R.color.nexora_text, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        heroBody.addView(label(
            "Review profile identity, network routing, privacy controls and engine limits. Each module shows what is implemented in this build and what still needs work.",
            14f, R.color.nexora_muted, false
        ))
        hero.addView(heroBody)
        body.addView(hero, marginBottom(14))

        val partial = FeatureCatalog.modules.count { it.status == FeatureStatus.PARTIAL }
        val roadmap = FeatureCatalog.modules.count { it.status == FeatureStatus.ROADMAP }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statCard(partial.toString(), "PARTIAL", R.color.nexora_accent), LinearParams(0, dp(72), 1f).apply {
            marginEnd = dp(6)
        })
        stats.addView(statCard(roadmap.toString(), "ROADMAP", R.color.nexora_muted), LinearParams(0, dp(72), 1f).apply {
            marginStart = dp(6)
        })
        body.addView(stats, marginBottom(18))

        body.addView(label("CONTROL MODULES", 11f, R.color.nexora_muted, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(2), 0, 0, dp(10))
        })
        FeatureCatalog.modules.forEach { module ->
            body.addView(moduleCard(module), marginBottom(10))
        }

        val note = card()
        val noteBody = vertical(dp(16))
        noteBody.addView(label("ENGINEERING RULE", 10f, R.color.nexora_accent, true))
        noteBody.addView(label(
            "A roadmap label means the capability is not active in this build. A partial label means only the listed behavior is implemented. NEXORA currently uses the installed Android System WebView.",
            13f, R.color.nexora_muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        note.addView(noteBody)
        body.addView(note, marginBottom(10))
        return root
    }

    private fun moduleCard(module: FeatureModule): View {
        val outer = card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@FeatureCenterActivity, FeaturePageActivity::class.java)
                        .putExtra(FeaturePageActivity.EXTRA_FEATURE_ID, module.id)
                        .putExtra(EXTRA_PROFILE_ID, profileId)
                )
            }
        }
        val content = vertical(dp(15))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label(module.category, 9f, R.color.nexora_muted, true), LinearParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(statusPill(module.status))
        content.addView(top)
        content.addView(label(module.title, 18f, R.color.nexora_text, true).apply {
            setPadding(0, dp(9), 0, dp(4))
        })
        content.addView(label(module.summary, 13f, R.color.nexora_muted, false))
        content.addView(label("Open module  ›", 12f, R.color.nexora_accent, true).apply {
            setPadding(0, dp(12), 0, 0)
        })
        outer.addView(content)
        return outer
    }

    private fun statCard(value: String, caption: String, accent: Int): View {
        val outer = card()
        val content = vertical(dp(12))
        content.addView(label(value, 22f, accent, true))
        content.addView(label(caption, 9f, R.color.nexora_muted, true))
        outer.addView(content)
        return outer
    }

    private fun statusPill(status: FeatureStatus): TextView {
        val (textColor, bgColor, text) = when (status) {
            FeatureStatus.AVAILABLE -> Triple(R.color.nexora_status_green, R.color.nexora_status_green_bg, status.label)
            FeatureStatus.PARTIAL -> Triple(R.color.nexora_accent, R.color.nexora_accent_soft, status.label)
            FeatureStatus.ROADMAP -> Triple(R.color.nexora_muted, R.color.nexora_raised, status.label)
        }
        return TextView(this).apply {
            this.text = text
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(textColor))
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(bgColor))
                cornerRadius = dp(20).toFloat()
            }
        }
    }

    private fun card(): MaterialCardView =
        MaterialCardView(this).apply {
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

    private fun label(textValue: String, size: Float, textColor: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color(textColor))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun LinearParams(width: Int, height: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)
    private fun marginBottom(value: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(value)
        }

    companion object {
        const val EXTRA_PROFILE_ID = "com.nexora.browser.extra.FEATURE_PROFILE_ID"
    }
}
