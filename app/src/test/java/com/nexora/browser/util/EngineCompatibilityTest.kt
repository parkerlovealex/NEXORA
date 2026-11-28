package com.nexora.browser.util

import com.nexora.browser.data.EngineDescriptor
import com.nexora.browser.data.UserAgentTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 4.3b compatibility states: unknown is never compatible. */
class EngineCompatibilityTest {

    private fun template(family: String?, major: Int?) = UserAgentTemplate(
        id = "t", appId = "facebook", displayName = "Facebook", targetOs = "ANDROID",
        userAgent = "Mozilla/5.0", sourceUrl = "https://example.com", evidenceQuote = "q",
        verified = true, engineFamily = family, engineMajor = major,
        engineBasis = if (family != null) "evidence" else null,
        engineEvidenceRefs = if (family != null) listOf("src") else emptyList()
    )

    private fun runtime(family: String?, major: Int?) = EngineDescriptor(
        family = family, major = major,
        fullVersion = if (major != null) "$major.0.0.1" else null,
        fullVersionTrust = EngineDescriptor.TRUST_PACKAGE, platform = "android"
    )

    @Test fun matchingMajorIsCompatible() {
        assertEquals(
            EngineCompatibilityState.COMPATIBLE,
            EngineCompatibility.evaluate(template("chromium", 158), runtime("chromium", 158))
        )
        assertEquals(
            TemplateApplication.NORMAL,
            EngineCompatibility.application(EngineCompatibilityState.COMPATIBLE)
        )
    }

    @Test fun differentMajorDiffers() {
        assertEquals(
            EngineCompatibilityState.DIFFERS,
            EngineCompatibility.evaluate(template("chromium", 158), runtime("chromium", 159))
        )
        assertEquals(
            TemplateApplication.REQUIRES_OVERRIDE,
            EngineCompatibility.application(EngineCompatibilityState.DIFFERS)
        )
    }

    @Test fun differentFamilyIsUnsupportedAndBlocked() {
        assertEquals(
            EngineCompatibilityState.UNSUPPORTED,
            EngineCompatibility.evaluate(template("gecko", 140), runtime("chromium", 158))
        )
        // An unsupported template can never enter the normal live-profile path.
        assertEquals(
            TemplateApplication.BLOCKED,
            EngineCompatibility.application(EngineCompatibilityState.UNSUPPORTED)
        )
    }

    @Test fun missingTemplateMetadataIsUnknown() {
        assertEquals(
            EngineCompatibilityState.UNKNOWN,
            EngineCompatibility.evaluate(template(null, null), runtime("chromium", 158))
        )
        assertEquals(
            TemplateApplication.REQUIRES_OVERRIDE,
            EngineCompatibility.application(EngineCompatibilityState.UNKNOWN)
        )
    }

    @Test fun missingRuntimeIsNoRuntime() {
        assertEquals(
            EngineCompatibilityState.NO_RUNTIME,
            EngineCompatibility.evaluate(template("chromium", 158), null)
        )
        assertEquals(
            TemplateApplication.REQUIRES_OVERRIDE,
            EngineCompatibility.application(EngineCompatibilityState.NO_RUNTIME)
        )
    }

    @Test fun unestablishedRuntimeSideIsUnknown() {
        // Template known, runtime family/major not established: never a guess.
        assertEquals(
            EngineCompatibilityState.UNKNOWN,
            EngineCompatibility.evaluate(template("chromium", 158), runtime(null, null))
        )
        assertEquals(
            EngineCompatibilityState.UNKNOWN,
            EngineCompatibility.evaluate(template("chromium", 158), runtime("chromium", null))
        )
    }
}
