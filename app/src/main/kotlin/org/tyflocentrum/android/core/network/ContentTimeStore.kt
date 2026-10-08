package net.tyflopodcast.tyflocentrum.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import net.tyflopodcast.tyflocentrum.core.model.*

enum class TimeSource(val source: String, val type: String) {
    PODCAST("tyflopodcast.net", "posts"),
    ARTICLE_POST("tyfloswiat.pl", "posts"),
    ARTICLE_PAGE("tyfloswiat.pl", "pages")
}
data class TimeKey(val source: TimeSource, val id: Int)
data class TimeRequest(val key: TimeKey, val modifiedGmt: String? = null)
fun WpPostSummary.timeRequest(kind: ContentKind, origin: FavoriteArticleOrigin = FavoriteArticleOrigin.POST) = TimeRequest(
    TimeKey(if (kind == ContentKind.PODCAST) TimeSource.PODCAST else if (origin == FavoriteArticleOrigin.PAGE) TimeSource.ARTICLE_PAGE else TimeSource.ARTICLE_POST, id),
    modifiedGmt.optionalString()
)
fun FavoriteItem.timeRequest(): TimeRequest? = when (this) {
    is FavoriteItem.PodcastFavorite -> summary.timeRequest(ContentKind.PODCAST)
    is FavoriteItem.ArticleFavorite -> summary.timeRequest(ContentKind.ARTICLE, origin)
    else -> null
}
fun interface TimeTransport {
    suspend fun fetch(source: TimeSource, ids: List<Int>): JsonElement
}

data class TimeRecord(
    val time: ContentTime?,
    val expiresAt: Long,
    val modifiedGmt: String?,
    val requestedModifiedGmt: String? = null
) {
    fun value(request: TimeRequest, now: Long): ContentTime? {
        if (now >= expiresAt) return null
        val source = utcMillis(request.modifiedGmt)
        if (source != null && utcMillis(modifiedGmt)?.let { it >= source } != true) return null
        return time
    }
}

/** Opcjonalna warstwa list. Nigdy nie pobiera tekstu ani audio i nie zmienia listy. */
class ContentTimeStore(
    private val transport: TimeTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 512,
    private val timeoutMs: Long = 2_500
) {
    private val lock = Any()
    private val cache = linkedMapOf<TimeKey, TimeRecord>()
    private val flights = mutableMapOf<TimeKey, Flight>()
    private val knownModified = linkedMapOf<TimeKey, String>()
    private class Flight(val request: TimeRequest)
    private val mutableState = MutableStateFlow<Map<TimeKey, TimeRecord>>(emptyMap())
    val state = mutableState.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    init { require(capacity > 0); require(timeoutMs > 0) }

    fun value(request: TimeRequest, now: Long = clock()): ContentTime? = synchronized(lock) {
        state.value[request.key]?.value(effective(request), now)
    }

    // Nowości zachowują stare obiekty wierszy dla stabilnej kolejności. Nowszą
    // datę źródłową uwzględniamy osobno, bez podmiany listy i jej kluczy.
    fun observeArticles(source: TimeSource, posts: List<WpPostSummary>) = synchronized(lock) {
        var changed = false
        posts.filter { it.id > 0 }.forEach { post ->
            val key = TimeKey(source, post.id)
            val modified = post.modifiedGmt.optionalString()
            if ((utcMillis(modified) ?: Long.MIN_VALUE) > (utcMillis(knownModified[key]) ?: Long.MIN_VALUE)) {
                knownModified.remove(key)
                knownModified[key] = modified!!
                if (cache[key]?.value(TimeRequest(key, modified), clock()) == null) cache.remove(key)
                flights.remove(key)
                changed = true
            }
        }
        while (knownModified.size > capacity) knownModified.remove(knownModified.keys.first())
        if (changed) { publish(); mutableRevision.value += 1 }
    }

    private fun effective(request: TimeRequest): TimeRequest {
        val known = knownModified[request.key]
        return if ((utcMillis(known) ?: Long.MIN_VALUE) > (utcMillis(request.modifiedGmt) ?: Long.MIN_VALUE)) request.copy(modifiedGmt = known) else request
    }

    fun invalidate(source: TimeSource) = synchronized(lock) {
        cache.keys.removeAll { it.source == source }
        flights.keys.removeAll { it.source == source }
        publish()
        mutableRevision.value += 1
    }

    /** Tylko świeża odpowiedź listowego WP, nigdy odczyt starego ulubionego/cache. */
    fun acceptPodcasts(posts: List<WpPostSummary>) = synchronized(lock) {
        posts.filter { it.id > 0 }.forEach { post ->
            val request = post.timeRequest(ContentKind.PODCAST)
            flights.remove(request.key)
            put(request.key, audioRecord(post.tyflocentrum, post.modifiedGmt.optionalString(), request))
        }
        publish()
    }

    suspend fun load(requests: List<TimeRequest>) {
        val unique = synchronized(lock) {
            requests.filter { it.key.id > 0 }.map { effective(it) }.groupBy { it.key }.map { (_, same) ->
                same.maxBy { utcMillis(it.modifiedGmt) ?: Long.MIN_VALUE }
            }
        }
        for ((source, group) in unique.groupBy { it.key.source }) {
            for (chunk in group.chunked(50)) {
                currentCoroutineContext().ensureActive()
                val owned = synchronized(lock) {
                    chunk.mapNotNull { request ->
                        val record = cache[request.key]
                        val reusable = record != null && clock() < record.expiresAt &&
                            (record.value(request, clock()) != null || record.requestedModifiedGmt == request.modifiedGmt)
                        val pending = flights[request.key]
                        val newer = (utcMillis(request.modifiedGmt) ?: Long.MIN_VALUE) > (utcMillis(pending?.request?.modifiedGmt) ?: Long.MIN_VALUE)
                        if (reusable || (pending != null && !newer)) null else {
                            val flight = Flight(request)
                            flights[request.key] = flight
                            flight
                        }
                    }
                }
                if (owned.isEmpty()) continue
                try {
                    val payload = try {
                        withTimeoutOrNull(timeoutMs) { transport.fetch(source, owned.map { it.request.key.id }) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    val rows = parseRows(source, payload)
                    synchronized(lock) {
                        owned.forEach { flight ->
                            val request = flight.request
                            if (flights[request.key] === flight) {
                                val row = rows[request.key.id]?.singleOrNull()
                                put(request.key, if (source == TimeSource.PODCAST) {
                                    audioRecord(row?.get("tyflocentrum"), row?.string("modified_gmt"), request)
                                } else readingRecord(row, request))
                            }
                        }
                        publish()
                    }
                } finally {
                    synchronized(lock) {
                        owned.forEach { if (flights[it.request.key] === it) flights.remove(it.request.key) }
                    }
                }
            }
        }
    }

    private fun parseRows(source: TimeSource, payload: JsonElement?): Map<Int, List<JsonObject>> {
        val items = if (source == TimeSource.PODCAST) payload as? JsonArray else {
            val root = payload as? JsonObject
            if (root?.integer("schema_version") != 1L || root.string("source") != source.source || root.string("type") != source.type) return emptyMap()
            root["items"] as? JsonArray
        }
        return items.orEmpty().mapNotNull { it as? JsonObject }.filter { (it.integer("id") ?: 0) in 1..Int.MAX_VALUE.toLong() }.groupBy { it.integer("id")!!.toInt() }
    }

    private fun readingRecord(row: JsonObject?, request: TimeRequest): TimeRecord {
        val now = clock()
        val value = ContentTime.reading(row?.get("tyflocentrum"), row?.string("freshness"), row?.string("checked_at"), row?.string("modified_gmt"), request.modifiedGmt, now)
        return TimeRecord(value, if (value == null) now + 60_000 else utcMillis(row?.string("checked_at"))!! + CONTENT_TIME_TTL_MS, row?.string("modified_gmt"), request.modifiedGmt)
    }

    private fun audioRecord(metadata: JsonElement?, modified: String?, request: TimeRequest): TimeRecord {
        val now = clock()
        val knownSource = utcMillis(request.modifiedGmt)
        val generated = (metadata as? JsonObject)?.string("generated_at")
        val value = ContentTime.audio(metadata).takeUnless {
            knownSource != null && utcMillis(generated)?.let { it >= knownSource } != true
        }
        return TimeRecord(value, now + if (value == null) 60_000 else CONTENT_TIME_TTL_MS, modified ?: generated, request.modifiedGmt)
    }

    private fun put(key: TimeKey, value: TimeRecord) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > capacity) cache.remove(cache.keys.first())
    }
    private fun publish() { mutableState.value = cache.toMap() }
}
