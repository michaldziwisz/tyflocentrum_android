package net.tyflopodcast.tyflocentrum.ui.common

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.tyflopodcast.tyflocentrum.core.model.*
import net.tyflopodcast.tyflocentrum.core.storage.AppPreferencesRepository
import org.junit.Assert.*
import org.junit.Test

class ContentTimeFavoritesStorageTest {
    @Test fun oldDataStoreFavoritesSurviveReadingAddingAndRemovingMetadataFavorites() = runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(target.cacheDir,"content-time-favorites-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated=object: ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val job=SupervisorJob()
        val seed=PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={isolated.preferencesDataStoreFile("tyflocentrum.preferences_pb")})
        seed.edit { it[stringPreferencesKey("favorites.json")] = """[{"type":"article","summary":{"id":7,"date":"2026-10-08","title":{"rendered":"Stary artykuł"},"link":"https://example.test/7"},"origin":"PAGE"},{"type":"podcast","summary":{"id":7,"date":"2026-10-08","title":{"rendered":"Stara audycja"},"link":"https://example.test/7"}}]""" }
        job.cancelAndJoin()
        val json=Json { ignoreUnknownKeys=true; classDiscriminator="type" }
        val repository=AppPreferencesRepository(isolated,json)
        val before=repository.favoritesFlow.first()
        assertEquals(listOf("article.page.7","podcast.7"),before.map { it.id })
        val new=FavoriteItem.PodcastFavorite(WpPostSummary(8,"2026-10-08",WpRenderedText("Nowa audycja"),link="https://example.test/8",tyflocentrum=JsonPrimitive(false)))
        repository.toggleFavorite(new)
        assertEquals(listOf("podcast.8","article.page.7","podcast.7"),repository.favoritesFlow.first().map { it.id })
        repository.removeFavorite(new)
        assertEquals(before,repository.favoritesFlow.first())
        assertTrue(isolated.preferencesDataStoreFile("tyflocentrum.preferences_pb").isFile)
    }
}
