package io.appsynk.sdk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Anonymized mode strips device identifiers but preserves non-identifying attribution. */
class AnonymizeTest {

    @Test
    fun anonymizedCopyStripsDeviceIdentifiers() {
        val attr = AttributionInfo(
            gaid = "g",
            androidId = "a",
            referrerUrl = "utm_source=x",
            metaInstallReferrer = "m",
            clickId = "c"
        )
        val anon = attr.anonymizedCopy()

        assertNull(anon.gaid)
        assertNull(anon.androidId)
        assertNull(anon.idfa)
        assertNull(anon.idfv)
        // Campaign attribution is not a personal device identifier — keep it.
        assertEquals("utm_source=x", anon.referrerUrl)
        assertEquals("m", anon.metaInstallReferrer)
        assertEquals("c", anon.clickId)
    }
}
