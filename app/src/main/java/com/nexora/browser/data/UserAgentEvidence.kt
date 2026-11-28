package com.nexora.browser.data

/** One structured evidence entry from the canonical UA template catalog. */
data class UserAgentEvidence(
    val sourceId: String,
    val kind: String,
    val sourceUrl: String?,
    val sourceRef: String?,
    val quotedText: String,
    val observed: String?,
    val appVersion: String?,
    val appBuild: String?,
    val observedAt: String?,
    val fetchedAt: String?
)
