package com.nexora.browser.automation

/** Sanitized profile fields used to build a workflow preview without exposing credentials. */
data class WorkflowProfileSnapshot(
    val id: String,
    val name: String,
    val proxyType: String,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyEntryId: String,
    val deviceId: String
)

data class WorkflowProxySnapshot(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val createdAt: Long
)

data class ProxyAssignment(
    val profileId: String,
    val proxyId: String
)

data class WorkflowPreview(
    val workflowId: String,
    val title: String,
    val totalProfiles: Int,
    val affectedProfiles: Int,
    val findings: List<String> = emptyList(),
    val assignments: List<ProxyAssignment> = emptyList(),
    val summary: String
)

/** Pure planning logic. It never mutates profiles or starts network activity. */
object WorkflowPlanner {
    private val relayTypes = setOf("HTTP", "HTTPS", "SOCKS5")

    fun auditProfiles(profiles: List<WorkflowProfileSnapshot>): WorkflowPreview {
        val findings = mutableListOf<String>()
        val duplicateNames = profiles.groupBy { it.name.trim().lowercase() }
            .filter { it.key.isNotBlank() && it.value.size > 1 }
        duplicateNames.forEach { (name, items) ->
            findings += "Duplicate profile name '$name' (${items.size} profiles)."
        }
        profiles.forEach { profile ->
            if (profile.deviceId.isBlank()) findings += "${profile.name}: device preset is missing."
            val type = profile.proxyType.uppercase()
            if (type != "NONE" && (profile.proxyHost.isBlank() || profile.proxyPort !in 1..65535)) {
                findings += "${profile.name}: proxy endpoint is incomplete."
            } else if (type !in setOf("NONE", "HTTP", "HTTPS", "SOCKS5", "SOCKS4")) {
                findings += "${profile.name}: proxy type '$type' is unknown."
            } else if (type == "SOCKS4") {
                findings += "${profile.name}: SOCKS4 is import-only and cannot be used by the current relay."
            }
        }
        val summary = if (profiles.isEmpty()) {
            "No profiles are available to audit."
        } else if (findings.isEmpty()) {
            "Checked ${profiles.size} profiles. No configuration issues were found by these local checks."
        } else {
            "Checked ${profiles.size} profiles and found ${findings.size} configuration warning(s)."
        }
        return WorkflowPreview(
            workflowId = ID_PROFILE_AUDIT,
            title = "Profile configuration audit",
            totalProfiles = profiles.size,
            affectedProfiles = 0,
            findings = findings,
            summary = summary
        )
    }

    fun assignUnassignedProxies(
        profiles: List<WorkflowProfileSnapshot>,
        proxies: List<WorkflowProxySnapshot>
    ): WorkflowPreview {
        val targets = profiles.filter {
            it.proxyType.equals("NONE", ignoreCase = true) && it.proxyEntryId.isBlank()
        }.sortedWith(compareBy<WorkflowProfileSnapshot> { it.name.lowercase() }.thenBy { it.id })
        // A21: one-to-one means a proxy already assigned to any profile
        // is not available again — previously the pool ignored existing
        // assignments, so re-running after adding a profile could pair
        // an already-used proxy with a second profile.
        val assignedProxyIds = profiles.mapNotNull {
            it.proxyEntryId.takeIf { id -> id.isNotBlank() }
        }.toSet()
        val available = proxies.filter {
            it.type.uppercase() in relayTypes && it.host.isNotBlank() && it.port in 1..65535 &&
                it.id !in assignedProxyIds
        }.sortedWith(compareBy<WorkflowProxySnapshot> { it.createdAt }.thenBy { it.id })
        val assignments = targets.zip(available).map { (profile, proxy) ->
            ProxyAssignment(profile.id, proxy.id)
        }
        val summary = when {
            targets.isEmpty() -> "No profiles with Direct connection are available for assignment."
            available.isEmpty() -> "No valid HTTP, HTTPS or SOCKS5 proxies are available in the library."
            else -> "Preview: ${assignments.size} proxy/profiles will be paired one-to-one. " +
                "${targets.size - assignments.size} unassigned profile(s) will remain unchanged."
        }
        return WorkflowPreview(
            workflowId = ID_ASSIGN_UNASSIGNED_PROXIES,
            title = "Assign saved proxies to unassigned profiles",
            totalProfiles = profiles.size,
            affectedProfiles = assignments.size,
            assignments = assignments,
            summary = summary
        )
    }

    const val ID_PROFILE_AUDIT = "profile-audit"
    const val ID_ASSIGN_UNASSIGNED_PROXIES = "assign-unassigned-proxies"
}
