package com.nexora.browser.util

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTunnelLifetimeTest {
    @Test fun activityInEitherDirectionExtendsOnlyTheIdleDeadline() {
        fun minute(value: Long) = TimeUnit.MINUTES.toNanos(value)
        val idle = RelayTunnelLifetime(0)
        idle.recordTraffic(minute(4))
        assertFalse(idle.expired(minute(8)))
        assertTrue(idle.expired(minute(9)))

        val active = RelayTunnelLifetime(0)
        for (i in 1L..59L) active.recordTraffic(minute(i))
        assertFalse(active.expired(minute(59)))
        assertTrue(active.expired(minute(60)))
    }
}
