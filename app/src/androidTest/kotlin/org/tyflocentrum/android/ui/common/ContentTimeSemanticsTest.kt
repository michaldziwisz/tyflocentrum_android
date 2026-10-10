package net.tyflopodcast.tyflocentrum.ui.common

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

// Wspólny runner używa produkcyjnego Application i odtwarzacza.
// Same testy semantyki nadal nie są pomiarem odtwarzania.
class ContentTimeTestRunner : AndroidJUnitRunner() {
    // Ochrona także wywołań bibliotek: test nie może odłączać czytnika użytkownika.
    override fun getUiAutomation(): android.app.UiAutomation = getUiAutomation(0)
    override fun getUiAutomation(flags: Int): android.app.UiAutomation =
        requireNotNull(super.getUiAutomation(flags or android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, net.tyflopodcast.tyflocentrum.TyflocentrumApplication::class.java.name, context)
}

class ContentTimeSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private fun assertAndroidClickableName(name: String, time: String? = null) {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun matching(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.contentDescription?.toString() == name) nodes += node
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(automation.rootInActiveWindow)
            return nodes
        }
        compose.waitUntil(5_000) { matching().any { it.isClickable } }
        val nodes=matching()
        assertEquals(1,nodes.size)
        assertTrue(nodes.single().isClickable)
        assertEquals(time,nodes.single().stateDescription?.toString())
        assertEquals(0,nodes.single().childCount)
        assertTrue(nodes.single().actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })
        println("ANDROID_ACCESSIBILITY_NODE name=$name; clickable=${nodes.single().isClickable}; count=${nodes.size}; children=${nodes.single().childCount}")
    }

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
        val name="Podcast. Audycja, 8 paź 2026"
        val time="Czas trwania: 1 minuta 1 sekunda"
        val row=compose.onNode(hasContentDescription(name) and hasStateDescription(time)).assertHasClickAction()
        compose.onAllNodes(hasStateDescription(time),useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithText("8 paź 2026 · Czas trwania: 1 min 1 s",useUnmergedTree=true).assertExists()
        val node=row.fetchSemanticsNode()
        val actions=node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Słuchaj","Skopiuj link","Dodaj do ulubionych"),actions.map { it.label })
        row.performClick()
        compose.runOnIdle { actions.first().action(); assertEquals(1,opened); assertEquals(1,listened) }
        assertAndroidClickableName(name,time)
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
        val before=compose.onNode(hasContentDescription("Artykuł, 8 paź 2026") and hasStateDescription("Czas niedostępny")).assertHasClickAction()
        val nodeId=before.fetchSemanticsNode().id
        before.performClick()
        compose.runOnIdle { assertEquals(1,opened); release.complete(Unit) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasStateDescription("Czytanie: około 2 minut")).fetchSemanticsNodes().isNotEmpty()
        }
        val after=compose.onNode(hasContentDescription("Artykuł, 8 paź 2026") and hasStateDescription("Czytanie: około 2 minut")).assertHasClickAction()
        assertEquals(nodeId,after.fetchSemanticsNode().id)
        compose.onAllNodes(hasStateDescription("Czytanie: około 2 minut"),useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithText("8 paź 2026 · Czytanie: około 2 min",useUnmergedTree=true).assertExists()
        assertAndroidClickableName("Artykuł, 8 paź 2026","Czytanie: około 2 minut")
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
        compose.onNode(hasContentDescription("Artykuł, 2026") and hasStateDescription("Czytanie: około 1 minuty")).assertHasClickAction()
        compose.runOnIdle { now.addAndGet(1_001) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasStateDescription("Czas niedostępny")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Artykuł, 2026") and hasStateDescription("Czas niedostępny")).assertHasClickAction()
        assertEquals(1,calls.get())
    }

    @Test fun issueAndPdfRowsHaveNoInventedTime() {
        compose.setContent { MaterialTheme { ContentListItem(title="Numer czasopisma",date="2026",onOpen={}) } }
        compose.onNodeWithContentDescription("Numer czasopisma, 2026").assertHasClickAction()
        // Brak czasu musi być brakiem KLUCZA StateDescription; kontrola nazwy tego nie wychwyci,
        // bo produkt umieszcza czas w StateDescription.
        compose.onNodeWithContentDescription("Numer czasopisma, 2026")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertAndroidClickableName("Numer czasopisma, 2026")
        compose.onAllNodes(hasContentDescription("Czas",substring=true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Czytanie",substring=true)).assertCountEquals(0)
    }
}
