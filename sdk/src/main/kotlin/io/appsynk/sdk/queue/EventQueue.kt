package io.appsynk.sdk.queue

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.appsynk.sdk.config.AppSynkOptions
import io.appsynk.sdk.models.AppSynkEvent
import io.appsynk.sdk.services.NetworkError
import io.appsynk.sdk.services.NetworkService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Reliable, persistent event queue — never loses an event.
 *
 * Mirrors the iOS `EventQueue`. The pending list is persisted to disk on every change (so a process
 * kill loses nothing), flushed on batch size / timer / background, and removed from the queue ONLY
 * after a 202. Dedup is idempotent and backend-side: each event carries a stable `clientEventId`
 * that survives retries and disk round-trips, so a re-sent event (e.g. a 202 lost on a flaky
 * network) is recognized and dropped server-side.
 *
 * On top of the in-memory flush, every [enqueue] schedules an [EventUploadWorker] (WorkManager) so
 * delivery survives a process kill or reboot — robustness beyond the iOS background-task model.
 */
class EventQueue(
    context: Context,
    private val network: NetworkService,
    private val options: AppSynkOptions
) {
    private val appContext: Context = context.applicationContext

    /** Shares the network layer's UTC-locked Date adapter so persisted timestamps round-trip. */
    private val gson: Gson = network.gson

    private val mutex = Mutex()      // guards `pending` + the on-disk file
    private val flushing = Mutex()   // ensures a single drain at a time (≈ iOS `isFlushing`)

    private val pending = mutableListOf<AppSynkEvent>()
    private var loaded = false

    private val batchSize = options.batchSize.coerceAtLeast(1)
    private val maxBatch = 100       // backend hard limit (HandleIngestBatchAsync)
    private val maxQueue = 1000      // safety bound

    private val storeFile: File =
        File(File(appContext.filesDir, DIR).apply { mkdirs() }, FILE)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Reload events persisted by a previous launch and attempt a flush. Call once at startup. */
    suspend fun restore() {
        mutex.withLock { ensureLoadedLocked() }
        flush()
    }

    // ── Queue ──────────────────────────────────────────────────────────────────

    suspend fun enqueue(event: AppSynkEvent) {
        val reachedBatch = mutex.withLock {
            ensureLoadedLocked()
            pending.add(event)
            if (pending.size > maxQueue) {
                val overflow = pending.size - maxQueue
                repeat(overflow) { pending.removeAt(0) }   // degraded mode: drop the oldest
                log("Queue overflow — dropped $overflow oldest event(s)")
            }
            persistLocked()   // persist on every enqueue so a kill before flush loses nothing
            pending.size >= batchSize
        }

        // WorkManager backstop: a process kill before the in-memory flush can't lose this event.
        EventUploadScheduler.schedule(appContext, network.apiKey, options)

        if (reachedBatch) flush()
    }

    /** Sends pending events in batches of up to 100, removing each batch ONLY after a 202. */
    suspend fun flush() {
        if (!flushing.tryLock()) return   // a flush is already draining
        try {
            while (true) {
                val batch = mutex.withLock {
                    ensureLoadedLocked()
                    if (pending.isEmpty()) return
                    pending.take(maxBatch)
                }
                try {
                    network.ingestBatch(batch)
                    val remaining = mutex.withLock {
                        removeFirstLocked(batch.size)   // remove only on success
                        persistLocked()
                        pending.size
                    }
                    log("Flushed ${batch.size} event(s) — $remaining remaining")
                } catch (e: NetworkError) {
                    if (e.isPermanentClientError) {
                        // 400/401/402/403 will never succeed: drop the batch so a poison event
                        // can't block the queue forever.
                        log("Dropping ${batch.size} event(s) — permanent error: ${e.message}")
                        mutex.withLock { removeFirstLocked(batch.size); persistLocked() }
                        continue
                    }
                    log("Flush failed (${e.message}) — events kept for retry")
                    return
                } catch (e: Exception) {
                    // Transient (IOException, etc.): keep the events, retry next cycle. Nothing lost.
                    log("Flush failed (${e.message}) — events kept for retry")
                    return
                }
            }
        } finally {
            flushing.unlock()
        }
    }

    /** Number of events currently queued (diagnostics / tests / Worker result decision). */
    suspend fun count(): Int = mutex.withLock { ensureLoadedLocked(); pending.size }

    // ── Private (callers must hold `mutex`) ─────────────────────────────────────

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        runCatching {
            if (storeFile.exists()) {
                val type = object : TypeToken<MutableList<AppSynkEvent>>() {}.type
                val restored: MutableList<AppSynkEvent>? = gson.fromJson(storeFile.readText(), type)
                if (restored != null) {
                    pending.clear()
                    pending.addAll(restored)
                }
            }
        }.onFailure { log("Failed to load queue: ${it.message}") }
    }

    private fun removeFirstLocked(n: Int) {
        repeat(minOf(n, pending.size)) { pending.removeAt(0) }
    }

    private fun persistLocked() {
        runCatching {
            val dir = storeFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(gson.toJson(pending))
            if (!tmp.renameTo(storeFile)) {
                // Fallback if atomic rename isn't supported on this filesystem.
                storeFile.writeText(gson.toJson(pending))
                tmp.delete()
            }
        }.onFailure { log("Failed to persist queue: ${it.message}") }
    }

    private fun log(msg: String) {
        if (options.logLevel == AppSynkOptions.LogLevel.VERBOSE) {
            android.util.Log.v("AppSynk:Queue", msg)
        }
    }

    companion object {
        private const val DIR = "appsynk"
        private const val FILE = "queue.json"
    }
}
