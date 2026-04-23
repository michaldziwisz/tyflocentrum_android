package org.tyflocentrum.android.core

import android.app.Application
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.tyflocentrum.android.BuildConfig
import org.tyflocentrum.android.core.network.ContactApiService
import org.tyflocentrum.android.core.network.TyfloRepository
import org.tyflocentrum.android.core.network.WpApiService
import org.tyflocentrum.android.core.playback.CastDiagnosticsLogger
import org.tyflocentrum.android.core.playback.PlayerController
import org.tyflocentrum.android.core.storage.AppPreferencesRepository
import retrofit2.Retrofit
import retrofit2.create
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer(
    application: Application
) {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(application.cacheDir.resolve("http-cache"), 20L * 1024L * 1024L))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .build()

    private fun retrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    private val podcastApi = retrofit("https://tyflopodcast.net/wp-json/").create<WpApiService>()
    private val articleApi = retrofit("https://tyfloswiat.pl/wp-json/").create<WpApiService>()
    private val contactApi = retrofit("https://kontakt.tyflopodcast.net/").create<ContactApiService>()

    val preferencesRepository = AppPreferencesRepository(
        context = application,
        json = json
    )

    val repository = TyfloRepository(
        podcastApi = podcastApi,
        articleApi = articleApi,
        contactApi = contactApi
    )

    val castDiagnostics = CastDiagnosticsLogger()

    val playerController = PlayerController(
        context = application,
        preferences = preferencesRepository,
        diagnostics = castDiagnostics
    )
}
