package dev.russiankeyboard.prototype

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One worker, at most one waiting task, and no stale callbacks after invalidation. */
class LatestRequestQueue(private val post: (() -> Unit) -> Unit) : Closeable {
    private val revision = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), { runnable -> Thread(runnable, "keyboard-analysis").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private var pending: Future<*>? = null

    @Synchronized fun invalidate() {
        revision.incrementAndGet()
        pending?.cancel(true)
        executor.queue.clear()
        pending = null
    }
    @Synchronized fun <T> submit(work: () -> T, apply: (T) -> Unit, failed: () -> Unit = {}, coalesce: Boolean = false) {
        if (executor.isShutdown) return
        if (coalesce) {
            // Keep running inference useful while replacing only waiting work.
            // cancel(false) does not interrupt a running task's body/callback.
            pending?.cancel(false)
            executor.queue.clear()
        } else invalidate()
        val requested = revision.get()
        pending = executor.submit {
            try {
                val result = work()
                if (revision.get() == requested && !Thread.currentThread().isInterrupted) post {
                    if (revision.get() == requested && !executor.isShutdown) apply(result)
                }
            } catch (_: CancellationException) {
                // A more recent editor revision owns the result.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                post { if (revision.get() == requested && !executor.isShutdown) failed() }
            }
        }
    }
    @Synchronized fun close(after: () -> Unit) {
        if (executor.isShutdown) return
        invalidate()
        executor.execute(after)
        executor.shutdown()
    }
    override fun close() { close {} }
}
