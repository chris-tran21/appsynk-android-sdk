package io.appsynk.sdk.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK no longer parses the referrer (that's the backend's job) — it must carry the RAW string,
 * gclid/gbraid included, so Google Ads attribution survives. This guards that contract.
 */
class PlayReferrerTest {

    @Test
    fun rawReferrerStringIsPreservedWithGclid() {
        val raw = "utm_source=google-play&utm_medium=cpc&gclid=ABC123&gbraid=GB456"
        val referrer = PlayReferrer(
            referrerUrl = raw,
            referrerClickTs = 100,
            installBeginTs = 200,
            referrerClickTsServer = 101,
            installBeginTsServer = 201,
            installVersion = "1.0",
            googlePlayInstant = false
        )

        assertEquals(raw, referrer.referrerUrl)
        assertTrue(referrer.referrerUrl.contains("gclid=ABC123"))
        assertTrue(referrer.referrerUrl.contains("gbraid=GB456"))
    }
}
