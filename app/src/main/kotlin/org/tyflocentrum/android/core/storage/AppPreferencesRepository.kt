package net.tyflopodcast.tyflocentrum.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.tyflopodcast.tyflocentrum.core.model.AppSettings
import net.tyflopodcast.tyflocentrum.core.model.ContactDraft
import net.tyflopodcast.tyflocentrum.core.model.ContentKindLabelPosition
import net.tyflopodcast.tyflocentrum.core.model.FavoriteItem
import net.tyflopodcast.tyflocentrum.core.model.PlaybackRateRememberMode
import net.tyflopodcast.tyflocentrum.core.model.PushPreferences

class AppPreferencesRepository(
    context: Context,
    private val json: Json
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("tyflocentrum.preferences_pb") }
    )

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            contentKindLabelPosition = prefs[CONTENT_KIND_LABEL_POSITION]
                ?.let(ContentKindLabelPosition::valueOf)
                ?: ContentKindLabelPosition.BEFORE,
            playbackRateRememberMode = prefs[PLAYBACK_RATE_REMEMBER_MODE]
                ?.let(PlaybackRateRememberMode::valueOf)
                ?: PlaybackRateRememberMode.GLOBAL,
            pushPreferences = PushPreferences(
                podcast = prefs[PUSH_PODCAST] ?: true,
                article = prefs[PUSH_ARTICLE] ?: true,
                live = prefs[PUSH_LIVE] ?: true,
                schedule = prefs[PUSH_SCHEDULE] ?: true
            )
        )
    }

    val favoritesFlow: Flow<List<FavoriteItem>> = dataStore.data.map { prefs ->
        val raw = prefs[FAVORITES_JSON].orEmpty()
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString(ListSerializer(FavoriteItem.serializer()), raw)
            }.getOrDefault(emptyList())
        }
    }

    val contactDraftFlow: Flow<ContactDraft> = dataStore.data.map { prefs ->
        ContactDraft(
            name = prefs[CONTACT_NAME].orEmpty(),
            message = prefs[CONTACT_MESSAGE] ?: ContactDraft.DEFAULT_CONTACT_MESSAGE
        )
    }

    suspend fun setContentKindLabelPosition(position: ContentKindLabelPosition) {
        dataStore.edit { it[CONTENT_KIND_LABEL_POSITION] = position.name }
    }

    suspend fun setPlaybackRateRememberMode(mode: PlaybackRateRememberMode) {
        dataStore.edit { it[PLAYBACK_RATE_REMEMBER_MODE] = mode.name }
    }

    suspend fun updatePushPreferences(update: (PushPreferences) -> PushPreferences) {
        dataStore.edit { prefs ->
            val current = PushPreferences(
                podcast = prefs[PUSH_PODCAST] ?: true,
                article = prefs[PUSH_ARTICLE] ?: true,
                live = prefs[PUSH_LIVE] ?: true,
                schedule = prefs[PUSH_SCHEDULE] ?: true
            )
            val next = update(current)
            prefs[PUSH_PODCAST] = next.podcast
            prefs[PUSH_ARTICLE] = next.article
            prefs[PUSH_LIVE] = next.live
            prefs[PUSH_SCHEDULE] = next.schedule
        }
    }

    suspend fun updateContactDraft(name: String? = null, message: String? = null) {
        dataStore.edit { prefs ->
            name?.let { prefs[CONTACT_NAME] = it }
            message?.let { prefs[CONTACT_MESSAGE] = it }
        }
    }

    suspend fun resetContactMessage() {
        dataStore.edit { prefs ->
            prefs[CONTACT_MESSAGE] = ContactDraft.DEFAULT_CONTACT_MESSAGE
        }
    }

    suspend fun toggleFavorite(item: FavoriteItem) {
        val current = favoritesFlow.first()
        val updated = if (current.any { it.id == item.id }) {
            current.filterNot { it.id == item.id }
        } else {
            listOf(item) + current
        }
        persistFavorites(updated)
    }

    suspend fun removeFavorite(item: FavoriteItem) {
        val updated = favoritesFlow.first().filterNot { it.id == item.id }
        persistFavorites(updated)
    }

    suspend fun savePlaybackRateGlobal(rate: Float) {
        dataStore.edit { it[GLOBAL_PLAYBACK_RATE] = rate }
    }

    suspend fun loadPlaybackRateGlobal(): Float? {
        return dataStore.data.first()[GLOBAL_PLAYBACK_RATE]
    }

    suspend fun savePlaybackRateForUrl(url: String, rate: Float) {
        dataStore.edit { it[floatPreferencesKey(dynamicKey("rate", url))] = rate }
    }

    suspend fun loadPlaybackRateForUrl(url: String): Float? {
        return dataStore.data.first()[floatPreferencesKey(dynamicKey("rate", url))]
    }

    suspend fun saveResumePosition(url: String, elapsedMs: Long) {
        dataStore.edit { prefs ->
            prefs[longPreferencesKey(dynamicKey("resume", url))] = elapsedMs
        }
    }

    suspend fun loadResumePosition(url: String): Long? {
        return dataStore.data.first()[longPreferencesKey(dynamicKey("resume", url))]
    }

    suspend fun clearResumePosition(url: String) {
        dataStore.edit { prefs ->
            prefs.remove(longPreferencesKey(dynamicKey("resume", url)))
        }
    }

    private suspend fun persistFavorites(items: List<FavoriteItem>) {
        val encoded = json.encodeToString(ListSerializer(FavoriteItem.serializer()), items)
        dataStore.edit { it[FAVORITES_JSON] = encoded }
    }

    private fun dynamicKey(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return "$prefix.$digest"
    }

    companion object {
        private val CONTENT_KIND_LABEL_POSITION = stringPreferencesKey("settings.contentKindLabelPosition")
        private val PLAYBACK_RATE_REMEMBER_MODE = stringPreferencesKey("settings.playbackRateRememberMode")
        private val PUSH_PODCAST = booleanPreferencesKey("push.podcast")
        private val PUSH_ARTICLE = booleanPreferencesKey("push.article")
        private val PUSH_LIVE = booleanPreferencesKey("push.live")
        private val PUSH_SCHEDULE = booleanPreferencesKey("push.schedule")
        private val FAVORITES_JSON = stringPreferencesKey("favorites.json")
        private val CONTACT_NAME = stringPreferencesKey("contact.name")
        private val CONTACT_MESSAGE = stringPreferencesKey("contact.message")
        private val GLOBAL_PLAYBACK_RATE = floatPreferencesKey("playback.globalRate")
    }
}
