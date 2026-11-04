package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatestRequestQueueTest {
    @Test fun continuousInputPreservesRunningResultAndCoalescesWaitingWork() {
        val posts = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val applied = mutableListOf<Int>()
        LatestRequestQueue { posts.add(it) }.use { queue ->
            queue.submit({ started.countDown(); release.await(3, TimeUnit.SECONDS); 0 }, { applied.add(it) }, coalesce = true)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            for (i in 1..100) queue.submit({ i }, { applied.add(it) }, coalesce = true)
            release.countDown()
            requireNotNull(posts.poll(3, TimeUnit.SECONDS))()
            requireNotNull(posts.poll(3, TimeUnit.SECONDS))()
            assertEquals(listOf(0, 100), applied)
        }
    }
    @Test fun aCallbackAlreadyPostedToUiCannotApplyAfterNewInput() {
        val posts = LinkedBlockingQueue<() -> Unit>()
        val applied = mutableListOf<String>()
        LatestRequestQueue { posts.add(it) }.use { queue ->
            queue.submit({ "old" }, { applied.add(it) })
            val old = requireNotNull(posts.poll(3, TimeUnit.SECONDS))
            queue.submit({ "new" }, { applied.add(it) })
            val latest = requireNotNull(posts.poll(3, TimeUnit.SECONDS))
            old(); latest()
            assertEquals(listOf("new"), applied)
        }
    }

    @Test fun slowObsoleteWorkCannotRunAlongsideNewWorkOrApplyAfterClose() {
        val posts = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val applied = mutableListOf<Int>()
        val queue = LatestRequestQueue { posts.add(it) }
        try {
            queue.submit({
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                started.countDown()
                try {
                    while (release.count > 0) {
                        try { release.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                    }
                } finally { active.decrementAndGet() }
                -1
            }, { applied.add(it) })
            assertTrue(started.await(3, TimeUnit.SECONDS))
            for (i in 1..100) queue.submit({
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try { i } finally { active.decrementAndGet() }
            }, { applied.add(it) })
            release.countDown()
            val latest = requireNotNull(posts.poll(3, TimeUnit.SECONDS))
            latest()
            assertEquals(1, maximum.get())
            assertEquals(listOf(100), applied)
            queue.submit({ 101 }, { applied.add(it) })
            val pending = requireNotNull(posts.poll(3, TimeUnit.SECONDS))
            queue.close(); pending()
            assertEquals(listOf(100), applied)
        } finally { release.countDown(); queue.close() }
    }
}
