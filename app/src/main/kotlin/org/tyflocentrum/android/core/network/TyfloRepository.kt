package net.tyflopodcast.tyflocentrum.core.network

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import net.tyflopodcast.tyflocentrum.core.model.FavoriteArticleOrigin
import net.tyflopodcast.tyflocentrum.BuildConfig
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import net.tyflopodcast.tyflocentrum.core.StanSwiezosci
import net.tyflopodcast.tyflocentrum.core.model.Availability
import net.tyflopodcast.tyflocentrum.core.model.Category
import net.tyflopodcast.tyflocentrum.core.model.Comment
import net.tyflopodcast.tyflocentrum.core.model.CommentPublishResult
import net.tyflopodcast.tyflocentrum.core.model.NewsItem
import net.tyflopodcast.tyflocentrum.core.model.PagedResult
import net.tyflopodcast.tyflocentrum.core.model.RadioSchedule
import net.tyflopodcast.tyflocentrum.core.model.ShowNotesData
import net.tyflopodcast.tyflocentrum.core.model.TextVersionParser
import net.tyflopodcast.tyflocentrum.core.model.TextVersionReference
import net.tyflopodcast.tyflocentrum.core.model.WpPostDetail
import net.tyflopodcast.tyflocentrum.core.model.WpPostSummary
import net.tyflopodcast.tyflocentrum.core.model.htmlToPlainText
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

private const val POST_FIELDS = "id,date,title,excerpt,content,guid"
private const val SUMMARY_FIELDS = "id,date,link,title,excerpt"
private const val CATEGORY_FIELDS = "id,name,count"
private const val DETAIL_OPERATION_TIMEOUT_MS = 30_000L
private const val DETAIL_ATTEMPT_TIMEOUT_MS = 12_000L

interface WpApiService {
    @GET("wp/v2/posts")
    suspend fun getPostSummaries(
        @Query("context") context: String = "embed",
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("orderby") orderBy: String = "date",
        @Query("order") order: String = "desc",
        @Query("_fields") fields: String = SUMMARY_FIELDS,
        @Query("categories") categoryId: Int? = null,
        @Query("search") search: String? = null,
        /**
         * Naglowek ustawiany TYLKO dla odswiezenia pierwszej strony.
         *
         * Nie przypinamy `no-cache` na stale w adnotacji `@Headers`, bo serwisy nie
         * wysylaja ETag ani Last-Modified: dopoki tak jest, cache i tak idzie do sieci,
         * a staly naglowek zablokowalby tanie odpowiedzi 304, gdyby WordPress zaczal
         * kiedys wysylac walidatory. Parametr `null` = zachowanie domyslne OkHttp.
         */
        @Header("Cache-Control") cacheControl: String? = null
    ): Response<List<WpPostSummary>>

    @GET("wp/v2/posts/{id}")
    suspend fun getPostDetail(
        @Path("id") id: Int,
        @Query("_fields") fields: String = POST_FIELDS,
        @Header("Cache-Control") cacheControl: String? = null
    ): WpPostDetail

    @GET("wp/v2/categories")
    suspend fun getCategories(
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("orderby") orderBy: String = "name",
        @Query("order") order: String = "asc",
        @Query("_fields") fields: String = CATEGORY_FIELDS
    ): Response<List<Category>>

    @GET("wp/v2/comments")
    suspend fun getComments(
        @Query("post") postId: Int,
        @Query("per_page") perPage: Int = 100
    ): List<Comment>

    @Headers("Cache-Control: no-cache")
    @GET("wp/v2/comments")
    suspend fun getCommentsPage(
        @Query("post") postId: Int,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): Response<List<Comment>>

    @GET("wp/v2/posts/{id}")
    suspend fun getPostLink(
        @Path("id") id: Int,
        @Query("_fields") fields: String = "link"
    ): WpPostLinkResponse

    @GET("wp/v2/pages")
    suspend fun getPageSummaries(
        @Query("context") context: String = "embed",
        @Query("per_page") perPage: Int,
        @Query("parent") parentId: Int? = null,
        @Query("slug") slug: String? = null,
        @Query("orderby") orderBy: String = "date",
        @Query("order") order: String = "desc",
        @Query("_fields") fields: String = SUMMARY_FIELDS
    ): List<WpPostSummary>

    @GET("wp/v2/pages/{id}")
    suspend fun getPageDetail(
        @Path("id") id: Int,
        @Query("_fields") fields: String = POST_FIELDS,
        @Header("Cache-Control") cacheControl: String? = null
    ): WpPostDetail

    @GET("wp/v2/pages")
    suspend fun getPageDetailsBySlug(
        @Query("context") context: String = "view",
        @Query("per_page") perPage: Int = 1,
        @Query("slug") slug: String,
        @Query("_fields") fields: String = POST_FIELDS
    ): List<WpPostDetail>
}

interface ContactApiService {
    @Headers("Cache-Control: no-cache")
    @GET("json.php")
    suspend fun getAvailability(
        @Query("ac") action: String = "current"
    ): Availability

    @Headers("Cache-Control: no-cache")
    @GET("json.php")
    suspend fun getSchedule(
        @Query("ac") action: String = "schedule"
    ): RadioSchedule

    @POST("json.php")
    suspend fun sendContact(
        @Query("ac") action: String = "add",
        @Body payload: ContactPayload
    ): ContactResponse

    @Multipart
    @POST("json.php")
    suspend fun sendVoiceContact(
        @Query("ac") action: String = "addvoice",
        @Part("author") author: okhttp3.RequestBody,
        @Part("duration_ms") durationMs: okhttp3.RequestBody,
        @Part audio: MultipartBody.Part
    ): VoiceContactResponse
}

@Serializable
data class ContactPayload(
    val author: String,
    val comment: String,
    val error: String? = null
)

@Serializable
data class ContactResponse(
    val author: String? = null,
    val comment: String? = null,
    val error: String? = null
)

@Serializable
data class VoiceContactResponse(
    val author: String? = null,
    val durationMs: Int? = null,
    val error: String? = null
)

@Serializable
data class WpPostLinkResponse(
    val link: String? = null
)

data class PagedScreenCache<T>(
    val items: List<T> = emptyList(),
    val nextPage: Int = 1,
    val totalPages: Int? = null
)

data class NewsScreenCache(
    val items: List<NewsItem> = emptyList(),
    val nextPodcastPage: Int = 1,
    val nextArticlePage: Int = 1,
    val podcastTotalPages: Int? = null,
    val articleTotalPages: Int? = null,
    /**
     * Znaczniki swiezosci przenoszone RAZEM z danymi ekranu.
     *
     * Bez nich cache ekranowy byl tylko „pokaz i pomin pobranie”: repozytorium jest
     * singletonem zyjacym tyle, co proces, wiec raz zapisana lista wygladala na wieczna.
     * Ekran nie mial czym stwierdzic, ze dane sa stare, i nie siegal do sieci.
     * Ta para pol zamienia go w „pokaz natychmiast, potem dociagnij”.
     */
    val swiezosc: StanSwiezosci = StanSwiezosci()
)

data class MagazineIssueScreenCache(
    val issue: WpPostDetail,
    val tocItems: List<WpPostSummary>,
    val pdfUrl: String? = null
)

class TyfloRepository(
    private val podcastApi: WpApiService,
    private val articleApi: WpApiService,
    private val contactApi: ContactApiService,
    private val httpClient: OkHttpClient
) {
    private val noRedirectHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var newsScreenCache: NewsScreenCache? = null
    private var magazineScreenCache: List<WpPostSummary>? = null

    private val podcastListScreenCaches = mutableMapOf<Int, PagedScreenCache<WpPostSummary>>()
    private val articleListScreenCaches = mutableMapOf<Int, PagedScreenCache<WpPostSummary>>()
    private val podcastDetailsCache = mutableMapOf<Int, WpPostDetail>()
    private val articleDetailsCache = mutableMapOf<Int, WpPostDetail>()
    private val tyfloswiatPageCache = mutableMapOf<Int, WpPostDetail>()
    private val commentsCache = mutableMapOf<Int, List<Comment>>()
    private val commentsCountCache = mutableMapOf<Int, Int>()
    private val showNotesCache = mutableMapOf<Int, ShowNotesData>()
    private val podcastTextVersionReferencesCache = mutableMapOf<Int, TextVersionReference?>()
    private val podcastTextVersionCache = mutableMapOf<Int, WpPostDetail?>()
    private val magazineIssueScreenCaches = mutableMapOf<Int, MagazineIssueScreenCache>()
    private val tyfloswiatPagesBySlugCache = mutableMapOf<Pair<String, Int>, List<WpPostSummary>>()
    private val tyfloswiatPageSummariesCache = mutableMapOf<Pair<Int, Int>, List<WpPostSummary>>()

    fun peekNewsScreenCache(): NewsScreenCache? = newsScreenCache

    fun storeNewsScreenCache(cache: NewsScreenCache) {
        newsScreenCache = cache
    }

    fun peekPodcastListScreenCache(categoryId: Int?): PagedScreenCache<WpPostSummary>? {
        return podcastListScreenCaches[normalizedCategoryKey(categoryId)]
    }

    fun storePodcastListScreenCache(categoryId: Int?, cache: PagedScreenCache<WpPostSummary>) {
        podcastListScreenCaches[normalizedCategoryKey(categoryId)] = cache
    }

    fun peekArticleListScreenCache(categoryId: Int?): PagedScreenCache<WpPostSummary>? {
        return articleListScreenCaches[normalizedCategoryKey(categoryId)]
    }

    fun storeArticleListScreenCache(categoryId: Int?, cache: PagedScreenCache<WpPostSummary>) {
        articleListScreenCaches[normalizedCategoryKey(categoryId)] = cache
    }

    fun peekMagazineScreenCache(): List<WpPostSummary>? = magazineScreenCache

    fun storeMagazineScreenCache(items: List<WpPostSummary>) {
        magazineScreenCache = items
    }

    fun peekPodcastDetail(id: Int): WpPostDetail? = podcastDetailsCache[id]

    fun peekArticleDetail(id: Int): WpPostDetail? = articleDetailsCache[id]

    fun peekTyfloswiatPage(id: Int): WpPostDetail? = tyfloswiatPageCache[id]

    fun peekComments(postId: Int): List<Comment>? = commentsCache[postId]

    fun peekCommentsCount(postId: Int): Int? = commentsCountCache[postId]

    fun peekShowNotes(postId: Int): ShowNotesData? = showNotesCache[postId]

    fun storeShowNotes(postId: Int, data: ShowNotesData) {
        showNotesCache[postId] = data
    }

    fun peekPodcastTextVersionReference(postId: Int): TextVersionReference? = podcastTextVersionReferencesCache[postId]

    fun peekPodcastTextVersion(postId: Int): WpPostDetail? = podcastTextVersionCache[postId]

    fun peekMagazineIssueScreenCache(issueId: Int): MagazineIssueScreenCache? = magazineIssueScreenCaches[issueId]

    fun storeMagazineIssueScreenCache(issueId: Int, cache: MagazineIssueScreenCache) {
        magazineIssueScreenCaches[issueId] = cache
    }

    suspend fun fetchPodcastSummariesPage(
        page: Int,
        perPage: Int,
        categoryId: Int? = null,
        pomijCache: Boolean = false
    ): PagedResult<WpPostSummary> {
        return podcastApi.getPostSummaries(
            perPage = perPage,
            page = page,
            categoryId = categoryId,
            cacheControl = if (pomijCache) "no-cache" else null
        ).toPagedResult()
    }

    suspend fun fetchArticleSummariesPage(
        page: Int,
        perPage: Int,
        categoryId: Int? = null,
        pomijCache: Boolean = false
    ): PagedResult<WpPostSummary> {
        return articleApi.getPostSummaries(
            perPage = perPage,
            page = page,
            categoryId = categoryId,
            cacheControl = if (pomijCache) "no-cache" else null
        ).toPagedResult()
    }

    suspend fun fetchPodcastSearchSummaries(query: String): List<WpPostSummary> {
        return podcastApi.getPostSummaries(perPage = 100, page = 1, search = query.trim()).bodyOrThrow()
    }

    suspend fun fetchArticleSearchSummaries(query: String): List<WpPostSummary> {
        return articleApi.getPostSummaries(perPage = 100, page = 1, search = query.trim()).bodyOrThrow()
    }

    suspend fun fetchPodcastDetail(id: Int, refresh: Boolean = false): WpPostDetail {
        if (!refresh) {
            podcastDetailsCache[id]?.let { return it }
        }
        return podcastApi.getPostDetail(id).also { podcastDetailsCache[id] = it }
    }

    suspend fun fetchArticleDetail(id: Int, refresh: Boolean = false): WpPostDetail {
        if (!refresh) {
            articleDetailsCache[id]?.let { return it }
        }
        return fetchDetailWithRecovery(
            key = DetailRequestKey(FavoriteArticleOrigin.POST, id),
            refresh = refresh,
            readCache = { articleDetailsCache[id] },
            writeCache = { articleDetailsCache[id] = it },
            fetch = { cacheControl -> articleApi.getPostDetail(id, cacheControl = cacheControl) }
        )
    }

    suspend fun fetchPodcastCategoriesPage(page: Int, perPage: Int): PagedResult<Category> {
        return podcastApi.getCategories(perPage = perPage, page = page).toPagedResult()
    }

    suspend fun fetchArticleCategoriesPage(page: Int, perPage: Int): PagedResult<Category> {
        return articleApi.getCategories(perPage = perPage, page = page).toPagedResult()
    }

    suspend fun fetchComments(postId: Int, refresh: Boolean = false): List<Comment> {
        if (!refresh) {
            commentsCache[postId]?.let { return it }
        }
        return podcastApi.getComments(postId).also { commentsCache[postId] = it }
    }

    suspend fun publishComment(
        postId: Int,
        authorName: String,
        authorEmail: String,
        content: String,
        parentId: Int = 0
    ): Result<CommentPublishResult> {
        return runCatching {
            val postLink = podcastApi.getPostLink(postId).link
                ?.takeIf { it.isNotBlank() }
                ?.let { URI(it) }
                ?: throw IOException("Nie udało się ustalić adresu wpisu dla formularza komentarza.")
            val result = withContext(Dispatchers.IO) {
                val formContext = loadCommentFormContext(postLink)
                if (formContext.formActionUrl == null) {
                    throw IOException(formContext.errorMessage ?: "Nie udało się przygotować formularza komentarza.")
                }
                val response = noRedirectHttpClient.newCall(
                    createHtmlRequestBuilder(formContext.formActionUrl)
                        .post(buildLegacyCommentFormBody(postId, authorName, authorEmail, content, parentId, formContext.akismetNonce))
                        .header("Referer", postLink.toString())
                        .header("Origin", "${postLink.scheme}://${postLink.host}")
                        .build()
                ).execute()
                response.use {
                    mapLegacyCommentResponse(
                        postId = postId,
                        postLink = postLink,
                        response = it
                    )
                }
            }
            commentsCache.remove(postId)
            commentsCountCache.remove(postId)
            result
        }
    }

    suspend fun fetchCommentsCount(postId: Int, refresh: Boolean = false): Int {
        if (!refresh) {
            commentsCountCache[postId]?.let { return it }
        }
        val response = podcastApi.getCommentsPage(postId = postId, page = 1, perPage = 1)
        response.ensureSuccessful()
        return (response.headers()["X-WP-Total"]?.toIntOrNull() ?: response.body().orEmpty().size)
            .also { commentsCountCache[postId] = it }
    }

    suspend fun fetchTyfloswiatPages(slug: String, perPage: Int = 100): List<WpPostSummary> {
        val key = slug to perPage
        tyfloswiatPagesBySlugCache[key]?.let { return it }
        return articleApi.getPageSummaries(perPage = perPage, slug = slug).also {
            tyfloswiatPagesBySlugCache[key] = it
        }
    }

    suspend fun fetchTyfloswiatPageSummaries(parentPageId: Int, perPage: Int = 100): List<WpPostSummary> {
        val key = parentPageId to perPage
        tyfloswiatPageSummariesCache[key]?.let { return it }
        return articleApi.getPageSummaries(perPage = perPage, parentId = parentPageId).also {
            tyfloswiatPageSummariesCache[key] = it
        }
    }

    suspend fun fetchTyfloswiatPage(id: Int, refresh: Boolean = false): WpPostDetail {
        if (!refresh) {
            tyfloswiatPageCache[id]?.let { return it }
        }
        return fetchDetailWithRecovery(
            key = DetailRequestKey(FavoriteArticleOrigin.PAGE, id),
            refresh = refresh,
            readCache = { tyfloswiatPageCache[id] },
            writeCache = { tyfloswiatPageCache[id] = it },
            fetch = { cacheControl -> articleApi.getPageDetail(id, cacheControl = cacheControl) }
        )
    }

    suspend fun fetchPodcastTextVersionReference(postId: Int, refresh: Boolean = false): TextVersionReference? {
        if (!refresh && podcastTextVersionReferencesCache.containsKey(postId)) {
            return podcastTextVersionReferencesCache[postId]
        }
        val podcast = fetchPodcastDetail(postId, refresh = refresh)
        return TextVersionParser.extractReference(podcast.content.rendered).also {
            podcastTextVersionReferencesCache[postId] = it
        }
    }

    suspend fun fetchPodcastTextVersion(postId: Int, refresh: Boolean = false): WpPostDetail? {
        if (!refresh && podcastTextVersionCache.containsKey(postId)) {
            return podcastTextVersionCache[postId]
        }
        val reference = fetchPodcastTextVersionReference(postId, refresh = refresh)
        val page = reference?.let { ref ->
            when {
                ref.pageId != null -> podcastApi.getPageDetail(ref.pageId)
                !ref.slug.isNullOrBlank() -> podcastApi.getPageDetailsBySlug(slug = ref.slug).firstOrNull()
                else -> null
            }
        }
        podcastTextVersionCache[postId] = page
        return page
    }

    fun getListenableUrl(postId: Int): String {
        return "https://tyflopodcast.net/pobierz.php?id=$postId&plik=0"
    }

    suspend fun getTpAvailability(): Availability = contactApi.getAvailability()

    suspend fun getRadioSchedule(): RadioSchedule = contactApi.getSchedule()

    suspend fun sendContactMessage(name: String, message: String): Result<Unit> {
        return runCatching {
            val response = contactApi.sendContact(payload = ContactPayload(author = name, comment = message))
            response.error?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
            Unit
        }
    }

    suspend fun sendVoiceMessage(name: String, audioFile: File, durationMs: Int): Result<Unit> {
        return runCatching {
            val authorPart = name.toRequestBody("text/plain".toMediaType())
            val durationPart = durationMs.toString().toRequestBody("text/plain".toMediaType())
            val audioPart = MultipartBody.Part.createFormData(
                name = "audio",
                filename = audioFile.name.ifBlank { "voice.m4a" },
                body = audioFile.asRequestBody("audio/mp4".toMediaType())
            )
            val response = contactApi.sendVoiceContact(
                author = authorPart,
                durationMs = durationPart,
                audio = audioPart
            )
            response.error?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
            Unit
        }
    }

    private fun normalizedCategoryKey(categoryId: Int?): Int = categoryId ?: -1

    private suspend fun fetchDetailWithRecovery(
        key: DetailRequestKey,
        refresh: Boolean,
        readCache: () -> WpPostDetail?,
        writeCache: (WpPostDetail) -> Unit,
        fetch: suspend (cacheControl: String?) -> WpPostDetail
    ): WpPostDetail {
        if (!refresh) {
            val cached = synchronized(detailRequestLock) {
                readCache()
            }
            if (cached != null) {
                currentCoroutineContext().ensureActive()
                return cached
            }
        }
        val operation = nextDetailOperationId(key)
        val startedAtNanos = System.nanoTime()
        return withTimeoutOrNull(DETAIL_OPERATION_TIMEOUT_MS) {
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < 2) {
                attempt += 1
                try {
                    val detail = withTimeoutOrNull(DETAIL_ATTEMPT_TIMEOUT_MS) {
                        fetch(if (refresh) "no-cache" else null)
                    } ?: throw SocketTimeoutException("Przekroczono limit ${DETAIL_ATTEMPT_TIMEOUT_MS} ms na pojedynczą próbę pobrania.")
                    validateDetail(detail, key)
                    currentCoroutineContext().ensureActive()
                    synchronized(detailRequestLock) {
                        if (isLatestDetailOperationLocked(key, operation)) {
                            writeCache(detail)
                        }
                    }
                    return@withTimeoutOrNull detail
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        throw error
                    }
                    lastError = error
                    if (attempt >= 2 || !shouldRetryDetailFetch(error)) {
                        throw error
                    }
                    val delayMillis = retryDelayMillis(error) ?: 0L
                    if (delayMillis > 0L) {
                        currentCoroutineContext().ensureActive()
                        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L
                        val remainingMillis = DETAIL_OPERATION_TIMEOUT_MS - elapsedMillis
                        if (delayMillis > remainingMillis.coerceAtLeast(0L)) {
                            throw error
                        }
                        kotlinx.coroutines.delay(delayMillis)
                    }
                }
            }
            throw lastError ?: IOException("Nie udało się pobrać artykułu.")
        } ?: throw SocketTimeoutException("Przekroczono łączny limit ${DETAIL_OPERATION_TIMEOUT_MS} ms pobierania.")
    }

    private fun validateDetail(detail: WpPostDetail, key: DetailRequestKey) {
        if (detail.id != key.id) {
            throw IllegalStateException("Serwer zwrócił treść o innym identyfikatorze.")
        }
    }

    private fun shouldRetryDetailFetch(error: Throwable): Boolean {
        return when (error) {
            is SocketTimeoutException -> true
            is SSLException -> false
            is IOException -> true
            is retrofit2.HttpException -> error.code() in setOf(408, 429, 500, 502, 503, 504)
            else -> false
        }
    }

    private fun retryDelayMillis(error: Throwable): Long? {
        val httpError = error as? retrofit2.HttpException ?: return null
        val value = httpError.response()?.headers()?.get("Retry-After") ?: return null
        return parseRetryAfterMillis(value)
    }

    private fun parseRetryAfterMillis(value: String): Long? {
        val trimmed = value.trim()
        val digitPrefix = trimmed.takeWhile { it.isDigit() }
        if (digitPrefix.isNotEmpty() && digitPrefix.length == trimmed.length) {
            val seconds = digitPrefix.toLongOrNull() ?: return DETAIL_OPERATION_TIMEOUT_MS + 1L
            val millis = seconds.coerceAtLeast(0L)
            return if (millis > Long.MAX_VALUE / 1000L) DETAIL_OPERATION_TIMEOUT_MS + 1L else millis * 1000L
        }
        val retryAt = runCatching {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
        }.getOrNull() ?: return null
        val millis = retryAt.toInstant().toEpochMilli() - System.currentTimeMillis()
        return millis.coerceAtLeast(0L)
    }

    private fun nextDetailOperationId(key: DetailRequestKey): Long {
        synchronized(detailRequestLock) {
            val next = (detailRequestVersions[key] ?: 0L) + 1L
            detailRequestVersions[key] = next
            return next
        }
    }

    private fun isLatestDetailOperationLocked(key: DetailRequestKey, operation: Long): Boolean {
        return detailRequestVersions[key] == operation
    }

    private data class DetailRequestKey(
        val origin: FavoriteArticleOrigin,
        val id: Int
    )

    private val detailRequestLock = Any()
    private val detailRequestVersions = mutableMapOf<DetailRequestKey, Long>()

    private fun loadCommentFormContext(postLink: URI): CommentFormContext {
        val response = httpClient.newCall(
            createHtmlRequestBuilder(postLink).get().build()
        ).execute()
        response.use {
            if (!it.isSuccessful) {
                return CommentFormContext(
                    formActionUrl = null,
                    akismetNonce = null,
                    errorMessage = "Nie udało się pobrać formularza komentarza ze strony wpisu."
                )
            }
            val html = it.body.string()
            val document = Jsoup.parse(html, postLink.toString())
            val form = document.selectFirst("form#commentform")
                ?: return CommentFormContext(
                    formActionUrl = null,
                    akismetNonce = null,
                    errorMessage = "Komentowanie nie jest dostępne dla tego wpisu."
                )
            val action = form.attr("action").ifBlank { form.absUrl("action") }
            if (action.isBlank()) {
                return CommentFormContext(
                    formActionUrl = null,
                    akismetNonce = null,
                    errorMessage = "Formularz komentarza nie zawiera adresu wysyłki."
                )
            }
            val formActionUrl = runCatching {
                val decodedAction = Parser.unescapeEntities(action, true)
                URI(decodedAction).takeIf { uri -> uri.isAbsolute } ?: postLink.resolve(decodedAction)
            }.getOrNull()
            val akismetNonce = form.selectFirst("input[name=akismet_comment_nonce]")
                ?.attr("value")
                ?.let { Parser.unescapeEntities(it, true) }
                ?.takeIf { it.isNotBlank() }
            return CommentFormContext(formActionUrl, akismetNonce, null)
        }
    }

    private fun buildLegacyCommentFormBody(
        postId: Int,
        authorName: String,
        authorEmail: String,
        content: String,
        parentId: Int,
        akismetNonce: String?
    ): FormBody {
        return FormBody.Builder()
            .add("comment", content.trim())
            .add("author", authorName.trim())
            .add("email", authorEmail.trim())
            .add("url", "")
            .add("comment_post_ID", postId.toString())
            .add("comment_parent", parentId.coerceAtLeast(0).toString())
            .add("submit", "Dodaj komentarz")
            .add("ak_js", System.currentTimeMillis().toString())
            .add("ak_hp_textarea", "")
            .apply {
                if (!akismetNonce.isNullOrBlank()) {
                    add("akismet_comment_nonce", akismetNonce)
                }
            }
            .build()
    }

    private fun mapLegacyCommentResponse(
        postId: Int,
        postLink: URI,
        response: okhttp3.Response
    ): CommentPublishResult {
        if (response.isRedirect) {
            val location = response.header("Location")
                ?.let { postLink.resolve(it) }
                ?: postLink
            return if (location.queryContains("unapproved") || location.queryContains("moderation-hash")) {
                CommentPublishResult(message = "Komentarz został przekazany do moderacji.")
            } else {
                CommentPublishResult(message = "Komentarz został opublikowany.")
            }
        }

        val html = response.body.string()
        val message = extractLegacyErrorMessage(html)
        return when {
            message.containsSpamSignal() -> CommentPublishResult(
                message = "Komentarz został zakwalifikowany jako spam."
            )
            message.containsModerationSignal() -> CommentPublishResult(
                message = "Komentarz został przekazany do moderacji."
            )
            else -> throw IOException(
                message?.takeIf { it.isNotBlank() }
                    ?: "Nie udało się wysłać komentarza. WordPress zwrócił HTTP ${response.code}."
            )
        }
    }

    private fun createHtmlRequestBuilder(uri: URI): Request.Builder {
        return Request.Builder()
            .url(uri.toString())
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "Tyflocentrum Android/${BuildConfig.VERSION_NAME}")
    }

    private data class CommentFormContext(
        val formActionUrl: URI?,
        val akismetNonce: String?,
        val errorMessage: String?
    )
}

private fun <T> Response<List<T>>.toPagedResult(): PagedResult<T> {
    ensureSuccessful()
    return PagedResult(
        items = body().orEmpty(),
        total = headers()["X-WP-Total"]?.toIntOrNull(),
        totalPages = headers()["X-WP-TotalPages"]?.toIntOrNull()
    )
}

private fun <T> Response<T>.ensureSuccessful() {
    if (!isSuccessful) {
        throw IOException("HTTP ${code()}")
    }
}

private fun <T> Response<T>.bodyOrThrow(): T {
    ensureSuccessful()
    return body() ?: throw IOException("Pusta odpowiedź serwera.")
}

private fun URI.queryContains(key: String): Boolean {
    return rawQuery
        ?.split("&")
        ?.any { part -> part.substringBefore("=").equals(key, ignoreCase = true) }
        ?: false
}

private fun extractLegacyErrorMessage(html: String): String? {
    return Jsoup.parse(html)
        .selectFirst(".wp-die-message")
        ?.text()
        ?.htmlToPlainText()
        ?.takeIf { it.isNotBlank() }
}

private fun String?.containsSpamSignal(): Boolean {
    return !isNullOrBlank() && contains("spam", ignoreCase = true)
}

private fun String?.containsModerationSignal(): Boolean {
    return !isNullOrBlank() &&
        (contains("moderac", ignoreCase = true) ||
            contains("oczekuje", ignoreCase = true) ||
            contains("unapproved", ignoreCase = true))
}
