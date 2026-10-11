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

    /** Kontrakt kolejności: typ treści, tytuł, [czas], data. Czas dokładnie raz, tuż przed datą. */
    private fun assertTimeBeforeDate(name: String, time: String, date: String) {
        val parts = name.split(", ")
        assertTrue("Nazwa bez miejsca na czas przed datą: $name", parts.size >= 3)
        assertEquals("Data na końcu nazwy: $name", date, parts.last())
        assertEquals("Czas bezpośrednio przed datą: $name", time, parts[parts.size - 2])
        assertEquals("Czas dokładnie raz w nazwie: $name", 1, parts.count { it == time })
        assertFalse("Czas nie może poprzedzać typu i tytułu: $name", parts.first().contains(time))
    }

    /** Czas należy do nazwy. Wiersz treści nie ma własnego stateDescription;
     *  stany innych kontrolek (nawigacja, przełączniki) pozostają nietknięte. */
    private fun assertAndroidClickableRow(name: String): android.view.accessibility.AccessibilityNodeInfo {
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
        assertTrue(nodes.single().isEnabled)
        assertNull("Wiersz treści nie może mieć stateDescription",nodes.single().stateDescription?.toString())
        assertEquals(0,nodes.single().childCount)
        assertTrue(nodes.single().actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })
        println("ANDROID_ACCESSIBILITY_NODE name=$name; clickable=${nodes.single().isClickable}; count=${nodes.size}; children=${nodes.single().childCount}; state=${nodes.single().stateDescription}")
        return nodes.single()
    }

    private fun assertNoRowStateKey(name: String) {
        compose.onNodeWithContentDescription(name)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun timeIsInsideClickableNameOnceAndActionsStillWork() {
        var opened = 0
        var listened = 0
        var copied = 0
        var favorited = 0
        val label = ContentTime.audio(Json.parseToJsonElement("""{"schema_version":1,"audio_status":"ready","duration_seconds":61}""")).timeLabel()
        compose.setContent {
            MaterialTheme {
                ContentListItem(title="Audycja", date="8 paź 2026", kind=ContentKind.PODCAST,
                    contentTime=label, onOpen={opened++}, onListen={listened++},
                    onCopyLink={copied++}, favoriteLabel="Dodaj do ulubionych", onToggleFavorite={favorited++})
            }
        }
        val date="8 paź 2026"
        val time="Czas trwania: 1 minuta 1 sekunda"
        val name="Podcast. Audycja, $time, $date"
        assertTimeBeforeDate(name,time,date)
        val row=compose.onNode(hasContentDescription(name) and hasClickAction()).assertHasClickAction()
        assertNoRowStateKey(name)
        compose.onAllNodes(hasContentDescription(name),useUnmergedTree=true).assertCountEquals(1)
        // Czas wypowiedziany raz: żaden inny węzeł nie powtarza go w nazwie.
        compose.onAllNodes(hasContentDescription(time,substring=true),useUnmergedTree=true).assertCountEquals(1)
        compose.onNodeWithText("$date · Czas trwania: 1 min 1 s",useUnmergedTree=true).assertExists()
        val node=row.fetchSemanticsNode()
        val actions=node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Słuchaj","Skopiuj link","Dodaj do ulubionych"),actions.map { it.label })
        // Mierzymy eksportowane akcje Android AX, nie wywołanie callbacków z testu.
        val rowAx = assertAndroidClickableRow(name)
        val labels = listOf("Słuchaj", "Skopiuj link", "Dodaj do ulubionych")
        val androidActions = rowAx.actionList.filter { it.label?.toString() in labels }
        assertEquals(labels, androidActions.map { it.label.toString() })
        assertEquals(labels.size, androidActions.map { it.id }.distinct().size)
        assertTrue(rowAx.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        androidActions.forEach { action ->
            assertTrue("Akcja AX ${action.label} musi zostać obsłużona", rowAx.performAction(action.id))
        }
        compose.runOnIdle {
            assertEquals(1,opened); assertEquals(1,listened); assertEquals(1,copied); assertEquals(1,favorited)
        }
        assertAndroidClickableRow(name)
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
        val beforeName="Artykuł, Czas niedostępny, 8 paź 2026"
        val afterName="Artykuł, Czytanie: około 2 minut, 8 paź 2026"
        assertTimeBeforeDate(beforeName,"Czas niedostępny","8 paź 2026")
        assertTimeBeforeDate(afterName,"Czytanie: około 2 minut","8 paź 2026")
        val before=compose.onNode(hasContentDescription(beforeName) and hasClickAction()).assertHasClickAction()
        assertNoRowStateKey(beforeName)
        val nodeId=before.fetchSemanticsNode().id
        before.performClick()
        compose.runOnIdle { assertEquals(1,opened); release.complete(Unit) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(afterName)).fetchSemanticsNodes().isNotEmpty()
        }
        val after=compose.onNode(hasContentDescription(afterName) and hasClickAction()).assertHasClickAction()
        assertEquals(nodeId,after.fetchSemanticsNode().id)
        assertNoRowStateKey(afterName)
        compose.onAllNodes(hasContentDescription(afterName),useUnmergedTree=true).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Czytanie: około 2 minut",substring=true),useUnmergedTree=true).assertCountEquals(1)
        // Dawny czas nie może zostać nigdzie w nazwach po aktualizacji.
        compose.onAllNodes(hasContentDescription("Czas niedostępny",substring=true),useUnmergedTree=true).assertCountEquals(0)
        compose.onNodeWithText("8 paź 2026 · Czytanie: około 2 min",useUnmergedTree=true).assertExists()
        assertAndroidClickableRow(afterName)
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
        val freshName="Artykuł, Czytanie: około 1 minuty, 2026"
        val expiredName="Artykuł, Czas niedostępny, 2026"
        assertTimeBeforeDate(freshName,"Czytanie: około 1 minuty","2026")
        assertTimeBeforeDate(expiredName,"Czas niedostępny","2026")
        compose.onNode(hasContentDescription(freshName) and hasClickAction()).assertHasClickAction()
        assertNoRowStateKey(freshName)
        compose.runOnIdle { now.addAndGet(1_001) }
        compose.mainClock.advanceTimeBy(1_100)
        // Wygaśnięcie musi zmienić nazwę, a nie tylko tekst na ekranie.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(expiredName)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription(expiredName) and hasClickAction()).assertHasClickAction()
        assertNoRowStateKey(expiredName)
        compose.onAllNodes(hasContentDescription("Czytanie: około 1 minuty",substring=true),useUnmergedTree=true).assertCountEquals(0)
        assertAndroidClickableRow(expiredName)
        assertEquals(1,calls.get())
    }

    @Test fun timeCanBeRemovedFromRowWithoutDateAndWithCommas() {
        val time = androidx.compose.runtime.mutableStateOf<TimeLabel?>(
            TimeLabel("Czytanie: około 2 min", "Czytanie: około 2 minut"))
        compose.setContent {
            MaterialTheme {
                ContentListItem(title="Tytuł, z przecinkiem", date="", supportingText="Opis, dodatkowy",
                    contentTime=time.value, onOpen={})
            }
        }
        val before="Tytuł, z przecinkiem, Opis, dodatkowy, Czytanie: około 2 minut"
        val after="Tytuł, z przecinkiem, Opis, dodatkowy"
        val nodeId=compose.onNodeWithContentDescription(before).assertHasClickAction().fetchSemanticsNode().id
        assertNoRowStateKey(before)
        assertAndroidClickableRow(before)
        compose.runOnIdle { time.value=null }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(after)).fetchSemanticsNodes().size==1 }
        assertEquals(nodeId,compose.onNodeWithContentDescription(after).assertHasClickAction().fetchSemanticsNode().id)
        assertNoRowStateKey(after)
        assertAndroidClickableRow(after)
        compose.onAllNodes(hasContentDescription("Czytanie",substring=true),useUnmergedTree=true).assertCountEquals(0)
    }

    @Test fun issueAndPdfRowsHaveNoInventedTime() {
        compose.setContent { MaterialTheme { ContentListItem(title="Numer czasopisma",date="2026",onOpen={}) } }
        compose.onNodeWithContentDescription("Numer czasopisma, 2026").assertHasClickAction()
        // Brak czasu musi być brakiem pola w nazwie ORAZ brakiem klucza StateDescription:
        // produkt nie dopisuje czasu do stanu wiersza w żadnym wariancie.
        assertNoRowStateKey("Numer czasopisma, 2026")
        assertAndroidClickableRow("Numer czasopisma, 2026")
        compose.onAllNodes(hasContentDescription("Czas",substring=true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Czytanie",substring=true)).assertCountEquals(0)
    }
}
