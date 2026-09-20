package net.tyflopodcast.tyflocentrum.core.network

import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.tyflopodcast.tyflocentrum.core.model.Availability
import net.tyflopodcast.tyflocentrum.core.model.RadioSchedule
import net.tyflopodcast.tyflocentrum.core.model.WpPostDetail
import net.tyflopodcast.tyflocentrum.core.model.WpRenderedText
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArticleLoadingRecoveryTest {
    @Test
    fun fetchArticleDetail_transient503Then200_returnsPost() = runBlocking {
        val article = articleDetail(id = 7, contentHtml = "<p>Treść po retry.</p>")
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    if (calls == 1) throw httpException(503)
                    article
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val detail = repository.fetchArticleDetail(7)

        assertEquals(7, detail.id)
        assertEquals("<p>Treść po retry.</p>", detail.content.rendered)
        assertEquals(2, calls)
    }

    @Test
    fun cancellationPassesThroughWithoutRetry() = runBlocking {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw CancellationException("cancelled")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        try {
            repository.fetchArticleDetail(10)
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        assertEquals(1, calls)
    }

    @Test
    fun notFoundIsNotRetried() = runBlocking {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw httpException(404)
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        try {
            repository.fetchArticleDetail(11)
            throw AssertionError("Expected HttpException")
        } catch (error: HttpException) {
            assertEquals(404, error.code())
        }
        assertEquals(1, calls)
    }

    @Test
    fun validResultIsCachedAndRefreshBypassesMemoryCache() = runBlocking {
        var calls = 0
        val first = articleDetail(id = 12, contentHtml = "<p>Pierwsza treść.</p>")
        val second = articleDetail(id = 12, contentHtml = "<p>Druga treść.</p>")
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    if (calls == 1) first else second
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val cached = repository.fetchArticleDetail(12)
        val reused = repository.fetchArticleDetail(12)
        val refreshed = repository.fetchArticleDetail(12, refresh = true)

        assertSame(cached, reused)
        assertEquals("<p>Druga treść.</p>", refreshed.content.rendered)
        assertEquals(2, calls)
    }

    @Test
    fun failedResponseIsNotCached() = runBlocking {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw IllegalArgumentException("bad payload")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        try {
            repository.fetchArticleDetail(13)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertEquals("bad payload", error.message)
        }

        assertEquals(null, repository.peekArticleDetail(13))
        assertEquals(1, calls)
    }

    @Test
    fun manualRefreshSendsNoCacheHeader() = runBlocking {
        val seen = mutableListOf<String?>()
        val articleApi = fakeWpApiService { methodName, args ->
            when (methodName) {
                "getPostDetail" -> {
                    seen += args.getOrNull(2) as String?
                    articleDetail(id = 21, contentHtml = "<p>Treść.</p>")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        repository.fetchArticleDetail(21)
        repository.fetchArticleDetail(21, refresh = true)

        assertEquals(listOf(null, "no-cache"), seen)
    }

    @Test
    fun mismatchedIdIsRejectedWithoutCachingOrRetry() = runBlocking {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    articleDetail(id = 999, contentHtml = "<p>Błędny identyfikator.</p>")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val error = runCatching {
            repository.fetchArticleDetail(22)
        }.exceptionOrNull()

        assertEquals("java.lang.IllegalStateException", error?.javaClass?.name)
        assertEquals("Serwer zwrócił treść o innym identyfikatorze.", error?.message)
        assertEquals(1, calls)
        assertEquals(null, repository.peekArticleDetail(22))
    }

    @Test
    fun retryAfterHeaderIsHonoredForTransientHttpError() = runBlocking {
        val article = articleDetail(id = 23, contentHtml = "<p>Po Retry-After.</p>")
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    if (calls == 1) throw httpException(503, retryAfter = "0")
                    article
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val detail = repository.fetchArticleDetail(23)

        assertEquals(23, detail.id)
        assertEquals(2, calls)
    }

    @Test
    fun retryAfterHttpDateIsParsed() = runBlocking {
        val article = articleDetail(id = 24, contentHtml = "<p>Po dacie Retry-After.</p>")
        var calls = 0
        val retryAfterDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneOffset.UTC)
        )
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    if (calls == 1) throw httpException(503, retryAfter = retryAfterDate)
                    article
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val detail = repository.fetchArticleDetail(24)

        assertEquals(24, detail.id)
        assertEquals(2, calls)
    }

    @Test
    fun retryAfterBeyondBudgetFailsWithoutEarlyRetry() = runBlocking {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw httpException(503, retryAfter = "999999999999999999999999999999999999999999999999999999")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val error = runCatching {
            repository.fetchArticleDetail(25)
        }.exceptionOrNull()

        assertEquals("retrofit2.HttpException", error?.javaClass?.name)
        assertEquals(1, calls)
        assertEquals(null, repository.peekArticleDetail(25))
    }

    @Test
    fun sslPeerUnverifiedExceptionIsNotRetriedOrCached() = runBlocking {
        var calls = 0
        val repository = repository(articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw SSLPeerUnverifiedException("peer rejected")
                }
                else -> unsupported(methodName)
            }
        })

        val error = runCatching {
            repository.fetchArticleDetail(26)
        }.exceptionOrNull()

        assertEquals("javax.net.ssl.SSLPeerUnverifiedException", error?.javaClass?.name)
        assertEquals("peer rejected", error?.message)
        assertEquals(1, calls)
        assertEquals(null, repository.peekArticleDetail(26))
    }

    @Test
    fun sslHandshakeCertificateExceptionIsNotRetriedOrCached() = runBlocking {
        var calls = 0
        val certificateError = CertificateException("bad cert")
        val handshakeError = SSLHandshakeException("cert rejected").apply {
            initCause(certificateError)
        }
        val repository = repository(articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    throw handshakeError
                }
                else -> unsupported(methodName)
            }
        })

        val error = runCatching {
            repository.fetchArticleDetail(27)
        }.exceptionOrNull()
        val causeChain = generateSequence(error) { it.cause }.take(16).toList()

        assertEquals("javax.net.ssl.SSLHandshakeException", error?.javaClass?.name)
        assertEquals("cert rejected", error?.message)
        assertEquals(true, causeChain.any { it === handshakeError })
        assertEquals(true, causeChain.any { it === certificateError })
        assertEquals(1, calls)
        assertEquals(null, repository.peekArticleDetail(27))
    }

    @Test
    fun timedOutAttemptRetriesAsSocketTimeoutException() = runTest {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    delay(13_000)
                    articleDetail(id = 28, contentHtml = "<p>Za późno.</p>")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val deferred = async {
            runCatching { repository.fetchArticleDetail(28) }.exceptionOrNull()
        }
        advanceTimeBy(24_100)
        advanceUntilIdle()
        val error = deferred.await()

        assertEquals("java.net.SocketTimeoutException", error?.javaClass?.name)
        assertEquals(2, calls)
        assertEquals(null, repository.peekArticleDetail(28))
    }

    @Test
    fun overallTimeoutBecomesSocketTimeoutInsteadOfQuietCancellation() = runTest {
        var calls = 0
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    delay(10_000)
                    throw httpException(503, retryAfter = "10")
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val deferred = async {
            runCatching { repository.fetchArticleDetail(29) }.exceptionOrNull()
        }
        advanceTimeBy(30_100)
        advanceUntilIdle()
        val error = deferred.await()

        assertEquals("java.net.SocketTimeoutException", error?.javaClass?.name)
        assertEquals(2, calls)
        assertEquals(30_100L, testScheduler.currentTime)
        assertEquals(null, repository.peekArticleDetail(29))
    }
    @Test
    fun callerTimeoutRemainsCancellation() = runTest {
        var calls = 0
        val repository = repository(articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    delay(20_000)
                    articleDetail(id = 30, contentHtml = "<p>Spóźniona treść.</p>")
                }
                else -> unsupported(methodName)
            }
        })
        val error = runCatching {
            kotlinx.coroutines.withTimeout(100) { repository.fetchArticleDetail(30) }
        }.exceptionOrNull()
        assertEquals("kotlinx.coroutines.TimeoutCancellationException", error?.javaClass?.name)
        assertEquals(1, calls)
        assertEquals(null, repository.peekArticleDetail(30))
    }

    @Test
    fun emptyContentIsValidAndCacheable() = runBlocking {
        val article = articleDetail(id = 14, contentHtml = "")
        val repository = repository(articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> article
                else -> unsupported(methodName)
            }
        })

        val detail = repository.fetchArticleDetail(14)

        assertEquals("", detail.content.rendered)
        assertSame(detail, repository.peekArticleDetail(14))
    }

    @Test
    fun newerRefreshPreventsOlderResponseFromOverwritingCache() = runTest {
        var calls = 0
        val first = articleDetail(id = 15, contentHtml = "<p>Stara odpowiedź.</p>")
        val second = articleDetail(id = 15, contentHtml = "<p>Nowa odpowiedź.</p>")
        val articleApi = fakeWpApiService { methodName, _ ->
            when (methodName) {
                "getPostDetail" -> {
                    calls += 1
                    when (calls) {
                        1 -> {
                            delay(100)
                            first
                        }
                        else -> second
                    }
                }
                else -> unsupported(methodName)
            }
        }
        val repository = repository(articleApi = articleApi)

        val firstLoad = async { repository.fetchArticleDetail(15) }
        delay(10)
        val refreshed = async { repository.fetchArticleDetail(15, refresh = true) }

        val refreshedResult = refreshed.await()
        val firstResult = firstLoad.await()

        assertEquals("<p>Nowa odpowiedź.</p>", refreshedResult.content.rendered)
        assertEquals("<p>Stara odpowiedź.</p>", firstResult.content.rendered)
        assertEquals("<p>Nowa odpowiedź.</p>", repository.peekArticleDetail(15)?.content?.rendered)
        assertEquals(2, calls)
    }

    private fun repository(articleApi: WpApiService): TyfloRepository {
        return TyfloRepository(
            podcastApi = fakeWpApiService { methodName, _ -> unsupported(methodName) },
            articleApi = articleApi,
            contactApi = object : ContactApiService {
                override suspend fun getAvailability(action: String): Availability = error("unused")
                override suspend fun getSchedule(action: String): RadioSchedule = error("unused")
                override suspend fun sendContact(action: String, payload: ContactPayload): ContactResponse = error("unused")
                override suspend fun sendVoiceContact(
                    action: String,
                    author: RequestBody,
                    durationMs: RequestBody,
                    audio: MultipartBody.Part
                ): VoiceContactResponse = error("unused")
            },
            httpClient = OkHttpClient()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeWpApiService(handler: suspend (methodName: String, args: Array<out Any?>) -> Any?): WpApiService {
        return Proxy.newProxyInstance(
            WpApiService::class.java.classLoader,
            arrayOf(WpApiService::class.java)
        ) { _, method, rawArgs ->
            val args = rawArgs ?: emptyArray()
            when (method.name) {
                "equals" -> false
                "hashCode" -> 1
                "toString" -> "FakeWpApiService"
                else -> {
                    val continuation = args.last() as Continuation<Any?>
                    val call: suspend () -> Any? = { handler(method.name, args) }
                    call.startCoroutine(continuation)
                    COROUTINE_SUSPENDED
                }
            }
        } as WpApiService
    }

    private fun httpException(code: Int, retryAfter: String? = null): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.test/error").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .headers(Headers.Builder().apply {
                retryAfter?.let { add("Retry-After", it) }
            }.build())
            .build()
        return HttpException(
            Response.error<WpPostDetail>(
                "error".toResponseBody("text/plain".toMediaType()),
                raw
            )
        )
    }

    private fun articleDetail(id: Int, contentHtml: String): WpPostDetail {
        return WpPostDetail(
            id = id,
            date = "2026-09-20T10:15:30",
            title = WpRenderedText("Artykuł $id"),
            excerpt = WpRenderedText("Zajawka $id"),
            content = WpRenderedText(contentHtml),
            guid = WpRenderedText("https://example.test/$id")
        )
    }

    private fun unsupported(methodName: String): Nothing {
        throw UnsupportedOperationException("Nieobsłużone w teście: $methodName")
    }
}
