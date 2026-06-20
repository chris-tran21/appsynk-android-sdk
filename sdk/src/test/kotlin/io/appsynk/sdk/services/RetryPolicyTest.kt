package io.appsynk.sdk.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Pure-JVM tests for the exponential backoff + retry classification (mirrors the iOS policy). */
class RetryPolicyTest {

    private val policy = RetryPolicy.DEFAULT

    @Test
    fun backoffSequenceIs2_4_8_16() {
        assertEquals(4, policy.maxRetries)
        assertEquals(2_000L, policy.delayMs(1))
        assertEquals(4_000L, policy.delayMs(2))
        assertEquals(8_000L, policy.delayMs(3))
        assertEquals(16_000L, policy.delayMs(4))
    }

    @Test
    fun retriesTransientStatusCodes() {
        for (code in intArrayOf(429, 500, 502, 503)) assertTrue("expected retry on $code", policy.shouldRetry(code))
        for (code in intArrayOf(200, 202, 400, 401, 402, 403)) assertFalse("no retry on $code", policy.shouldRetry(code))
    }

    @Test
    fun retriesConnectionIoErrors() {
        assertTrue(policy.shouldRetry(SocketTimeoutException()))
        assertTrue(policy.shouldRetry(UnknownHostException()))
        assertTrue(policy.shouldRetry(ConnectException()))
        assertTrue(policy.shouldRetry(IOException()))
        assertFalse(policy.shouldRetry(IllegalStateException()))
    }
}
