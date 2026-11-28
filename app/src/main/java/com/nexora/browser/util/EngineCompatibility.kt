package com.nexora.browser.util

import com.nexora.browser.data.EngineDescriptor
import com.nexora.browser.data.UserAgentTemplate

/** How a template's evidence-backed engine compares with the installed runtime. */
enum class EngineCompatibilityState { COMPATIBLE, DIFFERS, UNSUPPORTED, UNKNOWN, NO_RUNTIME }

/** Whether a template UA may be applied to live WebView profiles. */
enum class TemplateApplication { NORMAL, REQUIRES_OVERRIDE, BLOCKED }

/**
 * Evaluates catalog templates against the ACTUAL installed engine descriptor.
 * Unknown is never treated as compatible: without evidence on either side the
 * honest answer is UNKNOWN, and only an exact family + major match is
 * COMPATIBLE.
 */
object EngineCompatibility {

    fun evaluate(template: UserAgentTemplate, descriptor: EngineDescriptor?): EngineCompatibilityState {
        if (descriptor == null) return EngineCompatibilityState.NO_RUNTIME
        val templateFamily = template.engineFamily ?: return EngineCompatibilityState.UNKNOWN
        val templateMajor = template.engineMajor ?: return EngineCompatibilityState.UNKNOWN
        val runtimeFamily = descriptor.family ?: return EngineCompatibilityState.UNKNOWN
        if (templateFamily != runtimeFamily) return EngineCompatibilityState.UNSUPPORTED
        val runtimeMajor = descriptor.major ?: return EngineCompatibilityState.UNKNOWN
        return if (templateMajor == runtimeMajor) EngineCompatibilityState.COMPATIBLE
        else EngineCompatibilityState.DIFFERS
    }

    /** Live-profile application policy: only COMPATIBLE applies silently. */
    fun application(state: EngineCompatibilityState): TemplateApplication = when (state) {
        EngineCompatibilityState.COMPATIBLE -> TemplateApplication.NORMAL
        EngineCompatibilityState.UNSUPPORTED -> TemplateApplication.BLOCKED
        else -> TemplateApplication.REQUIRES_OVERRIDE
    }

    fun label(state: EngineCompatibilityState): String = when (state) {
        EngineCompatibilityState.COMPATIBLE -> "Compatible"
        EngineCompatibilityState.DIFFERS -> "Differs"
        EngineCompatibilityState.UNSUPPORTED -> "Unsupported"
        EngineCompatibilityState.UNKNOWN -> "Unknown"
        EngineCompatibilityState.NO_RUNTIME -> "No runtime"
    }

    fun detail(state: EngineCompatibilityState): String = when (state) {
        EngineCompatibilityState.COMPATIBLE -> "Template engine major matches the installed WebView."
        EngineCompatibilityState.DIFFERS -> "The template and installed WebView have different engine majors."
        EngineCompatibilityState.UNSUPPORTED -> "The template targets a different engine family than the installed WebView."
        EngineCompatibilityState.UNKNOWN -> "Engine compatibility has not been independently established."
        EngineCompatibilityState.NO_RUNTIME -> "No installed WebView engine could be established on this device."
    }
}
