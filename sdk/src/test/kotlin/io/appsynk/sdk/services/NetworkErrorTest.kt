package io.appsynk.sdk.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The permanent/transient split the queue uses to drop poison batches vs keep for retry. */
class NetworkErrorTest {

    @Test
    fun permanentClientErrorsAreNotRetried() {
        assertTrue(NetworkError.Unauthorized.isPermanentClientError)
        assertTrue(NetworkError.PaymentRequired.isPermanentClientError)
        assertTrue(NetworkError.Forbidden.isPermanentClientError)
        assertTrue(NetworkError.BadRequest("bad").isPermanentClientError)
    }

    @Test
    fun transientErrorsAreRetried() {
        assertFalse(NetworkError.RateLimited.isPermanentClientError)
        assertFalse(NetworkError.ServerError(503).isPermanentClientError)
        assertFalse(NetworkError.InvalidResponse.isPermanentClientError)
    }
}
