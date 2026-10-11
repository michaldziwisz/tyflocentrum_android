package net.tyflopodcast.tyflocentrum.ui.common

import android.app.Application
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.tyflopodcast.tyflocentrum.core.AppContainer
import net.tyflopodcast.tyflocentrum.core.model.*
import net.tyflopodcast.tyflocentrum.core.network.*
import net.tyflopodcast.tyflocentrum.ui.LocalAppContainer
import net.tyflopodcast.tyflocentrum.ui.screens.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

class ContentTimeActiveScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mode = AtomicInteger(0)
    private val now = AtomicLong(System.currentTimeMillis())
    private val ledger = CopyOnWriteArrayList<String>()
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val modified = "2026-10-07T00:00:00"

    private fun metadata(audio: Boolean, phase: Int): String = when (phase) {
        0, 4 -> "null"
        3 -> "false"
        else -> if (audio) """{"schema_version":1,"audio_status":"ready","duration_seconds":${if (phase == 1) 61 else 122},"generated_at":"${Instant.ofEpochMilli(now.get())}"}"""
        else """{"schema_version":1,"text_status":"ready","word_count":${if (phase == 1) 400 else 600},"reading_minutes":${if (phase == 1) 2 else 3}}"""
    }
    private fun summary(audio: Boolean, page: Boolean, phase: Int, id: Int = 7): String = """{"id":$id,"date":"2026-10-08T12:00:00","modified_gmt":"$modified","title":{"rendered":"${if (audio) "Podcast $id" else if (page) "Strona $id" else "Artykuł $id"}"},"link":"https://example.test/$id","tyflocentrum":${metadata(audio,phase)}}"""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); val u = request.url; val phase = mode.get()
        ledger += "phase=$phase $u cache=${request.header("Cache-Control")}"
        val audio = u.host == "tyflopodcast.net"
        val page = u.queryParameter("type") == "pages" || u.encodedPath.contains("pages")
        val include=(u.queryParameter("include") ?: u.queryParameter("ids"))?.split(",")?.map { it.toInt() }
        val perPage=u.queryParameter("per_page")?.toInt() ?: 70
        val pageNumber=u.queryParameter("page")?.toInt() ?: 1
        val ids=include ?: (1..70).drop((pageNumber-1)*perPage).take(perPage)
        val payload = when {
            u.encodedPath == "/v1/metadata" -> """{"schema_version":1,"source":"tyfloswiat.pl","type":"${if(page) "pages" else "posts"}","items":[${ids.joinToString(",") { id -> """{"id":$id,"modified_gmt":"$modified","freshness":"fresh","checked_at":"${Instant.ofEpochMilli(now.get())}","tyflocentrum":${metadata(false,phase)}}""" }}]}"""
            u.encodedPath.endsWith("/pages/99") -> """{"id":99,"date":"2026-10-08","title":{"rendered":"Numer"},"content":{"rendered":""},"excerpt":{"rendered":""},"guid":{"rendered":"https://example.test/99"}}"""
            else -> "[${ids.joinToString(",") { summary(audio,page,phase,it) }}]"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("controlled fixture")
            .header("X-WP-TotalPages",((70+perPage-1)/perPage).toString()).body(payload.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun retrofit(url: String) = Retrofit.Builder().baseUrl(url).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()

    private fun ax(title: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isClickable && node.contentDescription?.contains(title) == true) found = node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return found
    }
    private lateinit var apparatus: RefreshAccessibilityHarness
    private val focusTrace = java.util.concurrent.atomic.AtomicReference<RefreshFocusTrace?>()
    private fun clickRefresh() {
        compose.waitUntil(5_000) { ax("Odśwież")?.isEnabled == true }
        compose.onNode(hasContentDescription("Odśwież") and isEnabled()).assertHasClickAction()
        apparatus.click("Odśwież")
    }
    private fun row(title: String) = compose.onNode(hasContentDescription(title, substring = true) and hasClickAction())
    // Czas jest częścią nazwy wiersza, nie jego stanem.
    private fun waitLabel(title: String, label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(title,substring=true) and
                hasContentDescription(label,substring=true) and hasClickAction())
                .fetchSemanticsNodes().size == 1
        }
        compose.waitUntil(5_000) { ax(title)?.contentDescription?.toString()?.contains(label) == true }
    }
    private fun assertNoRowState(title: String) =
        assertNull("Wiersz treści nie może mieć stateDescription: $title",
            requireNotNull(ax(title)).stateDescription?.toString())
    /** Kontrakt kolejności: typ treści, tytuł, czas, data. Zwraca nazwę bez segmentu czasu. */
    private fun nameSkeleton(name: String, label: String): List<String> {
        val parts=name.split(", ")
        assertTrue("Nazwa bez miejsca na czas przed datą: $name",parts.size>=3)
        assertEquals("Czas bezpośrednio przed datą: $name",label,parts[parts.size-2])
        assertEquals("Czas dokładnie raz w nazwie: $name",1,parts.count { it==label })
        return parts.filterIndexed { index,_ -> index!=parts.size-2 }
    }


    private val physicalReader: Boolean
        get() = InstrumentationRegistry.getArguments().getString("physicalReader") == "true"

    private fun returnFromBackground() {
        if (physicalReader) {
            val activity = compose.activity
            assertTrue(apparatus.automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            Thread.sleep(1_500)
            apparatus.dump("background", includeCompose = false)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            focusTrace.get()?.markReturn()
            context.startActivity(android.content.Intent(context, activity.javaClass)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            compose.waitUntil(10_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
            assertSame("Powrót do tej samej aktywności", activity, compose.activity)
        } else {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(350)
            focusTrace.get()?.markReturn()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    private fun scenario(surface: String, resume: Boolean, windowOnly: Boolean = false) {
        apparatus=RefreshAccessibilityHarness(compose,"scroll-$surface-${if(windowOnly) "window-only" else if(resume) "resume" else "manual"}")
        apparatus.withTouchExploration { measure(surface,resume,windowOnly) }
    }
    private fun measure(surface: String, resume: Boolean, windowOnly: Boolean) {
        val store=ContentTimeStore(RetrofitTimeTransport.create(client,json),clock={now.get()})
        val repository=TyfloRepository(retrofit("https://tyflopodcast.net/wp-json/").create(),retrofit("https://tyfloswiat.pl/wp-json/").create(),retrofit("https://kontakt.tyflopodcast.net/").create(),client,store)
        val container=(compose.activity.application as net.tyflopodcast.tyflocentrum.TyflocentrumApplication).appContainer
        compose.runOnUiThread {
            for((field,value) in listOf("repository" to repository,"contentTimes" to store))
                AppContainer::class.java.getDeclaredField(field).apply { isAccessible=true }.set(container,value)
        }
        runBlocking {
            container.preferencesRepository.favoritesFlow.first().forEach { container.preferencesRepository.removeFavorite(it) }
            if(surface=="favorites") for(id in 1..70) {
                container.preferencesRepository.toggleFavorite(FavoriteItem.PodcastFavorite(json.decodeFromString(summary(true,false,0,id))))
                container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,false,0,id)),FavoriteArticleOrigin.POST))
                container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,true,0,id)),FavoriteArticleOrigin.PAGE))
            }
        }
        lateinit var testNav: androidx.navigation.NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { MaterialTheme {
                val nav=rememberNavController()
                testNav=nav
                androidx.navigation.compose.NavHost(nav,startDestination="fixture") {
                composable("articleDetail/{id}/{origin}") { androidx.compose.material3.Text("Cel testu otwarcia artykułu") }
                composable("fixture") {
                when(surface) {
                    "news" -> NewsScreen(nav,RootDestination.NEWS)
                    "podcasts" -> PodcastListScreen(nav,"Podcasty",null)
                    "podcast-category" -> PodcastListScreen(nav,"Kategoria",3)
                    "articles" -> ArticleListScreen(nav,"Artykuły",null)
                    "article-category" -> ArticleListScreen(nav,"Kategoria",3)
                    "search" -> SearchScreen(nav,RootDestination.SEARCH)
                    "favorites" -> FavoritesScreen(nav)
                    "issue" -> MagazineIssueScreen(nav,99)
                }
                } }
            } }
        }
        if(surface=="search") {
            compose.runOnIdle {
                fun fill(v:View) { if(v is EditText) v.setText("Podcast Artykuł"); if(v is ViewGroup) for(i in 0 until v.childCount) fill(v.getChildAt(i)) }
                fill(compose.activity.window.decorView)
            }
            compose.onNode(hasContentDescription("Szukaj") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).performTouchInput { click() }
        }
        val playback=ActivePlaybackProbe(container.playerController)
        try {
            // Produkt trzyma czas w nazwie wiersza, nie w StateDescription: bootstrap zgodny z waitLabel.
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Czas niedostępny",substring=true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            val list=compose.onNode(hasScrollToIndexAction())
            apparatus.dump("initial-viewport")
            val targetId=if(surface=="news") 7 else 17
            val title=when(surface) { "news" -> "Podcast $targetId"; "podcasts","podcast-category","search" -> "Podcast 17"; "issue","favorites" -> "Strona 17"; else -> "Artykuł 17" }
            assertNull("Wiersz poza początkowym viewportem",ax(title))
            if(surface in listOf("news","podcasts","podcast-category","articles","article-category")) {
                list.performScrollToNode(hasContentDescription("Wczytaj starsze treści"))
                compose.onNode(hasContentDescription("Wczytaj starsze treści")).performTouchInput { click() }
                compose.waitUntil(15_000) { ledger.any { Regex("[?&]page=2(?:&|$)").containsMatchIn(it.substringBefore(" cache=")) } }
                compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Odśwież") and isEnabled()).fetchSemanticsNodes().size==1 }
            }
            // Prawdziwa akcja przewinięcia listy, nie podmiana LazyListState w aplikacji.
            list.performScrollToNode(hasContentDescription(title,substring=true))
            waitLabel(title,"Czas niedostępny")
            playback.start()
            compose.waitUntil(30_000) { playback.ready() }
            val baseline=playback.begin()
            Thread.sleep(3_000)
            val baselineFailures=playback.failures(baseline)
            apparatus.write("playback-baseline",org.json.JSONObject().put("failures",org.json.JSONArray(baselineFailures)).toString())
            assertTrue("Odtwarzanie bez odświeżania: $baselineFailures",baselineFailures.isEmpty())
            // Miniodtwarzacz już jest widoczny przed bazowym pomiarem geometrii.
            compose.waitForIdle()
            if (physicalReader) apparatus.focusByReader(title,true) else apparatus.focus(title,true)
            val pid=Process.myPid()
            val skeleton=nameSkeleton(ax(title)!!.contentDescription.toString(),"Czas niedostępny")
            assertNoRowState(title)
            val id=row(title).fetchSemanticsNode().id
            val bounds=row(title).fetchSemanticsNode().boundsInRoot
            val scrollBefore=list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Rzeczywista niezerowa pozycja",scrollBefore>0f)
            val actions=ax(title)!!.actionList.map { it.id to it.label?.toString() }.filterNot { it.first in listOf(64,128) }
            val announcements=Collections.synchronizedList(mutableListOf<String>())
            apparatus.automation.setOnAccessibilityEventListener { event ->
                focusTrace.get()?.record(event)
                if(event.eventType==android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) announcements+=event.text.toString()
                if(event.text.any { it.toString().contains("nowe treści",true) }) announcements+=event.text.toString()
            }
            // gated=true: zwykła nawigacja i ręczne odświeżenie — fokus nadal blokuje PASS.
            // gated=false: wyłącznie przebieg po Home/powrocie. Wynik zapisujemy w dowodach
            // z jawnym polem, ale NIE zaliczamy go jako PASS i nie przywracamy fokusu z testu.
            fun verifyFocus(label: String, gated: Boolean) {
                // Stały budżet obserwacji, bez kończenia po pierwszym poprawnym odczycie.
                Thread.sleep(if (physicalReader) 8_000 else 400)
                apparatus.automation.waitForIdle(300, 5_000)
                val trace = requireNotNull(focusTrace.getAndSet(null))
                val node = ax(title)
                val result = trace.result(node, completed = true)
                val failures = result.getJSONArray("failures").length()
                result.put("gated", gated)
                result.put("afterReturnFromHome", !gated)
                result.put("sameRowFocusAfterReturn", node?.isAccessibilityFocused ?: false)
                result.put("verdict", if (failures == 0) "OK" else if (gated) "FAIL" else "ZNANE_OGRANICZENIE")
                apparatus.write(label, result.toString(2))
                println("FOCUS_VERDICT row=$title label=$label gated=$gated failures=$failures")
                if (gated) assertEquals("Niepoprawna historia fokusu: $result", 0, failures)
            }
            val before=org.json.JSONObject().put("pid",pid).put("row",title).put("id",id).put("scroll",scrollBefore).put("bounds",bounds.toString())
                .put("store",System.identityHashCode(store)).put("repository",System.identityHashCode(repository)).put("client",System.identityHashCode(client)).put("activity",System.identityHashCode(compose.activity))
            apparatus.write("scroll-before",before.toString(2))
            apparatus.dump("scrolled-before")
            if (windowOnly) {
                val calls = ledger.size
                focusTrace.set(RefreshFocusTrace(requireNotNull(ax(title)), resume = true))
                returnFromBackground()
                // Macierz Home nie blokuje TYLKO na asercjach fokusu po powrocie.
                verifyFocus("window-only-focus", gated = false)
                assertEquals("Bez odświeżenia przed upływem progu", calls, ledger.size)
                assertEquals(id, row(title).fetchSemanticsNode().id)
                assertEquals("Nazwa poza czasem bez zmian", skeleton,
                    nameSkeleton(requireNotNull(ax(title)).contentDescription.toString(), "Czas niedostępny"))
                assertNoRowState(title)
                apparatus.dump("window-only-returned")
                return
            }
            for(phase in 1..4) {
                compose.waitUntil(30_000) { playback.settled() }
                val window=playback.begin()
                mode.set(phase)
                val calls=ledger.size
                focusTrace.set(RefreshFocusTrace(requireNotNull(ax(title)), resume))
                if(resume) {
                    now.addAndGet(120_001)
                    returnFromBackground()
                } else clickRefresh()
                val label=if(phase>=3) "Czas niedostępny" else if(title.startsWith("Podcast"))
                    if(phase==1) "Czas trwania: 1 minuta 1 sekunda" else "Czas trwania: 2 minuty 2 sekundy"
                    else if(phase==1) "Czytanie: około 2 minut" else "Czytanie: około 3 minut"
                waitLabel(title,label)
                compose.waitUntil(15_000) { ledger.size>calls && compose.onAllNodes(hasContentDescription("Odśwież") and isEnabled()).fetchSemanticsNodes().size==1 }
                Thread.sleep(400)
                val node=row(title).fetchSemanticsNode()
                assertEquals(id,node.id)
                assertEquals(bounds.topLeft,node.boundsInRoot.topLeft)
                // Zakres semantyczny jest znormalizowany wysokością wiersza; kotwicą jest geometria tego samego ID.
                assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()>0f)
                verifyFocus("phase-$phase-focus", gated = !resume)
                val ax=ax(title)!!
                // Fokus po Home/powrocie (resume) jest znanym ograniczeniem i nie blokuje;
                // zwykła nawigacja oraz ręczne odświeżenie nadal muszą zachować fokus.
                if (resume) println("FOCUS_AFTER_RETURN row=$title phase=$phase focused=${ax.isAccessibilityFocused}")
                else assertTrue("Fokus zachowany bez ponownego ustawiania",ax.isAccessibilityFocused)
                assertEquals(actions,ax.actionList.map { it.id to it.label?.toString() }.filterNot { it.first in listOf(64,128) })
                assertEquals("Nazwa poza czasem bez zmian",skeleton,nameSkeleton(ax.contentDescription.toString(),label))
                assertNoRowState(title)
                assertEquals(0,ax.childCount)
                val visible=if(phase>=3) label else if(title.startsWith("Podcast")) if(phase==1) "Czas trwania: 1 min 1 s" else "Czas trwania: 2 min 2 s" else if(phase==1) "Czytanie: około 2 min" else "Czytanie: około 3 min"
                compose.onAllNodes(hasText(visible,substring=true) and hasAnyAncestor(hasContentDescription(title,substring=true)),useUnmergedTree=true).assertCountEquals(1)
                assertEquals(pid,Process.myPid())
                assertTrue("Bez nowych treści: $announcements",announcements.isEmpty())
                if (physicalReader) compose.waitUntil(30_000) { playback.settled() }
                val failures=playback.failures(window)
                apparatus.write("phase-$phase-playback-verdict",org.json.JSONObject().put("failures",org.json.JSONArray(failures)).toString())
                assertTrue("Odtwarzanie: $failures",failures.isEmpty())
                apparatus.write("phase-$phase-scroll",org.json.JSONObject(before.toString()).put("id",node.id).put("scroll",list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()).put("bounds",node.boundsInRoot.toString()).put("focus",ax.isAccessibilityFocused).put("focusGated",!resume).put("name",ax.contentDescription.toString()).put("stateDescription",ax.stateDescription?.toString()).toString(2))
                apparatus.dump("phase-$phase-after")
            }
            // Akcja istniejącego wiersza z prawdziwego AX; nie callback testowy.
            if(surface in listOf("issue","favorites")) {
                assertTrue(ax(title)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.waitUntil(5_000) { testNav.currentBackStackEntry?.arguments?.getString("id")=="17" }
                assertEquals("page",testNav.currentBackStackEntry?.arguments?.getString("origin"))
                apparatus.write("existing-action","ACTION_CLICK: articleDetail/17/page")
            } else {
            val copy=ax(title)!!.actionList.single { it.label?.toString()=="Skopiuj link" }
            assertTrue(ax(title)!!.performAction(copy.id))
            compose.waitUntil(5_000) {
                var text=""
                compose.runOnUiThread { text=(compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text.toString() }
                text=="https://example.test/$targetId"
            }
            apparatus.write("existing-action","ACTION custom Skopiuj link: clipboard=https://example.test/$targetId")
            }
            compose.waitUntil(30_000) { playback.settled() }
            val negative=playback.negativeStop()
            apparatus.write("negative-stop",org.json.JSONObject().put("detected",negative.isNotEmpty()).put("failures",org.json.JSONArray(negative)).toString(2))
            assertTrue("Walidator musi wykryć rzeczywiste zatrzymanie",negative.isNotEmpty())
        } finally {
            focusTrace.getAndSet(null)?.let { trace ->
                apparatus.write("unfinished-focus", trace.result(
                    apparatus.automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY), completed = false,
                ).toString(2))
            }
            apparatus.write("player-samples-events",playback.dump())
            playback.close()
            apparatus.dump("finally")
            apparatus.write("ledger",ledger.joinToString("\n"))
            apparatus.automation.setOnAccessibilityEventListener(null)
        }
    }
    @Test fun readerNewsHomeWithoutRefresh() = scenario("news", true, windowOnly = true)

    @Test fun activePlaybackBaselineWithoutRefresh() {
        apparatus=RefreshAccessibilityHarness(compose,"playback-baseline-only")
        apparatus.withTouchExploration {
            val container=(compose.activity.application as net.tyflopodcast.tyflocentrum.TyflocentrumApplication).appContainer
            compose.setContent { MaterialTheme { androidx.compose.material3.Text("Pomiar bazowy bez odświeżania") } }
            val playback=ActivePlaybackProbe(container.playerController)
            try {
                playback.start()
                compose.waitUntil(30_000) { playback.ready() }
                val window=playback.begin()
                Thread.sleep(30_000)
                if (physicalReader) compose.waitUntil(30_000) { playback.settled() }
                val failures=playback.failures(window)
                apparatus.write("baseline-verdict",org.json.JSONObject().put("failures",org.json.JSONArray(failures)).toString())
                val negative=playback.negativeStop()
                apparatus.write("negative-stop",org.json.JSONObject().put("detected",negative.isNotEmpty()).put("failures",org.json.JSONArray(negative)).toString())
                assertTrue("Zatrzymanie musi zostać wykryte",negative.isNotEmpty())
                assertTrue("Odtwarzanie bez operacji listy: $failures",failures.isEmpty())
            } finally {
                apparatus.write("player-samples-events",playback.dump())
                playback.close()
                apparatus.dump("finally")
            }
        }
    }
    @Test fun activeScrollNewsManual() = scenario("news",false)
    @Test fun activeScrollNewsResume() = scenario("news",true)
    @Test fun activeScrollPodcastsManual() = scenario("podcasts",false)
    @Test fun activeScrollPodcastsResume() = scenario("podcasts",true)
    @Test fun activeScrollPodcastCategoryManual() = scenario("podcast-category",false)
    @Test fun activeScrollPodcastCategoryResume() = scenario("podcast-category",true)
    @Test fun activeScrollArticlesManual() = scenario("articles",false)
    @Test fun activeScrollArticlesResume() = scenario("articles",true)
    @Test fun activeScrollArticleCategoryManual() = scenario("article-category",false)
    @Test fun activeScrollArticleCategoryResume() = scenario("article-category",true)
    @Test fun activeScrollSearchManual() = scenario("search",false)
    @Test fun activeScrollSearchResume() = scenario("search",true)
    @Test fun activeScrollFavoritesManual() = scenario("favorites",false)
    @Test fun activeScrollFavoritesResume() = scenario("favorites",true)
    @Test fun activeScrollIssueManual() = scenario("issue",false)
    @Test fun activeScrollIssueResume() = scenario("issue",true)
}
