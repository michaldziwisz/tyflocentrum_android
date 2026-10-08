package net.tyflopodcast.tyflocentrum.ui.common

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.runner.AndroidJUnitRunner
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import net.tyflopodcast.tyflocentrum.core.model.*
import net.tyflopodcast.tyflocentrum.core.network.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Te testy mierzą prawdziwą semantykę Compose, nie uruchamiają Cast ani radia.
class ContentTimeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}

class ContentTimeSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timeIsOnClickableRowOnceAndActionsStillWork() {
        var opened = 0
        var listened = 0
        val label = ContentTime.audio(Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":61}""")).timeLabel()
        compose.setContent {
            MaterialTheme {
                ContentListItem(title="Audycja", date="8 paź 2026", kind=ContentKind.PODCAST,
                    contentTime=label, onOpen={opened++}, onListen={listened++},
                    onCopyLink={}, favoriteLabel="Dodaj do ulubionych", onToggleFavorite={})
            }
        }
        val name="Podcast. Audycja, 8 paź 2026, Czas trwania: 1 minuta 1 sekunda"
        val row=compose.onNodeWithContentDescription(name).assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Czas trwania:",substring=true),useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithText("8 paź 2026 · Czas trwania: 1 min 1 s",useUnmergedTree=true).assertExists()
        val node=row.fetchSemanticsNode()
        val actions=node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Słuchaj","Skopiuj link","Dodaj do ulubionych"),actions.map { it.label })
        row.performClick()
        compose.runOnIdle { actions.first().action(); assertEquals(1,opened); assertEquals(1,listened) }
        println("SEMANTICS_AUDIO="+compose.onRoot().printToString())
    }

    @Test fun blockedMetadataLeavesRowClickableAndAddsReadingWithoutChangingNode() {
        val entered=AtomicBoolean(false)
        val release=CompletableDeferred<Unit>()
        val now=Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        val request=TimeRequest(TimeKey(TimeSource.ARTICLE_PAGE,7))
        val store=ContentTimeStore(TimeTransport { _, _ ->
            entered.set(true); release.await()
            Json.parseToJsonElement("""{"schema_version":1,"source":"tyfloswiat.pl","type":"pages","items":[{"id":7,"freshness":"fresh","checked_at":"2026-10-08T11:00:00Z","tyflocentrum":{"schema_version":1,"text_status":"ready","word_count":201,"reading_minutes":2}}]}""")
        },clock={now},timeoutMs=60_000)
        var opened=0
        compose.setContent {
            val times=rememberContentTimeLabels(listOf(request),store,clock={now})
            MaterialTheme {
                ContentListItem(title="Artykuł",date="8 paź 2026",contentTime=times.label(request),onOpen={opened++})
            }
        }
        compose.waitUntil(5_000) { entered.get() }
        val before=compose.onNodeWithContentDescription("Artykuł, 8 paź 2026, Czas niedostępny").assertHasClickAction()
        val nodeId=before.fetchSemanticsNode().id
        before.performClick()
        compose.runOnIdle { assertEquals(1,opened); release.complete(Unit) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Czytanie: około 2 minut",substring=true)).fetchSemanticsNodes().isNotEmpty()
        }
        val after=compose.onNodeWithContentDescription("Artykuł, 8 paź 2026, Czytanie: około 2 minut").assertHasClickAction()
        assertEquals(nodeId,after.fetchSemanticsNode().id)
        compose.onAllNodes(hasContentDescription("Czytanie:",substring=true),useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithText("8 paź 2026 · Czytanie: około 2 min",useUnmergedTree=true).assertExists()
        println("SEMANTICS_READING="+compose.onRoot().printToString())
    }

    @Test fun visibleReadingExpiresWithoutScrollingOrRetry() {
        val now=java.util.concurrent.atomic.AtomicLong(Instant.parse("2026-10-08T12:00:00Z").toEpochMilli())
        val request=TimeRequest(TimeKey(TimeSource.ARTICLE_POST,7))
        val calls=java.util.concurrent.atomic.AtomicInteger(0)
        val store=ContentTimeStore(TimeTransport { _, _ ->
            calls.incrementAndGet()
            Json.parseToJsonElement("""{"schema_version":1,"source":"tyfloswiat.pl","type":"posts","items":[{"id":7,"freshness":"fresh","checked_at":"2026-10-07T12:00:01Z","tyflocentrum":{"schema_version":1,"text_status":"ready","word_count":200,"reading_minutes":1}}]}""")
        },clock={now.get()})
        kotlinx.coroutines.runBlocking { store.load(listOf(request)) }
        compose.setContent {
            val times=rememberContentTimeLabels(listOf(request),store,clock={now.get()})
            MaterialTheme { ContentListItem(title="Artykuł",date="2026",contentTime=times.label(request),onOpen={}) }
        }
        compose.onNodeWithContentDescription("Artykuł, 2026, Czytanie: około 1 minuty").assertHasClickAction()
        compose.runOnIdle { now.addAndGet(1_001) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Czas niedostępny",substring=true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Artykuł, 2026, Czas niedostępny").assertHasClickAction()
        assertEquals(1,calls.get())
    }

    @Test fun issueAndPdfRowsHaveNoInventedTime() {
        compose.setContent { MaterialTheme { ContentListItem(title="Numer czasopisma",date="2026",onOpen={}) } }
        compose.onNodeWithContentDescription("Numer czasopisma, 2026").assertHasClickAction()
        compose.onAllNodes(hasContentDescription("Czas",substring=true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Czytanie",substring=true)).assertCountEquals(0)
    }
}
