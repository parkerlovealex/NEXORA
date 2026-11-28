package com.nexora.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyOverrideClearTest {
    @Test fun throwingClearStopsDirectBrowsing() {
        val error = IllegalStateException("provider rejected clear")
        var opened = 0
        var reported: Throwable? = null
        ProxyOverrideClear.request({ throw error }) { result ->
            result.fold({ opened++ }, { reported = it })
        }
        assertEquals(0, opened)
        assertSame(error, reported)
    }

    @Test fun waitsForAcknowledgementAndCompletesOnlyOnce() {
        lateinit var acknowledge: Runnable
        val results = mutableListOf<Result<Unit>>()
        ProxyOverrideClear.request({ acknowledge = it }) { results.add(it) }
        assertTrue(results.isEmpty())
        acknowledge.run()
        acknowledge.run()
        assertEquals(1, results.size)
        assertTrue(results.single().isSuccess)
    }

    @Test fun callbackOnAThrowingSubmissionCannotReopenTheRoute() {
        lateinit var acknowledge: Runnable
        val results = mutableListOf<Result<Unit>>()
        ProxyOverrideClear.request({
            acknowledge = it
            it.run()
            throw IllegalStateException("submission failed")
        }) { results.add(it) }
        acknowledge.run()
        assertEquals(1, results.size)
        assertTrue(results.single().isFailure)
    }
}
