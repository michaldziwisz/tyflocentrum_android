package net.tyflopodcast.tyflocentrum.core.network

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import net.tyflopodcast.tyflocentrum.core.model.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentTimeStoreTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private fun request(id: Int, type: TimeSource = TimeSource.ARTICLE_POST) = TimeRequest(TimeKey(type, id))
    private fun batch(type: TimeSource, ids: List<Int>, minutes: Int = 2): JsonElement = Json.parseToJsonElement("""{"schema_version":1,"source":"${type.source}","type":"${type.type}","items":[${ids.joinToString(",") { """{"id":$it,"freshness":"fresh","checked_at":"2026-10-08T11:00:00Z","modified_gmt":"2026-10-07T00:00:00","tyflocentrum":{"schema_version":1,"text_status":"ready","word_count":${minutes * 200},"reading_minutes":$minutes}}""" }}]}""")

    @Test fun batches50DeduplicatesAndSeparatesPostsPagesAndResponseOrder() = runTest {
        val calls = mutableListOf<Pair<TimeSource,List<Int>>>()
        val store = ContentTimeStore(TimeTransport { type, ids -> calls += type to ids; batch(type, ids.reversed()) }, clock = { now })
        val input = (1..103).map { request(it) } + request(1) + request(0) + request(-1) + request(1, TimeSource.ARTICLE_PAGE)
        store.load(input)
        assertEquals(listOf(50,50,3,1), calls.map { it.second.size })
        assertEquals(104, calls.sumOf { it.second.size })
        input.filter { it.key.id > 0 }.forEach { assertEquals(2L, store.value(it)?.amount) }
        store.load(input)
        assertEquals(4,calls.size)
    }
    @Test fun foreignIdsDuplicateIdsAndWrongEnvelopeAreNotTrusted() = runTest {
        val store = ContentTimeStore(TimeTransport { type, _ -> batch(type,listOf(1,1,999)) },clock={now})
        store.load(listOf(request(1),request(2)))
        assertNull(store.value(request(1)))
        assertNull(store.value(request(2)))
        assertFalse(store.state.value.containsKey(request(999).key))
        val wrong = ContentTimeStore(TimeTransport { _, _ -> batch(TimeSource.ARTICLE_PAGE,listOf(1)) },clock={now})
        wrong.load(listOf(request(1)))
        assertNull(wrong.value(request(1)))
    }
    @Test fun failureIsNegativeCachedAndDoesNotRetryUntilNextExplicitLoadAfterCooldown() = runTest {
        var time=now; var calls=0
        val store=ContentTimeStore(TimeTransport { _, _ -> calls++; throw IOException("503") },clock={time})
        repeat(50) { store.load(listOf(request(1))) }
        assertEquals(1,calls)
        assertNull(store.value(request(1)))
        time+=60_001
        store.load(listOf(request(1)))
        assertEquals(2,calls)
    }
    @Test fun ownTimeoutIsUnavailableButParentCancellationPropagates() = runTest {
        var calls=0
        val store=ContentTimeStore(TimeTransport { _, _ -> calls++; delay(99_000); JsonNull },clock={now})
        store.load(listOf(request(1)))
        assertNull(store.value(request(1)))
        assertEquals(2_500L,testScheduler.currentTime)
        val result=runCatching { withTimeout(100) { store.load(listOf(request(2))) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(store.state.value.containsKey(request(2).key))
        assertEquals(2,calls)
    }
    @Test fun overlappingRequestsShareFlightAndCancellationAllowsLaterRetry() = runTest {
        var calls=0
        val gate=CompletableDeferred<Unit>()
        val store=ContentTimeStore(TimeTransport { type, ids -> calls++; gate.await(); batch(type,ids) },clock={now})
        val first=launch { store.load(listOf(request(1))) }; runCurrent()
        store.load(listOf(request(1)))
        assertEquals(1,calls)
        first.cancelAndJoin()
        gate.complete(Unit)
        store.load(listOf(request(1)))
        assertEquals(2,calls)
        assertEquals(2L,store.value(request(1))?.amount)
    }
    @Test fun lateCallbackBeforeRefreshCannotOverwriteNewValue() = runTest {
        val gate=CompletableDeferred<Unit>(); var calls=0
        val store=ContentTimeStore(TimeTransport { type, ids ->
            calls++
            if(calls==1) { withContext(NonCancellable) { gate.await() }; batch(type,ids,1) } else batch(type,ids,3)
        },clock={now})
        val old=launch { store.load(listOf(request(1))) }; runCurrent()
        store.invalidate(TimeSource.ARTICLE_POST)
        store.load(listOf(request(1)))
        gate.complete(Unit); old.join()
        assertEquals(3L,store.value(request(1))?.amount)
    }
    @Test fun ttlExpiresInMemoryAndNewerSourceInvalidatesCachedValue() = runTest {
        var time=now
        val store=ContentTimeStore(TimeTransport { type, ids -> batch(type,ids) },clock={time})
        store.load(listOf(request(1)))
        assertEquals(2L,store.value(request(1))?.amount)
        assertNull(store.value(request(1).copy(modifiedGmt="2026-10-08T00:00:00")))
        time+=23*60*60*1000L
        assertNull(store.value(request(1)))
    }
    @Test fun refreshedSourceDateHidesTimeEvenForOldStableNewsRow() = runTest {
        val store=ContentTimeStore(TimeTransport { type, ids -> batch(type,ids) },clock={now})
        val old=request(1)
        store.load(listOf(old))
        val updated=WpPostSummary(1,"2026-10-08",WpRenderedText("Tytuł"),link="x",modifiedGmt=JsonPrimitive("2026-10-08T11:00:00"))
        store.observeArticles(TimeSource.ARTICLE_POST,listOf(updated))
        assertNull(store.value(old))
        store.load(listOf(old))
        assertNull(store.value(old))
    }
    @Test fun inlinePodcastMetadataIsNotReFetchedOrRenewedByOldFavorite() = runTest {
        var calls=0; var time=now
        val store=ContentTimeStore(TimeTransport { _, _ -> calls++; JsonNull },clock={time})
        val post=WpPostSummary(1,"2026-10-08",WpRenderedText("Audycja"),link="x",tyflocentrum=Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":2.5}"""))
        val request=post.timeRequest(ContentKind.PODCAST)
        store.acceptPodcasts(listOf(post))
        store.load(listOf(request))
        assertEquals(0,calls)
        assertEquals(3L,store.value(request)?.amount)
        time+=CONTENT_TIME_TTL_MS
        assertNull(store.value(request))
        store.load(listOf(requireNotNull(FavoriteItem.PodcastFavorite(post).timeRequest())))
        assertEquals(1,calls)
        assertNull(store.value(request))
    }
    @Test fun newerSourceDateInPodcastBatchRejectsOldGeneration() = runTest {
        val store=ContentTimeStore(TimeTransport { _, _ -> Json.parseToJsonElement("""[{"id":1,"modified_gmt":"2026-10-08T11:00:00","tyflocentrum":{"schema_version":1,"audio_status":"ready","duration_seconds":60,"generated_at":"2026-10-07T10:00:00Z"}}]""") },clock={now})
        val request=request(1,TimeSource.PODCAST)
        store.load(listOf(request))
        assertNull(store.value(request))
    }
    @Test fun cacheIsBoundedAndUnsupportedNeverBecomesOneMinute() = runTest {
        val store=ContentTimeStore(TimeTransport { _, _ -> JsonNull },clock={now},capacity=10)
        store.load((1..25).map { request(it) })
        assertEquals(10,store.state.value.size)
        assertNull(store.value(request(25)))
    }
}
