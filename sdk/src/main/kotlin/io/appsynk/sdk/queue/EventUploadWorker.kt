package io.appsynk.sdk.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.services.NetworkService
import java.util.concurrent.TimeUnit

/**
 * Delivers persisted events after the SDK process is killed.
 *
 * Reads the network config from its input data, reconstructs a [NetworkService] + [EventQueue], and
 * drains `queue.json`. WorkManager persists the request and replays it — with a `CONNECTED` network
 * constraint and exponential backoff — across process kills and reboots, so an event enqueued just
 * before the app dies is still delivered. Idempotency is preserved by each event's `clientEventId`.
 */
class EventUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val apiKey = inputData.getString(KEY_API_KEY) ?: return Result.failure()
        val options = AppSynkOptions(
            logLevel = enumOr(inputData.getString(KEY_LOG_LEVEL), AppSynkOptions.LogLevel.NONE),
            environment = enumOr(inputData.getString(KEY_ENVIRONMENT), AppSynkOptions.Environment.PRODUCTION),
            customApiUrl = inputData.getString(KEY_CUSTOM_URL),
            batchSize = inputData.getInt(KEY_BATCH_SIZE, 10),
            hmacSecretKey = inputData.getString(KEY_HMAC_SECRET),
            hmacKeyId = inputData.getString(KEY_HMAC_KEY_ID)
        )

        val network = NetworkService(apiKey, options)
        val queue = EventQueue(applicationContext, network, options)

        return try {
            queue.flush()
            // Events remain only on a transient failure → ask WorkManager to retry with backoff.
            if (queue.count() == 0) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_CUSTOM_URL = "customApiUrl"
        const val KEY_HMAC_SECRET = "hmacSecret"
        const val KEY_HMAC_KEY_ID = "hmacKeyId"
        const val KEY_BATCH_SIZE = "batchSize"
        const val KEY_LOG_LEVEL = "logLevel"

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }
}

/**
 * Schedules the network-constrained, backed-off upload that survives process death. Called on every
 * enqueue (and can be called when the app backgrounds). Uses a unique work name with
 * [ExistingWorkPolicy.KEEP] so pending uploads don't pile up — one drains the whole queue.
 */
object EventUploadScheduler {
    private const val WORK_NAME = "appsynk_event_upload"

    fun schedule(context: Context, apiKey: String, options: AppSynkOptions) {
        val data = Data.Builder()
            .putString(EventUploadWorker.KEY_API_KEY, apiKey)
            .putString(EventUploadWorker.KEY_ENVIRONMENT, options.environment.name)
            .putString(EventUploadWorker.KEY_LOG_LEVEL, options.logLevel.name)
            .putInt(EventUploadWorker.KEY_BATCH_SIZE, options.batchSize)
            .apply {
                options.customApiUrl?.let { putString(EventUploadWorker.KEY_CUSTOM_URL, it) }
                options.hmacSecretKey?.let { putString(EventUploadWorker.KEY_HMAC_SECRET, it) }
                options.hmacKeyId?.let { putString(EventUploadWorker.KEY_HMAC_KEY_ID, it) }
            }
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<EventUploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
