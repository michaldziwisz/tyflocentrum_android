package net.tyflopodcast.tyflocentrum.core.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import retrofit2.http.GET
import retrofit2.http.Query

interface ReadingTimeApi {
    @GET("v1/metadata")
    suspend fun batch(@Query("source") source: String, @Query("type") type: String, @Query("ids") ids: String): JsonElement
}
interface PodcastTimeApi {
    @GET("wp/v2/posts")
    suspend fun batch(
        @Query("include") ids: String,
        @Query("per_page") perPage: Int,
        @Query("context") context: String = "embed",
        @Query("_fields") fields: String = "id,modified_gmt,tyflocentrum"
    ): JsonElement
}

class RetrofitTimeTransport(private val reading: ReadingTimeApi, private val podcasts: PodcastTimeApi) : TimeTransport {
    override suspend fun fetch(source: TimeSource, ids: List<Int>): JsonElement {
        require(ids.size in 1..50 && ids.all { it > 0 } && ids.distinct().size == ids.size)
        return if (source == TimeSource.PODCAST) podcasts.batch(ids.joinToString(","), ids.size)
        else reading.batch(source.source, source.type, ids.joinToString(","))
    }
    companion object {
        fun create(httpClient: OkHttpClient, json: Json): RetrofitTimeTransport {
            val client = httpClient.newBuilder().cache(null)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2_500, TimeUnit.MILLISECONDS).build()
            fun retrofit(url: String) = Retrofit.Builder().baseUrl(url).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
            return RetrofitTimeTransport(
                retrofit("https://tyflocentrum.tyflo.eu.org/").create<ReadingTimeApi>(),
                retrofit("https://tyflopodcast.net/wp-json/").create<PodcastTimeApi>()
            )
        }
    }
}
