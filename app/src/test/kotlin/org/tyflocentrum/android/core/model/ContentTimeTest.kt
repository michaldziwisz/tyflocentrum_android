package net.tyflopodcast.tyflocentrum.core.model

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ContentTimeContractTest(private val kind: String, private val case: JsonObject) {
    @Test fun sharedContract() {
        val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        val value = if (kind == "text") ContentTime.reading(
            case["metadata"], case.string("freshness"), case.string("checked_at"),
            case.string("metadata_modified_gmt"), case.string("source_modified_gmt"), now
        ) else ContentTime.audio(case["metadata"])
        assertEquals(case.string("name"), case[if (kind == "text") "expected_minutes" else "expected_seconds"]?.jsonPrimitive?.longOrNull, value?.amount)
        val label = value.timeLabel()
        assertEquals(case.string("name"), case.string("expected_visible"), label.visible)
        assertEquals(case.string("name"), case.string("expected_accessible"), label.accessible)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}: {1}") fun cases(): List<Array<Any>> {
            val fixture = Json.parseToJsonElement(requireNotNull(ContentTimeContractTest::class.java.getResource("/shared-contract.json")).readText()).jsonObject
            return listOf("text", "audio").flatMap { kind -> fixture.getValue(kind + "_cases").jsonArray.map { arrayOf<Any>(kind, it.jsonObject) } }.also { check(it.size == 50) }
        }
    }
}

class ContentTimeTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    @Test fun malformedOptionalMetadataDoesNotLosePostOrFavorites() {
        listOf("null", "false", "4", "[]", "\"bad\"", "{}", "{\"duration_seconds\":true}").forEach { metadata ->
            val post = json.decodeFromString<WpPostSummary>("""{"id":7,"date":"2026-10-08","link":"https://example.test/7","title":{"rendered":"Treść"},"tyflocentrum":$metadata,"modified_gmt":false}""")
            assertEquals(7, post.id)
            assertNull(ContentTime.audio(post.tyflocentrum))
            val favorites = listOf<FavoriteItem>(FavoriteItem.PodcastFavorite(post), FavoriteItem.ArticleFavorite(post, FavoriteArticleOrigin.PAGE))
            assertEquals(favorites, json.decodeFromString<List<FavoriteItem>>(json.encodeToString(favorites)))
        }
    }
    @Test fun legacyFavoritesKeepOrderAndIdentity() {
        val raw = """[{"type":"article","summary":{"id":7,"date":"2026-10-08","title":{"rendered":"Artykuł"},"link":"x"},"origin":"PAGE"},{"type":"podcast","summary":{"id":7,"date":"2026-10-08","title":{"rendered":"Audycja"},"link":"y"}}]"""
        val items = json.decodeFromString<List<FavoriteItem>>(raw)
        assertEquals(listOf("article.page.7", "podcast.7"), items.map { it.id })
        assertEquals(items, json.decodeFromString<List<FavoriteItem>>(json.encodeToString(items)))
    }
    @Test fun optionalSourceDateDoesNotRejectEmbedAndAgesAtBoundary() {
        val m = Json.parseToJsonElement("""{"schema_version":1,"text_status":"ready","word_count":201,"reading_minutes":2}""")
        val start = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        assertEquals(2L, ContentTime.reading(m,"fresh","2026-10-08T12:00:00Z",null,null,start)?.amount)
        assertNull(ContentTime.reading(m,"fresh","2026-10-08T12:00:00Z",null,null,start + 86_400_000))
        assertNull(ContentTime.reading(m,"fresh","2026-10-08T12:00:01Z",null,null,start))
    }
    @Test fun numbersAreStrictAndBounded() {
        listOf("true","\"1\"","1.5","null").forEach { version ->
            assertNull(ContentTime.audio(Json.parseToJsonElement("""{"schema_version":$version,"audio_status":"ready","duration_seconds":60}""")))
        }
        assertEquals(2147483647L, ContentTime.audio(Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":2147483647}"""))?.amount)
        assertNull(ContentTime.audio(Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":1e999}""")))
    }
}
