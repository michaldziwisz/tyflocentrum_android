package net.tyflopodcast.tyflocentrum.core.network

import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.tyflopodcast.tyflocentrum.core.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

class ContentTimeWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val urls = Collections.synchronizedList(mutableListOf<HttpUrl>())
    private var status = 200
    private var body = "[]"
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        urls += chain.request().url
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun wp() = Retrofit.Builder().baseUrl("https://example.test/wp-json/").client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create<WpApiService>()
    private fun repo(store: ContentTimeStore? = null) = TyfloRepository(wp(),wp(),Retrofit.Builder().baseUrl("https://example.test/").client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(),client,store)

    @Test fun realListsRequestOptionalMetadataWithoutFullTextOrAudio() = runBlocking {
        body = """[{"id":7,"date":"2026-10-08","title":{"rendered":"Treść"},"link":"https://example.test/7","tyflocentrum":false}]"""
        val repository = repo()
        assertEquals(7,repository.fetchPodcastSummariesPage(1,20).items.single().id)
        assertEquals(7,repository.fetchArticleSummariesPage(1,20,8).items.single().id)
        assertEquals(7,repository.fetchPodcastSearchSummaries("szukaj").single().id)
        assertEquals(7,repository.fetchArticleSearchSummaries("szukaj").single().id)
        assertEquals(7,repository.fetchTyfloswiatPageSummaries(1409).single().id)
        assertEquals(5,urls.size)
        urls.forEach { url ->
            val fields = url.queryParameter("_fields")!!.split(',')
            assertTrue(url.toString(), "tyflocentrum" in fields)
            assertTrue(url.toString(), "modified_gmt" in fields)
            assertFalse(url.toString(),"content" in fields)
            assertTrue(url.encodedPath in listOf("/wp-json/wp/v2/posts","/wp-json/wp/v2/pages"))
        }
        assertTrue(repository.getListenableUrl(7).contains("id=7"))
        assertEquals(5,urls.size) // Wyznaczenie drogi odtwarzania nie pobiera nagrania.
        println("LIST_URL_LEDGER=" + urls.joinToString("\n"))
    }
    @Test fun optionalTransport503429MalformedAndMissingAreNegativeCached() = runBlocking {
        for ((code,payload) in listOf(503 to "{}",429 to "{}",200 to "false",200 to "{",200 to "{}")) {
            urls.clear(); status=code; body=payload
            val store = ContentTimeStore(RetrofitTimeTransport.create(client,json), clock={now})
            val request = TimeRequest(TimeKey(TimeSource.ARTICLE_POST,7))
            repeat(4) { store.load(listOf(request)) }
            assertNull(store.value(request))
            assertEquals(1,urls.size)
            assertEquals("tyflocentrum.tyflo.eu.org",urls.single().host)
            assertEquals("/v1/metadata",urls.single().encodedPath)
            assertEquals("7",urls.single().queryParameter("ids"))
        }
    }
    @Test fun podcastFavoritesUseMetadataOnlyBatchAndFractionalSeconds() = runBlocking {
        body="""[{"id":8,"tyflocentrum":{"schema_version":1,"audio_status":"ready","duration_seconds":2.5}},{"id":7,"tyflocentrum":[]} ]"""
        val store=ContentTimeStore(RetrofitTimeTransport.create(client,json),clock={now})
        val requests=listOf(7,8).map { TimeRequest(TimeKey(TimeSource.PODCAST,it)) }
        store.load(requests)
        assertEquals(3L,store.value(requests[1])?.amount)
        assertNull(store.value(requests[0]))
        assertEquals("7,8",urls.single().queryParameter("include"))
        assertEquals("id,modified_gmt,tyflocentrum",urls.single().queryParameter("_fields"))
        assertEquals("tyflopodcast.net",urls.single().host)
        println("FAVORITE_URL_LEDGER=" + urls.single())
    }
    @Test fun repositorySeedsInlinePodcastTimeAndKeepsEveryScreenCacheStable() = runBlocking {
        val store=ContentTimeStore(RetrofitTimeTransport.create(client,json),clock={now})
        val repository=repo(store)
        body="""[{"id":7,"date":"2026-10-08","title":{"rendered":"Audycja"},"link":"https://example.test/7","tyflocentrum":{"schema_version":1,"audio_status":"ready","duration_seconds":60.1}}]"""
        val post=repository.fetchPodcastSummariesPage(1,20).items.single()
        store.load(listOf(post.timeRequest(ContentKind.PODCAST)))
        assertEquals(1,urls.size)
        assertEquals(61L,store.value(post.timeRequest(ContentKind.PODCAST))?.amount)
        val paged=PagedScreenCache(listOf(post),2,5)
        repository.storePodcastListScreenCache(3,paged)
        repository.storeArticleListScreenCache(null,paged)
        val news=NewsScreenCache(listOf(NewsItem(ContentKind.PODCAST,post)),2,3,5,6)
        repository.storeNewsScreenCache(news)
        val issue=MagazineIssueScreenCache(post.toDetailStub(),listOf(post),"https://example.test/issue.pdf")
        repository.storeMagazineIssueScreenCache(8,issue)
        repository.storeMagazineScreenCache(listOf(post))
        store.invalidate(TimeSource.PODCAST)
        assertSame(paged,repository.peekPodcastListScreenCache(3))
        assertSame(paged,repository.peekArticleListScreenCache(null))
        assertSame(news,repository.peekNewsScreenCache())
        assertSame(issue,repository.peekMagazineIssueScreenCache(8))
        assertEquals(listOf(post),repository.peekMagazineScreenCache())
        assertEquals("PODCAST.7",repository.peekNewsScreenCache()!!.items.single().uniqueId)
    }

    @Test fun blockedMetadataDoesNotBlockSourceListDetailOrPlaybackRoute() = runBlocking {
        val gate=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        val store=ContentTimeStore(TimeTransport { _, _ -> entered.complete(Unit); gate.await(); JsonNull },clock={now})
        val pending=launch { store.load(listOf(TimeRequest(TimeKey(TimeSource.ARTICLE_POST,7)))) }
        entered.await()
        body="""[{"id":7,"date":"2026-10-08","title":{"rendered":"Treść"},"link":"https://example.test/7"}]"""
        val repository=repo()
        assertEquals(7,withTimeout(1_000) { repository.fetchArticleSummariesPage(1,20) }.items.single().id)
        assertFalse(pending.isCompleted)
        body="""{"id":7,"date":"2026-10-08","title":{"rendered":"Treść"},"excerpt":{"rendered":""},"content":{"rendered":"Czytelny artykuł"},"guid":{"rendered":"https://example.test/7"}}"""
        assertEquals("Czytelny artykuł",withTimeout(1_000) { repository.fetchArticleDetail(7) }.content.rendered)
        assertTrue(repository.getListenableUrl(7).contains("id=7"))
        pending.cancelAndJoin()
    }
}
