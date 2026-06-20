package io.appsynk.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Attribution result returned by AppSynk after an install is attributed.
 * Stored in SharedPreferences for subsequent event enrichment.
 */
data class AttributionData(
    @SerializedName("channel") val channel: String?,
    @SerializedName("campaignName") val campaignName: String?,
    @SerializedName("adSetName") val adSetName: String?,
    @SerializedName("creativeName") val creativeName: String?,
    @SerializedName("medium") val medium: String?,
    @SerializedName("source") val source: String?,
    @SerializedName("clickId") val clickId: String?,
    @SerializedName("clickTimestamp") val clickTimestamp: Date?,
    @SerializedName("isOrganic") val isOrganic: Boolean,
    @SerializedName("attributionModel") val attributionModel: String?,
    @SerializedName("confidenceScore") val confidenceScore: Double?,
    /** Deferred deep link destination to route the user to, if any. */
    @SerializedName("deepLink") val deepLink: String? = null
) {
    companion object {
        val ORGANIC = AttributionData(
            channel = null,
            campaignName = null,
            adSetName = null,
            creativeName = null,
            medium = null,
            source = null,
            clickId = null,
            clickTimestamp = null,
            isOrganic = true,
            attributionModel = null,
            confidenceScore = null
        )
    }
}
