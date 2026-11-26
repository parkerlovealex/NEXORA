package com.nexora.browser.data

/** A database-published, evidence-backed User-Agent template for an embedded app browser. */
data class UserAgentTemplate(
    val id: String,
    val appId: String,
    val displayName: String,
    /** ANDROID or IOS */
    val targetOs: String,
    val userAgent: String,
    val sourceUrl: String,
    val evidenceQuote: String,
    val verified: Boolean
)
