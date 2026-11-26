package com.nexora.browser.content

import java.net.IDN
import java.net.URI
import java.util.Locale

data class ContentBlockerSnapshot(
    val enabled: Boolean,
    val allowedSites: Set<String>,
    val rawRules: List<String>
)

data class ContentFilterRule(
    val host: String,
    val pathPrefix: String?,
    val exception: Boolean
)

data class ContentFilterParseReport(
    val rules: List<ContentFilterRule>,
    val supportedLines: Int,
    val ignoredLines: Int
)

data class ContentBlockDecision(
    val blocked: Boolean,
    val matchedRule: ContentFilterRule? = null
)

/**
 * NEXORA's lightweight WebView request filter.
 *
 * This deliberately supports a documented, safe subset of common host rules:
 *   ||ads.example^
 *   @@||trusted.ads.example^
 *   ||ads.example/path^
 *   example.org
 *
 * It does not claim full Adblock Plus/EasyList compatibility. Unsupported
 * cosmetic, regex, scriptlet, and resource-option rules are counted as ignored.
 */
class ContentBlockerEngine {
    @Volatile
    private var state = EngineState(
        enabled = false,
        allowedSites = emptySet(),
        rulesByHost = emptyMap(),
        supportedLines = 0,
        ignoredLines = 0
    )

    fun update(snapshot: ContentBlockerSnapshot) {
        val parsed = parseRules(snapshot.rawRules)
        state = EngineState(
            enabled = snapshot.enabled,
            allowedSites = snapshot.allowedSites.mapNotNull(::normalizeHost).toSet(),
            rulesByHost = parsed.rules.groupBy { it.host },
            supportedLines = parsed.supportedLines,
            ignoredLines = parsed.ignoredLines
        )
    }

    fun shouldBlock(resourceUrl: String, pageUrl: String?): Boolean =
        evaluate(resourceUrl, pageUrl).blocked

    /**
     * Evaluates a request and returns the matching rule for optional, redacted
     * diagnostics. Callers must not persist the original URL or request headers.
     */
    fun evaluate(resourceUrl: String, pageUrl: String?): ContentBlockDecision {
        val current = state
        if (!current.enabled) return ContentBlockDecision(blocked = false)

        val resource = parseUrl(resourceUrl) ?: return ContentBlockDecision(blocked = false)
        val pageHost = parseUrl(pageUrl.orEmpty())?.host
        if (pageHost != null && current.allowedSites.any { matchesDomain(pageHost, it) }) {
            return ContentBlockDecision(blocked = false)
        }

        // Only inspect rules indexed under the resource host or its parent domains.
        // This avoids scanning a large imported list for every WebView request.
        val candidateRules = mutableListOf<ContentFilterRule>()
        var candidateHost = resource.host
        while (true) {
            current.rulesByHost[candidateHost]?.let(candidateRules::addAll)
            val separator = candidateHost.indexOf('.')
            if (separator < 0) break
            candidateHost = candidateHost.substring(separator + 1)
        }

        // Exception rules always take precedence over blocking rules.
        if (candidateRules.any { it.exception && it.matches(resource.host, resource.path) }) {
            return ContentBlockDecision(blocked = false)
        }
        val matchedBlockRule = candidateRules.firstOrNull {
            !it.exception && it.matches(resource.host, resource.path)
        }
        return ContentBlockDecision(
            blocked = matchedBlockRule != null,
            matchedRule = matchedBlockRule
        )
    }

    fun supportedRuleCount(): Int = state.supportedLines

    fun ignoredRuleCount(): Int = state.ignoredLines

    fun isEnabled(): Boolean = state.enabled

    fun isSiteAllowed(pageUrl: String?): Boolean {
        val host = parseUrl(pageUrl.orEmpty())?.host ?: return false
        return state.allowedSites.any { matchesDomain(host, it) }
    }

    private data class EngineState(
        val enabled: Boolean,
        val allowedSites: Set<String>,
        val rulesByHost: Map<String, List<ContentFilterRule>>,
        val supportedLines: Int,
        val ignoredLines: Int
    )

    private data class ParsedUrl(val host: String, val path: String)

    private fun ContentFilterRule.matches(resourceHost: String, resourcePath: String): Boolean {
        return matchesDomain(resourceHost, host) &&
            (pathPrefix == null || resourcePath.startsWith(pathPrefix))
    }

    private fun parseUrl(raw: String): ParsedUrl? {
        if (raw.isBlank()) return null
        return runCatching {
            val uri = URI(raw)
            val host = normalizeHost(uri.host) ?: return null
            ParsedUrl(host, uri.rawPath.orEmpty().ifBlank { "/" })
        }.getOrNull()
    }

    companion object {
        private val DOMAIN_LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        fun parseRules(lines: List<String>): ContentFilterParseReport {
            val parsed = mutableListOf<ContentFilterRule>()
            var supported = 0
            var ignored = 0

            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("!") || line == "#" || line.startsWith("# ")) return@forEach

                val exception = line.startsWith("@@")
                val pattern = if (exception) line.removePrefix("@@") else line
                val rule = parseSingleRule(pattern, exception)
                if (rule == null) {
                    ignored++
                } else {
                    parsed.add(rule)
                    supported++
                }
            }

            val unique = parsed.distinctBy { Triple(it.host, it.pathPrefix, it.exception) }
            return ContentFilterParseReport(unique, supported, ignored)
        }

        fun normalizeHost(raw: String?): String? {
            val value = raw?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT).orEmpty()
            if (value.isBlank() || value.contains('/') || value.contains(':') || value.contains('*')) return null
            val ascii = runCatching { IDN.toASCII(value).lowercase(Locale.ROOT) }.getOrNull() ?: return null
            if (ascii.length > 253 || ascii.startsWith(".") || ascii.endsWith(".")) return null
            val labels = ascii.split('.')
            if (labels.size < 2 || labels.any { !DOMAIN_LABEL.matches(it) }) return null
            return ascii
        }

        private fun parseSingleRule(pattern: String, exception: Boolean): ContentFilterRule? {
            val body = when {
                pattern.startsWith("||") -> pattern.removePrefix("||")
                else -> pattern
            }
            if (body.startsWith("/") || body.contains("://") || body.startsWith("|") ||
                body.contains("*") || body.contains("\$") || body.contains("^") && !body.endsWith("^")
            ) return null

            val delimiterIndex = body.indexOfFirst { it == '/' || it == '^' }
            val hostPart = if (delimiterIndex >= 0) body.substring(0, delimiterIndex) else body
            val tail = if (delimiterIndex >= 0) body.substring(delimiterIndex) else ""
            val host = normalizeHost(hostPart) ?: return null

            val pathPrefix = if (tail.startsWith("/")) {
                val path = tail.removeSuffix("^")
                if (path.isBlank() || path.contains('?') || path.contains('#') || path.contains('^')) return null
                val suffix = tail.removePrefix(path)
                if (suffix !in setOf("", "^")) return null
                path
            } else {
                if (tail !in setOf("", "^")) return null
                null
            }

            return ContentFilterRule(host, pathPrefix, exception)
        }

        private fun matchesDomain(host: String, ruleHost: String): Boolean =
            host == ruleHost || host.endsWith(".$ruleHost")
    }
}
