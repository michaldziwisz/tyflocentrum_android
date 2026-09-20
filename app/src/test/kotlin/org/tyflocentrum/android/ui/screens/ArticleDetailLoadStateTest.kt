package net.tyflopodcast.tyflocentrum.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleDetailLoadStateTest {
    @Test
    fun cancelledUncooperativeOperation_doesNotRunSuccessOrFinally() = runTest {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var starts = 0
        var successes = 0
        var failures = 0
        var finalizers = 0

        val job = launch {
            runDetailLoadSafely(
                onStart = {
                    starts += 1
                    started.complete(Unit)
                },
                load = {
                    started.await()
                    withContext(NonCancellable) { released.await() }
                    "loaded"
                },
                onSuccess = {
                    successes += 1
                },
                onFailure = {
                    failures += 1
                },
                onFinally = {
                    finalizers += 1
                }
            )
        }

        started.await()
        job.cancel()
        released.complete(Unit)
        job.join()

        assertEquals(1, starts)
        assertEquals(0, successes)
        assertEquals(0, failures)
        assertEquals(0, finalizers)
    }

    @Test
    fun failedLoadRunsFailureAndFinallyOnce() = runTest {
        var starts = 0
        var failures = 0
        var finalizers = 0
        var seenMessage: String? = null

        runDetailLoadSafely<Unit>(
            onStart = { starts += 1 },
            load = { throw IllegalStateException("boom") },
            onSuccess = { throw AssertionError("success should not run") },
            onFailure = { error ->
                failures += 1
                seenMessage = error.message
            },
            onFinally = { finalizers += 1 }
        )

        assertEquals(1, starts)
        assertEquals(1, failures)
        assertEquals("boom", seenMessage)
        assertEquals(1, finalizers)
    }

    @Test
    fun cancellationPassesThroughToCaller() = runTest {
        val error = runCatching {
            runDetailLoadSafely<Unit>(
                onStart = {},
                load = { throw CancellationException("stop") },
                onSuccess = { throw AssertionError("success should not run") },
                onFailure = { throw AssertionError("failure should not run") },
                onFinally = { throw AssertionError("finally should not run") }
            )
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals("stop", error?.message)
    }

    @Test
    fun concurrentLoads_keepNewerCleanupAndResult() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var currentLabel = "idle"
        var visibleLabel = "initial"
        var finalizerRuns = 0

        suspend fun launchLoad(
            label: String,
            started: CompletableDeferred<Unit>,
            released: CompletableDeferred<Unit>
        ) = async {
            runDetailLoadSafely(
                onStart = {
                    currentLabel = label
                    started.complete(Unit)
                },
                load = {
                    started.await()
                    withContext(NonCancellable) { released.await() }
                    label
                },
                onSuccess = { loaded ->
                    visibleLabel = loaded
                },
                onFailure = { throw AssertionError("failure should not run") },
                onFinally = {
                    finalizerRuns += 1
                    currentLabel = "idle"
                }
            )
        }

        val first = launchLoad("first", firstStarted, releaseFirst)
        firstStarted.await()
        first.cancel()

        val second = launchLoad("second", secondStarted, releaseSecond)
        secondStarted.await()

        releaseFirst.complete(Unit)
        first.cancelAndJoin()
        assertEquals("second", currentLabel)
        assertEquals("initial", visibleLabel)
        assertEquals(0, finalizerRuns)
        releaseSecond.complete(Unit)
        second.await()

        assertEquals("second", visibleLabel)
        assertEquals(1, finalizerRuns)
        assertEquals("idle", currentLabel)
    }
}
