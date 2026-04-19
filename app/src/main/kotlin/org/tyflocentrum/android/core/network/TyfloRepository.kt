package org.tyflocentrum.android.core.network

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.tyflocentrum.android.core.model.Availability
import org.tyflocentrum.android.core.model.Category
import org.tyflocentrum.android.core.model.Comment
import org.tyflocentrum.android.core.model.PagedResult
import org.tyflocentrum.android.core.model.RadioSchedule
import org.tyflocentrum.android.core.model.WpPostDetail
import org.tyflocentrum.android.core.model.WpPostSummary
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

private const val POST_FIELDS = "id,date,title,excerpt,content,guid"
private const val SUMMARY_FIELDS = "id,date,link,title,excerpt"
private const val CATEGORY_FIELDS = "id,name,count"

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
        @Query("search") search: String? = null
    ): Response<List<WpPostSummary>>

    @GET("wp/v2/posts/{id}")
    suspend fun getPostDetail(
        @Path("id") id: Int,
        @Query("_fields") fields: String = POST_FIELDS
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
        @Query("_fields") fields: String = POST_FIELDS
    ): WpPostDetail
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

class TyfloRepository(
    private val podcastApi: WpApiService,
    private val articleApi: WpApiService,
    private val contactApi: ContactApiService
) {
    suspend fun fetchPodcastSummariesPage(page: Int, perPage: Int, categoryId: Int? = null): PagedResult<WpPostSummary> {
        return podcastApi.getPostSummaries(perPage = perPage, page = page, categoryId = categoryId).toPagedResult()
    }

    suspend fun fetchArticleSummariesPage(page: Int, perPage: Int, categoryId: Int? = null): PagedResult<WpPostSummary> {
        return articleApi.getPostSummaries(perPage = perPage, page = page, categoryId = categoryId).toPagedResult()
    }

    suspend fun fetchPodcastSearchSummaries(query: String): List<WpPostSummary> {
        return podcastApi.getPostSummaries(perPage = 100, page = 1, search = query.trim()).bodyOrThrow()
    }

    suspend fun fetchArticleSearchSummaries(query: String): List<WpPostSummary> {
        return articleApi.getPostSummaries(perPage = 100, page = 1, search = query.trim()).bodyOrThrow()
    }

    suspend fun fetchPodcastDetail(id: Int): WpPostDetail = podcastApi.getPostDetail(id)

    suspend fun fetchArticleDetail(id: Int): WpPostDetail = articleApi.getPostDetail(id)

    suspend fun fetchPodcastCategoriesPage(page: Int, perPage: Int): PagedResult<Category> {
        return podcastApi.getCategories(perPage = perPage, page = page).toPagedResult()
    }

    suspend fun fetchArticleCategoriesPage(page: Int, perPage: Int): PagedResult<Category> {
        return articleApi.getCategories(perPage = perPage, page = page).toPagedResult()
    }

    suspend fun fetchComments(postId: Int): List<Comment> = podcastApi.getComments(postId)

    suspend fun fetchCommentsCount(postId: Int): Int {
        val response = podcastApi.getCommentsPage(postId = postId, page = 1, perPage = 1)
        response.ensureSuccessful()
        return response.headers()["X-WP-Total"]?.toIntOrNull() ?: response.body().orEmpty().size
    }

    suspend fun fetchTyfloswiatPages(slug: String, perPage: Int = 100): List<WpPostSummary> {
        return articleApi.getPageSummaries(perPage = perPage, slug = slug)
    }

    suspend fun fetchTyfloswiatPageSummaries(parentPageId: Int, perPage: Int = 100): List<WpPostSummary> {
        return articleApi.getPageSummaries(perPage = perPage, parentId = parentPageId)
    }

    suspend fun fetchTyfloswiatPage(id: Int): WpPostDetail {
        return articleApi.getPageDetail(id)
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
