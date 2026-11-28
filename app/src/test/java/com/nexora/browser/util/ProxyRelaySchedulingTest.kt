package com.nexora.browser.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRelaySchedulingTest {
    @Test fun independentWorkStartsWhileTwoWorkersAreOccupied() {
        val relay = ProxyRelayServer(ProxyRelayConfig("HTTP", "localhost", 80))
        // Exercise the server's own executor without opening a socket.
        val field = ProxyRelayServer::class.java.getDeclaredField("workers").apply { isAccessible = true }
        val pool = field.get(relay) as ThreadPoolExecutor
        val running = CountDownLatch(2)
        val release = CountDownLatch(1)
        val third = CountDownLatch(1)
        try {
            repeat(2) {
                pool.execute {
                    running.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            pool.execute { third.countDown() }
            assertTrue("independent work should start immediately", third.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            relay.close()
        }
    }
}
