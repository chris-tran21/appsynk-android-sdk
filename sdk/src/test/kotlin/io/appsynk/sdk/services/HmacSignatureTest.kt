package io.appsynk.sdk.services

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HMAC signing against the iOS test vectors — byte-for-byte parity so a future backend validation
 * covers both platforms. Robolectric provides `android.util.Base64` (the only Android dependency).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HmacSignatureTest {

    @Test
    fun signatureMatchesIosVector() {
        val headers = RequestSigner.sign("hello".toByteArray(Charsets.UTF_8), 1_700_000_000_000L, "topsecret", "k")

        assertEquals(setOf("Authorization", "x-Key-Id", "x-Timestamp", "x-Signature"), headers.keys)
        assertEquals("HMAC", headers["Authorization"])
        assertEquals("k", headers["x-Key-Id"])
        assertEquals("1700000000000", headers["x-Timestamp"])
        assertEquals("Zv3JktWIlp+xnX5V3UTxl4iptM1R5XjnDTKuiu7cpBM=", headers["x-Signature"])
    }

    @Test
    fun emptyPayloadUsesEmptyContentHash() {
        val headers = RequestSigner.sign(ByteArray(0), 42L, "s", "k")
        // canonical = "42\n" (empty content hash)
        assertEquals("R8M6qELWJp/MCMTKELzKJNvEObrYbr8dmos8vfRVmOI=", headers["x-Signature"])
    }
}
