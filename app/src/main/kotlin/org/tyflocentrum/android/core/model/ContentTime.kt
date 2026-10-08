package net.tyflopodcast.tyflocentrum.core.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlinx.serialization.json.*

const val CONTENT_TIME_TTL_MS = 86_400_000L

data class TimeLabel(val visible: String, val accessible: String)

@ConsistentCopyVisibility
data class ContentTime private constructor(val amount: Long, val audio: Boolean) {
    companion object {
        fun audio(metadata: JsonElement?): ContentTime? {
            val obj = metadata as? JsonObject ?: return null
            if (obj.integer("schema_version") != 1L || obj.string("audio_status") != "ready") return null
            val number = (obj["duration_seconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
            if (!number.isFinite() || number <= 0 || number > Int.MAX_VALUE.toDouble()) return null
            return ContentTime(ceil(number).toLong(), true)
        }

        fun reading(metadata: JsonElement?, freshness: String?, checkedAt: String?, metadataModified: String?, sourceModified: String?, now: Long): ContentTime? {
            val obj = metadata as? JsonObject ?: return null
            if (freshness != "fresh" || obj.integer("schema_version") != 1L || obj.string("text_status") != "ready") return null
            val checked = utcMillis(checkedAt) ?: return null
            if (checked > now || now - checked >= CONTENT_TIME_TTL_MS) return null
            val source = utcMillis(sourceModified)
            if (source != null && (utcMillis(metadataModified)?.let { it < source } != false)) return null
            val words = obj.integer("word_count") ?: return null
            val minutes = obj.integer("reading_minutes") ?: return null
            if (words <= 0 || minutes <= 0 || minutes != words / 200 + if (words % 200 > 0) 1 else 0) return null
            return ContentTime(minutes, false)
        }
    }
}

fun ContentTime?.timeLabel(): TimeLabel {
    if (this == null) return TimeLabel("Czas niedostępny", "Czas niedostępny")
    if (!audio) return TimeLabel("Czytanie: około $amount min", "Czytanie: około $amount ${if (amount == 1L) "minuty" else "minut"}")
    val values = listOf(amount / 3600, amount / 60 % 60, amount % 60)
    val short = listOf("godz.", "min", "s")
    val forms = listOf(listOf("godzina", "godziny", "godzin"), listOf("minuta", "minuty", "minut"), listOf("sekunda", "sekundy", "sekund"))
    fun unit(n: Long, f: List<String>) = when {
        n == 1L -> f[0]
        n % 10 in 2..4 && n % 100 !in 12..14 -> f[1]
        else -> f[2]
    }
    return TimeLabel(
        "Czas trwania: " + values.indices.filter { values[it] > 0 }.joinToString(" ") { "${values[it]} ${short[it]}" },
        "Czas trwania: " + values.indices.filter { values[it] > 0 }.joinToString(" ") { "${values[it]} ${unit(values[it], forms[it])}" }
    )
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.integer(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
internal fun JsonElement?.optionalString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun utcMillis(value: String?): Long? {
    if (value == null) return null
    return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) {
        try { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { null }
    }
}
