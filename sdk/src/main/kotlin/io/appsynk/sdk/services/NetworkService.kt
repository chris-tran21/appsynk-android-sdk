package io.appsynk.sdk.services

import android.os.Build
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.core.AppSynkConstants
import io.appsynk.sdk.models.AppSynkEvent
import io.appsynk.sdk.models.AttributionData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP client for the AppSynk API — all endpoints, exponential retry, and optional HMAC signing.
 *
 * Mirrors the iOS `NetworkService` (RetryPolicy, RequestSigner, error mapping). Uses
 * `HttpURLConnection` to avoid an OkHttp dependency; each attempt runs on [Dispatchers.IO] and is
 * rebuilt (and re-signed with a fresh timestamp) so HMAC stays valid across retries.
 */
class NetworkService(
    internal val apiKey: String,
    private val options: AppSynkOptions
) {
    // Gson's setDateFormat("…'Z'") formats in LOCAL time but stamps a literal 'Z' → wrong offset.
    // A type adapter with an explicit UTC SimpleDateFormat emits (and parses) correct UTC timestamps.
    internal val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonSerializer<Date> { src, _, _ ->
            JsonPrimitive(utcDateFormat().format(src))
        })
        .registerTypeAdapter(Date::class.java, JsonDeserializer<Date> { json, _, _ ->
            utcDateFormat().parse(json.asString)
        })
        .create()

    private val retryPolicy = RetryPolicy.DEFAULT

    // Sign only when BOTH credentials are present and non-empty; otherwise requests go unsigned.
    private val hmacSecret: String? = options.hmacSecretKey?.takeIf { it.isNotEmpty() }
    private val hmacKeyId: String? = options.hmacKeyId?.takeIf { it.isNotEmpty() }

    /** Custom User-Agent — the backend reads it server-side for probabilistic matching. */
    private val userAgent: String =
        "AppSynk-Android/${AppSynkConstants.SDK_VERSION} (${Build.MODEL}; Android ${Build.VERSION.RELEASE})"

    private val baseUrl: String
        get() = (options.customApiUrl ?: options.environment.baseUrl).trimEnd('/')

    // ── Endpoints ──────────────────────────────────────────────────────────────

    /** POST /v1/events — single event. 202 = accepted. */
    suspend fun ingest(event: AppSynkEvent) {
        val (body, code) = perform("POST", "v1/events", body = gson.toJson(event).toByteArray(Charsets.UTF_8))
        validate(code, body)
    }

    /** POST /v1/events/batch — up to 100 events. 202 = accepted. */
    suspend fun ingestBatch(events: List<AppSynkEvent>) {
        if (events.isEmpty()) return
        val payload = gson.toJson(BatchPayload(events)).toByteArray(Charsets.UTF_8)
        val (body, code) = perform("POST", "v1/events/batch", body = payload)
        validate(code, body)
    }

    /** GET /v1/ping — connectivity + API key check (200 valid / 401 invalid). */
    suspend fun ping(): PingResponse {
        val (body, code) = perform("GET", "v1/ping", body = null)
        validate(code, body)
        return gson.fromJson(body, PingResponse::class.java)
    }

    /** GET /v1/sdk/init?bundleId= — validates key + bundleId, returns plan + environment. */
    suspend fun sdkInit(bundleId: String): SdkInitResponse {
        val query = "bundleId=" + URLEncoder.encode(bundleId, "UTF-8")
        val (body, code) = perform("GET", "v1/sdk/init", query = query, body = null)
        validate(code, body)
        return gson.fromJson(body, SdkInitResponse::class.java)
    }

    /** GET /v1/links/{linkId}/attribution — resolves a tracking link's campaign data. */
    suspend fun resolveLinkAttribution(linkId: String): AttributionData {
        val (body, code) = perform("GET", "v1/links/$linkId/attribution", body = null)
        validate(code, body)
        return gson.fromJson(body, LinkAttributionResponse::class.java).toAttributionData()
    }

    // ── Request execution + retry ──────────────────────────────────────────────

    /**
     * Sends the request, retrying transient failures (429 / 5xx / connection IOExceptions) with
     * exponential backoff (2/4/8/16s). The request is rebuilt and re-signed each attempt.
     */
    private suspend fun perform(
        method: String,
        path: String,
        query: String? = null,
        body: ByteArray?
    ): Pair<String, Int> {
        val urlStr = buildString {
            append(baseUrl).append('/').append(path)
            if (query != null) append('?').append(query)
        }
        var attempt = 0
        while (true) {
            val outcome: Pair<String, Int>? = try {
                // Only the blocking HttpURLConnection call needs the IO dispatcher; the backoff
                // delay below is non-blocking and runs on the caller's dispatcher.
                val (responseBody, code) = withContext(Dispatchers.IO) { execute(urlStr, method, body) }
                if (retryPolicy.shouldRetry(code) && attempt < retryPolicy.maxRetries) {
                    verboseLog("HTTP $code on /$path — retry ${attempt + 1}/${retryPolicy.maxRetries}")
                    null
                } else {
                    responseBody to code
                }
            } catch (e: IOException) {
                if (retryPolicy.shouldRetry(e) && attempt < retryPolicy.maxRetries) {
                    verboseLog("Network error ${e.javaClass.simpleName} on /$path — retry ${attempt + 1}/${retryPolicy.maxRetries}")
                    null
                } else {
                    throw e
                }
            }
            if (outcome != null) return outcome
            attempt++
            delay(retryPolicy.delayMs(attempt))
        }
    }

    /** One HTTP attempt. Builds the standard headers and, when configured, the HMAC headers. */
    private fun execute(urlStr: String, method: String, body: ByteArray?): Pair<String, Int> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("X-Api-Key", apiKey)
        conn.setRequestProperty("X-SDK-Version", AppSynkConstants.SDK_VERSION)
        conn.setRequestProperty("X-SDK-Platform", AppSynkConstants.PLATFORM)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", userAgent)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
        }
        signHeaders(body)?.forEach { (field, value) -> conn.setRequestProperty(field, value) }

        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return text to code
        } finally {
            conn.disconnect()
        }
    }

    /** Optional HMAC headers — only when both credentials are configured. Re-signed per attempt. */
    private fun signHeaders(body: ByteArray?): Map<String, String>? {
        val secret = hmacSecret ?: return null
        val keyId = hmacKeyId ?: return null
        return RequestSigner.sign(body ?: ByteArray(0), System.currentTimeMillis(), secret, keyId)
    }

    private fun validate(statusCode: Int, body: String) {
        when (statusCode) {
            200, 202 -> return
            400 -> throw NetworkError.BadRequest(body)
            401 -> throw NetworkError.Unauthorized
            402 -> throw NetworkError.PaymentRequired
            403 -> throw NetworkError.Forbidden
            429 -> throw NetworkError.RateLimited
            else -> throw NetworkError.ServerError(statusCode)
        }
    }

    private fun verboseLog(msg: String) {
        if (options.logLevel == AppSynkOptions.LogLevel.VERBOSE)
            android.util.Log.v("AppSynk:Network", msg)
    }
}

// ── HMAC request signer ──────────────────────────────────────────────────────────

/**
 * Optional HMAC-SHA256 request signing (`javax.crypto.Mac`). Byte-for-byte identical to the iOS
 * scheme so a future backend validation covers both platforms:
 *   canonical = "{timestampMs}\n{base64(sha256(payload))}"  (empty body → empty content hash)
 *   signature = base64(HMAC-SHA256(canonical, secretKey))
 *
 * Disabled by default (the backend does not yet verify the signature; the headers are harmless if
 * present). Uses `android.util.Base64.NO_WRAP` — equivalent to iOS `base64EncodedString()`.
 */
object RequestSigner {
    fun sign(payload: ByteArray, timestampMs: Long, secret: String, keyId: String): Map<String, String> {
        val contentHash = if (payload.isEmpty()) ""
        else Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(payload), Base64.NO_WRAP)
        val canonical = "$timestampMs\n$contentHash"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return mapOf(
            "Authorization" to "HMAC",
            "x-Key-Id" to keyId,
            "x-Timestamp" to timestampMs.toString(),
            "x-Signature" to signature
        )
    }
}

// ── Retry policy ─────────────────────────────────────────────────────────────────

/**
 * Exponential backoff. Retries transient failures only: HTTP 429 / 5xx and connection-level
 * `IOException`s (SocketTimeout / UnknownHost / Connect …). Permanent client errors
 * (400/401/402/403) are never retried.
 */
class RetryPolicy(val maxRetries: Int, private val baseDelayMs: Long) {
    companion object {
        /** 4 retries after the initial attempt; backoff 2s, 4s, 8s, 16s. */
        val DEFAULT = RetryPolicy(maxRetries = 4, baseDelayMs = 2_000L)
    }

    /** Backoff before retry `n` (1-based): baseDelay · 2^(n-1) → 2s, 4s, 8s, 16s. */
    fun delayMs(retry: Int): Long = baseDelayMs shl (retry - 1).coerceAtLeast(0)

    fun shouldRetry(statusCode: Int): Boolean = statusCode == 429 || statusCode in 500..599

    /** Transient network failures are all `IOException` subtypes (timeout / DNS / connect / reset). */
    fun shouldRetry(error: Throwable): Boolean = error is IOException
}

// ── Errors ───────────────────────────────────────────────────────────────────────

/** Typed network errors mirroring the iOS `NetworkError`, with the permanent/transient split. */
sealed class NetworkError(message: String) : Exception(message) {
    object InvalidResponse : NetworkError("Invalid server response.")
    class BadRequest(val body: String) : NetworkError("Bad request: $body")
    object Unauthorized : NetworkError("Invalid API key.")
    object PaymentRequired : NetworkError("Quota exhausted (402).")
    object Forbidden : NetworkError("App inactive — data collection paused (403).")
    object RateLimited : NetworkError("Rate limit exceeded. Events will be retried.")
    class ServerError(val statusCode: Int) : NetworkError("Server error $statusCode.")

    /** Permanent client errors must not be retried (the queue drops the batch instead of re-queuing). */
    val isPermanentClientError: Boolean
        get() = when (this) {
            is BadRequest, Unauthorized, PaymentRequired, Forbidden -> true
            InvalidResponse, RateLimited, is ServerError -> false
        }
}

// ── Wire payloads / responses ──────────────────────────────────────────────────────

private data class BatchPayload(
    @SerializedName("events") val events: List<AppSynkEvent>
)

/** GET /v1/ping response (extra fields like `timestamp` are ignored). */
data class PingResponse(
    @SerializedName("status") val status: String,
    @SerializedName("environment") val environment: String
)

/** GET /v1/sdk/init response (subset; `appDbId` is ignored). */
data class SdkInitResponse(
    @SerializedName("appId") val appId: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("environment") val environment: String,
    @SerializedName("plan") val plan: String,
    @SerializedName("isActive") val isActive: Boolean
)

/**
 * Wire shape of GET /v1/links/{linkId}/attribution, mapped to the SDK's [AttributionData].
 * Backend keys differ from the model (adSet/creative/networkClickId/matchType/isAttributed).
 */
private data class LinkAttributionResponse(
    @SerializedName("channel") val channel: String?,
    @SerializedName("campaignName") val campaignName: String?,
    @SerializedName("adSet") val adSet: String?,
    @SerializedName("creative") val creative: String?,
    @SerializedName("matchType") val matchType: String?,
    @SerializedName("confidenceScore") val confidenceScore: Double?,
    @SerializedName("networkClickId") val networkClickId: String?,
    @SerializedName("clickTimestamp") val clickTimestamp: String?,
    @SerializedName("deepLink") val deepLink: String?,
    @SerializedName("isAttributed") val isAttributed: Boolean?
) {
    fun toAttributionData(): AttributionData = AttributionData(
        channel = channel,
        campaignName = campaignName,
        adSetName = adSet,
        creativeName = creative,
        medium = null,
        source = channel,
        clickId = networkClickId,
        clickTimestamp = clickTimestamp?.let { parseIsoUtc(it) },
        isOrganic = !(isAttributed ?: false),
        attributionModel = matchType,
        confidenceScore = confidenceScore,
        deepLink = deepLink
    )
}

/**
 * A fresh UTC-locked ISO-8601 formatter per call. `SimpleDateFormat` is not thread-safe, so a new
 * instance is created for each (de)serialization rather than shared across the adapter lambdas.
 */
private fun utcDateFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

/** Parse an ISO-8601 UTC timestamp, tolerating both millisecond and second precision. */
private fun parseIsoUtc(value: String): Date? =
    listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { pattern ->
        runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)
        }.getOrNull()
    }
