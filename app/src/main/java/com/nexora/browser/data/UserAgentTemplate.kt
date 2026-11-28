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
    val verified: Boolean,
    /** Canonical catalog status (e.g. "verified"); empty for legacy embedded records. */
    val status: String = "",
    /** Catalog release that published this record; empty for legacy embedded records. */
    val catalogReleaseId: String = "",
    /** dataVersion of the template catalog this record came from. */
    val catalogDataVersion: String = "",
    /** The complete canonical evidence list, preserved unflattened. */
    val evidence: List<UserAgentEvidence> = emptyList()
) {
    val evidenceCount: Int get() = evidence.size
}
