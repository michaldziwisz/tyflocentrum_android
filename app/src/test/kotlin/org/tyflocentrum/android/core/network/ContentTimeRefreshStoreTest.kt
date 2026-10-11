package net.tyflopodcast.tyflocentrum.core.network

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import net.tyflopodcast.tyflocentrum.core.model.*
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ContentTimeRefreshStoreTest {
    private val now=Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
    private fun requests(source: TimeSource, count: Int = 1) = (1..count).map { TimeRequest(TimeKey(source,it),"2026-10-07T00:00:00") }
    private fun response(source: TimeSource, ids: List<Int>, phase: Int): JsonElement {
        val rows=ids.joinToString(",") { id ->
            val metadata=when(phase) {
                0,4 -> "null"
                3 -> "false"
                else -> if(source==TimeSource.PODCAST) """{"schema_version":1,"audio_status":"ready","duration_seconds":${phase*61},"generated_at":"2026-10-09T11:00:00Z"}"""
                    else """{"schema_version":1,"text_status":"ready","word_count":${phase*400},"reading_minutes":${phase*2}}"""
            }
            """{"id":$id,"modified_gmt":"2026-10-07T00:00:00","freshness":"fresh","checked_at":"2026-10-09T11:00:00Z","tyflocentrum":$metadata}"""
        }
        return Json.parseToJsonElement(if(source==TimeSource.PODCAST) "[$rows]" else """{"schema_version":1,"source":"${source.source}","type":"${source.type}","items":[$rows]}""")
    }
    @Test fun manualRefreshBypassesBothCachesForSameKeysAndDates() = runTest {
        for(source in TimeSource.entries) {
            var phase=0; var calls=0
            val store=ContentTimeStore(TimeTransport { type, ids -> calls++; response(type,ids,phase) },clock={now})
            val rows=requests(source)
            store.load(rows); assertNull(store.value(rows.single()))
            for(next in 1..4) {
                phase=next
                store.load(rows)
                assertEquals(next,calls) // Serwer zmieniono jawnie, przed akcją nadal stara wartość.
                store.refresh(rows); store.load(rows)
                assertEquals(next+1,calls)
                val expected=if(next>=3) null else if(source==TimeSource.PODCAST) next*61L else next*2L
                assertEquals(expected,store.value(rows.single())?.amount)
            }
        }
    }
    @Test fun resumeBeforeAgeIsSilentAndAfterAgeUpdatesReadyWithoutNewIds() = runTest {
        for(source in TimeSource.entries) {
            var time=now; var phase=1; var calls=0
            val store=ContentTimeStore(TimeTransport { type, ids -> calls++; response(type,ids,phase) },clock={time})
            val rows=requests(source)
            store.load(rows); phase=2
            time+=119_999; repeat(10) { store.load(rows,revalidate=true) }; assertEquals(1,calls)
            time+=2; store.load(rows,revalidate=true); assertEquals(2,calls)
            assertEquals(if(source==TimeSource.PODCAST) 122L else 4L,store.value(rows.single())?.amount)
        }
    }
    @Test fun transportFailureKeepsGoodValueAndExpiryWithoutRetryLoop() = runTest {
        var time=now; var fail=false; var calls=0
        val store=ContentTimeStore(TimeTransport { type, ids -> calls++; if(fail) throw IOException("offline") else response(type,ids,1) },clock={time})
        val rows=requests(TimeSource.ARTICLE_POST)
        store.load(rows); val expires=store.state.value[rows.single().key]!!.expiresAt
        fail=true; time+=120_001
        store.load(rows,revalidate=true)
        assertEquals(2L,store.value(rows.single())?.amount)
        assertEquals(expires,store.state.value[rows.single().key]!!.expiresAt)
        repeat(10) { store.load(rows,revalidate=true) }; assertEquals(2,calls)
        time+=60_001; store.load(rows,revalidate=true); assertEquals(3,calls)
        time=expires; assertNull(store.value(rows.single()))
    }
    @Test fun retryAfterIsRespectedEvenByManualRefreshAndOtherPages() = runTest {
        for(header in listOf("180","Fri, 09 Oct 2026 12:03:00 GMT")) {
            var time=now; var calls=0
            val store=ContentTimeStore(TimeTransport { _, _ ->
                calls++
                throw HttpException(Response.error<JsonElement>("{}".toResponseBody(),okhttp3.Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://example.test/").build()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429).message("Too Many Requests").headers(Headers.headersOf("Retry-After",header)).build()))
            },clock={time})
            val rows=requests(TimeSource.ARTICLE_PAGE,101)
            store.load(rows); assertEquals(1,calls)
            repeat(10) { store.refresh(rows); store.load(rows) }; assertEquals(1,calls)
            time+=180_001; store.refresh(rows); store.load(rows); assertEquals(2,calls)
            assertNull(store.value(rows.first()))
        }
    }
    @Test fun latestRefreshOwnsAllChunksNotOnlyInFlightKeys() = runTest {
        var phase=1; val oldGate=CompletableDeferred<Unit>(); val ledger=mutableListOf<Pair<Int,List<Int>>>()
        val store=ContentTimeStore(TimeTransport { type, ids ->
            val captured=phase; ledger+=captured to ids
            if(captured==1) withContext(NonCancellable) { oldGate.await() }
            response(type,ids,captured)
        },clock={now})
        val rows=requests(TimeSource.ARTICLE_POST,120)
        val old=launch { store.load(rows) }; runCurrent()
        phase=2; store.refresh(rows); store.load(rows)
        oldGate.complete(Unit); old.join()
        rows.forEach { assertEquals(4L,store.value(it)?.amount) }
        assertEquals(listOf(50),ledger.filter { it.first==1 }.map { it.second.size })
        assertEquals(listOf(50,50,20),ledger.filter { it.first==2 }.map { it.second.size })
    }
    @Test fun cancellationAndRapidRefreshCannotEraseNewGeneration() = runTest {
        var phase=1; val gate=CompletableDeferred<Unit>()
        val store=ContentTimeStore(TimeTransport { type, ids ->
            val captured=phase
            if(captured==1) withContext(NonCancellable) { gate.await() }
            response(type,ids,captured)
        },clock={now})
        val rows=requests(TimeSource.PODCAST)
        val old=launch { store.load(rows) }; runCurrent()
        old.cancel(); phase=2
        repeat(5) { store.refresh(rows) }
        store.load(rows); gate.complete(Unit); old.join()
        assertEquals(122L,store.value(rows.single())?.amount)
        store.load(rows)
        assertEquals(122L,store.value(rows.single())?.amount)
    }
    @Test fun longerThanCacheKeepsEveryScreenRecordWithoutUnboundedGlobalCache() = runTest {
        var calls=0
        val store=ContentTimeStore(TimeTransport { type, ids -> calls++; response(type,ids,1) },clock={now})
        val rows=requests(TimeSource.ARTICLE_POST,620)
        val visible=mutableMapOf<TimeKey,TimeRecord>()
        store.load(rows,onRecords={visible.putAll(it)})
        assertEquals(512,store.state.value.size); assertEquals(620,visible.size); assertEquals(13,calls)
        rows.forEach { assertEquals(2L,store.value(it,visible[it.key],now)?.amount) }
        // Brak automatycznej pętli uzupełniającej wyrzucone rekordy.
        advanceUntilIdle(); assertEquals(13,calls)
        store.load(rows,revalidate=true,visibleRecords=visible,onRecords={visible.putAll(it)})
        assertEquals(13,calls)
    }
    @Test fun oldInlineResponseCannotOverwriteNewerResumeBatchInSameGeneration() = runTest {
        val store=ContentTimeStore(TimeTransport { type, ids -> response(type,ids,2) },clock={now})
        val ticket=store.beginList(TimeSource.PODCAST,false)
        val rows=requests(TimeSource.PODCAST)
        store.load(rows,revalidate=true)
        val old=Json { ignoreUnknownKeys=true }.decodeFromString<WpPostSummary>("""{"id":1,"date":"2026-10-08","title":{"rendered":"Stały podcast"},"link":"x","tyflocentrum":{"schema_version":1,"audio_status":"ready","duration_seconds":61}}""")
        store.finishList(TimeSource.PODCAST,ticket,listOf(old))
        assertEquals(122L,store.value(rows.single())?.amount)
    }
}
