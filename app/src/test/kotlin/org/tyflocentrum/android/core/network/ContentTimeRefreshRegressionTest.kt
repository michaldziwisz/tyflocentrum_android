package net.tyflopodcast.tyflocentrum.core.network

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import net.tyflopodcast.tyflocentrum.core.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

class ContentTimeRefreshRegressionTest {
    private val now=Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
    private fun post(seconds: Int) = WpPostSummary(7,"2026-10-08",WpRenderedText("Stały podcast"),link="x",
        tyflocentrum=Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":$seconds}"""))

    @Test fun refreshingDoesNotBlankGoodVisibleTimeBeforeResponse() {
        val store=ContentTimeStore(TimeTransport { _, _ -> error("Nie wywołano transportu") },clock={now})
        val post=post(61); val request=post.timeRequest(ContentKind.PODCAST)
        store.acceptPodcasts(listOf(post))
        assertEquals(61L,store.value(request)?.amount)
        store.invalidate(TimeSource.PODCAST)
        assertEquals("Odświeżanie nie jest wycofaniem metadanych",61L,store.value(request)?.amount)
    }

    @Test fun olderInlineResponseAfterManualRefreshCannotRestoreOldTime() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val calls=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val first=calls.incrementAndGet()==1
            if(first) { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
            val body="""[{"id":7,"date":"2026-10-08","modified_gmt":"2026-10-07T00:00:00","title":{"rendered":"Stały podcast"},"link":"x","tyflocentrum":{"schema_version":1,"audio_status":"ready","duration_seconds":${if(first) 61 else 122},"generated_at":"2026-10-09T11:00:00Z"}}]"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun retrofit()=Retrofit.Builder().baseUrl("https://example.test/").client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
        val store=ContentTimeStore(TimeTransport { _, _ -> error("Inline, bez drugiego pobrania") },clock={now})
        val repo=TyfloRepository(retrofit().create(),retrofit().create(),retrofit().create(),client,store)
        val old=async(Dispatchers.Default) { repo.fetchPodcastSummariesPage(2,20) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
            val fresh=repo.fetchPodcastSummariesPage(1,20,pomijCache=true).items.single()
            val request=fresh.timeRequest(ContentKind.PODCAST)
            assertEquals(122L,store.value(request)?.amount)
            release.countDown(); old.await()
            assertEquals("Spóźniona paginacja nie wygrywa z odświeżeniem",122L,store.value(request)?.amount)
            assertEquals(2,calls.get())
        } finally { release.countDown(); old.cancelAndJoin() }
    }
}
