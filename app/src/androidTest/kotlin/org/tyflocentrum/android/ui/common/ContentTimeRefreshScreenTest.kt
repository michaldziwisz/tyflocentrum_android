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
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.Collections
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

/** Prawdziwe ekrany, repo i eksport Android AX. Serwer zmienia się wyłącznie
 * w jawnej fazie testu, nigdy po osiągnięciu liczby żądań. Brak sieci produkcyjnej. */
class ContentTimeRefreshScreenTest {
    companion object {
        private var sharedContainer: AppContainer? = null
        @JvmStatic @org.junit.AfterClass fun pausePlayer() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { sharedContainer?.playerController?.pause() }
        }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mode = AtomicInteger(0)
    private val now = AtomicLong(System.currentTimeMillis())
    private val ledger = Collections.synchronizedList(mutableListOf<String>())
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val modified = "2026-10-07T00:00:00"

    private fun metadata(audio: Boolean, phase: Int): String = when (phase) {
        0, 4 -> "null"
        3 -> "false"
        else -> if (audio) """{"schema_version":1,"audio_status":"ready","duration_seconds":${if (phase == 1) 61 else 122},"generated_at":"${Instant.ofEpochMilli(now.get())}"}"""
        else """{"schema_version":1,"text_status":"ready","word_count":${if (phase == 1) 400 else 600},"reading_minutes":${if (phase == 1) 2 else 3}}"""
    }
    private fun summary(audio: Boolean, page: Boolean, phase: Int): String = """{"id":7,"date":"2026-10-08T12:00:00","modified_gmt":"$modified","title":{"rendered":"${if (audio) "Stały podcast" else if (page) "Stała strona" else "Stały artykuł"}"},"link":"https://example.test/7","tyflocentrum":${metadata(audio,phase)}}"""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); val u = request.url; val phase = mode.get()
        ledger += "phase=$phase $u cache=${request.header("Cache-Control")}"
        val audio = u.host == "tyflopodcast.net"
        val page = u.queryParameter("type") == "pages" || u.encodedPath.contains("pages")
        val payload = when {
            u.encodedPath == "/v1/metadata" -> """{"schema_version":1,"source":"tyfloswiat.pl","type":"${if (page) "pages" else "posts"}","items":[{"id":7,"modified_gmt":"$modified","freshness":"fresh","checked_at":"${Instant.ofEpochMilli(now.get())}","tyflocentrum":${metadata(false,phase)}}]}"""
            u.encodedPath.endsWith("/pages/99") -> """{"id":99,"date":"2026-10-08","title":{"rendered":"Numer"},"content":{"rendered":""},"excerpt":{"rendered":""},"guid":{"rendered":"https://example.test/99"}}"""
            else -> "[${summary(audio,page,phase)}]"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("controlled fixture")
            .header("X-WP-TotalPages","1").body(payload.toResponseBody("application/json".toMediaType())).build()
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
    private fun requiredState(title: String): String =
        requireNotNull(requireNotNull(ax(title)) { "Brak węzła AX dla $title" }.stateDescription) {
            "Brak StateDescription na węźle $title"
        }.toString()

    private fun requiredName(title: String): String =
        requireNotNull(requireNotNull(ax(title)) { "Brak węzła AX dla $title" }.contentDescription) {
            "Brak contentDescription na węźle $title"
        }.toString()

    private fun clickRefresh() {
        compose.waitUntil(5_000) { ax("Odśwież")?.isEnabled == true }
        compose.onNode(hasContentDescription("Odśwież") and isEnabled()).assertHasClickAction()
        apparatus.click("Odśwież")
    }
    private fun row(title: String) = compose.onNode(hasContentDescription(title, substring = true) and hasClickAction())
    private fun waitLabel(title: String, label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(title,substring=true) and hasStateDescription(label))
                .fetchSemanticsNodes().size == 1
        }
        compose.waitUntil(5_000) { ax(title)?.stateDescription?.toString() == label }
    }

    private fun scenario(surface: String, resume: Boolean = false) {
        apparatus = RefreshAccessibilityHarness(compose, "$surface-${if (resume) "resume" else "manual"}")
        apparatus.withTouchExploration { measuredScenario(surface, resume) }
    }

    private fun measuredScenario(surface: String, resume: Boolean) {
        val store = ContentTimeStore(RetrofitTimeTransport.create(client,json),clock={now.get()})
        val repository = TyfloRepository(retrofit("https://tyflopodcast.net/wp-json/").create(),
            retrofit("https://tyfloswiat.pl/wp-json/").create(),retrofit("https://kontakt.tyflopodcast.net/").create(),client,store)
        lateinit var container: AppContainer
        compose.runOnUiThread {
            container = (compose.activity.application as net.tyflopodcast.tyflocentrum.TyflocentrumApplication).appContainer
            sharedContainer = container
            // Tylko podmiana zależności w testach. Produkcyjny AppContainer bez zmian.
            for ((field,value) in listOf("repository" to repository, "contentTimes" to store)) {
                AppContainer::class.java.getDeclaredField(field).apply { isAccessible=true }.set(container,value)
            }
        }
        if (surface == "favorites") runBlocking {
            container.preferencesRepository.favoritesFlow.first().forEach { container.preferencesRepository.removeFavorite(it) }
            // Zapis starych ulubionych bez metadanych, przed pierwszym ekranem.
            container.preferencesRepository.toggleFavorite(FavoriteItem.PodcastFavorite(json.decodeFromString(summary(true,false,0))))
            container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,false,0)),FavoriteArticleOrigin.POST))
            container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,true,0)),FavoriteArticleOrigin.PAGE))
        }
        val pid=Process.myPid()
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                MaterialTheme {
                    val nav=rememberNavController()
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
                }
            }
        }
        if(surface=="search") {
            compose.runOnIdle {
                fun fill(view: View) {
                    if(view is EditText) view.setText("Stały")
                    if(view is ViewGroup) for(i in 0 until view.childCount) fill(view.getChildAt(i))
                }
                fill(compose.activity.window.decorView)
            }
            compose.onNode(hasContentDescription("Szukaj") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button)).performClick()
        }
        val titles=when(surface) {
            "news","search" -> listOf("Stały podcast","Stały artykuł")
            "favorites" -> listOf("Stała strona","Stały artykuł","Stały podcast")
            "podcasts","podcast-category" -> listOf("Stały podcast")
            "issue" -> listOf("Stała strona")
            else -> listOf("Stały artykuł")
        }
        try {
            titles.forEach { waitLabel(it,"Czas niedostępny") }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Odśwież") and isEnabled() and hasClickAction()).fetchSemanticsNodes().size == 1 }
            // Każdy wiersz i ekran pozostają zamontowane przez cały scenariusz.
            val identities=titles.associateWith { row(it).fetchSemanticsNode().id }
            apparatus.dump("initial")
            val androidNodes=titles.associateWith { requireNotNull(ax(it)) }
            val stableNames=titles.associateWith { requireNotNull(ax(it)).contentDescription.toString() }
            val androidActions=titles.associateWith { requireNotNull(ax(it)).actionList.map { a -> a.id }.filter { a -> a != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && a != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS } }
            val actions=titles.associateWith { row(it).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { a -> a.label } }
            val original=repository.peekNewsScreenCache()?.items
            val bounds=titles.associateWith { row(it).fetchSemanticsNode().boundsInRoot }
            val announcements=Collections.synchronizedList(mutableListOf<String>())
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.setOnAccessibilityEventListener { event ->
                if(event.eventType==android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) announcements+=event.text.toString()
            }
            if(!resume) {
                apparatus.focus(titles.first(), substring = true)
                compose.waitUntil(5_000) { ax(titles.first())?.isAccessibilityFocused == true }
            }
            val calls=ledger.size
            if(resume) {
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitForIdle()
                assertEquals("Powrót przed progiem",calls,ledger.size)
            }
            for(phase in 1..4) {
                val previousCalls=ledger.size
                mode.set(phase)
                // Udowodnij, że serwer nie zmienił wiersza przed akcją.
                if(phase==1) titles.forEach { waitLabel(it,"Czas niedostępny") }
                apparatus.dump("phase-$phase-before-refresh")
                if(resume) {
                    now.addAndGet(120_001)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                } else clickRefresh()
                compose.waitUntil(10_000) { ledger.size > previousCalls && compose.onAllNodes(hasContentDescription("Odśwież") and isEnabled() and hasClickAction()).fetchSemanticsNodes().size == 1 }
                titles.forEach { title ->
                    val label=if(phase>=3) "Czas niedostępny" else if(title=="Stały podcast")
                        if(phase==1) "Czas trwania: 1 minuta 1 sekunda" else "Czas trwania: 2 minuty 2 sekundy"
                        else if(phase==1) "Czytanie: około 2 minut" else "Czytanie: około 3 minut"
                    waitLabel(title,label)
                    val node=row(title).fetchSemanticsNode()
                    assertEquals(identities[title],node.id)
                    assertEquals(actions[title],node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label })
                    val android=requireNotNull(ax(title))
                    assertTrue(android.isClickable)
                    assertTrue(android.isEnabled)
                    assertEquals(androidActions[title],android.actionList.map { it.id }.filter { it != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && it != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS })
                    // Window ID może zostać nadany ponownie przez Android po ON_STOP.
                    // Compose ID ma pozostać ten sam także po powrocie z tła.
                    if(!resume) assertEquals("Tożsamość Android AX",androidNodes[title],android)
                    if(title==titles.first()) assertEquals(bounds[title]!!.topLeft,node.boundsInRoot.topLeft)
                    assertEquals(stableNames[title],android.contentDescription.toString())
                    assertEquals(label,android.stateDescription?.toString())
                    assertFalse(android.contentDescription.toString().contains(label))
                    assertEquals(0,android.childCount)
                    val visible=if(phase>=3) label else if(title=="Stały podcast")
                        if(phase==1) "Czas trwania: 1 min 1 s" else "Czas trwania: 2 min 2 s"
                        else if(phase==1) "Czytanie: około 2 min" else "Czytanie: około 3 min"
                    compose.onAllNodes(hasText(visible,substring=true),useUnmergedTree=true).assertCountEquals(if(phase>=3) titles.size else if(title=="Stały podcast") 1 else titles.count { it!="Stały podcast" })
                    println("REFRESH_AX surface=$surface resume=$resume phase=$phase pid=$pid store=${System.identityHashCode(store)} repo=${System.identityHashCode(repository)} activity=${System.identityHashCode(compose.activity)} node=${node.id} name=${android.contentDescription}")
                }
                apparatus.dump("phase-$phase-after-refresh")
                apparatus.write("phase-$phase-store", store.state.value.toString())
                assertEquals(pid,Process.myPid())
                if(!resume) assertTrue(requireNotNull(ax(titles.first())).isAccessibilityFocused)
                assertTrue("Zmiana czasu nie ogłasza nowych wpisów: $announcements",announcements.isEmpty())
                if(original!=null) assertEquals(original,repository.peekNewsScreenCache()?.items)
            }
            // Jawnie wymagamy istniejącego węzła i istniejącego stanu: null nie może
            // zaliczyć porównania przez zamianę na tekst "null" po obu stronach.
            val stable=titles.associateWith { requiredState(it) }
            val beforeNoChange = ledger.size
            apparatus.dump("no-change-before-refresh")
            clickRefresh()
            compose.waitUntil(10_000) { ledger.size > beforeNoChange && compose.onAllNodes(hasContentDescription("Odśwież") and isEnabled() and hasClickAction()).fetchSemanticsNodes().size == 1 }
            compose.waitForIdle()
            apparatus.dump("no-change-after-refresh")
            assertEquals(stable,titles.associateWith { requiredState(it) })
            assertEquals(stableNames,titles.associateWith { requiredName(it) })
            titles.forEach { title ->
                assertEquals(identities[title],row(title).fetchSemanticsNode().id)
                assertEquals(actions[title],row(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label })
            }
            assertTrue(announcements.isEmpty())
            if (!resume) assertTrue(requireNotNull(ax(titles.first())).isAccessibilityFocused)
            println("REFRESH_PASS surface=$surface resume=$resume pid=$pid phases=4 noChange=true calls=${ledger.size}")
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setOnAccessibilityEventListener(null)
            apparatus.dump("finally")
            apparatus.write("ledger", ledger.joinToString("\n"))
            println("REFRESH_LEDGER $surface resume=$resume\n"+ledger.joinToString("\n"))
            // Odtwarzacz oraz DataStore są singletonami całego procesu testowego.
            // Każdy test ma te same dawne ulubione bez przełączania filtra w trakcie.
            if(surface=="favorites") runBlocking {
                container.preferencesRepository.toggleFavorite(FavoriteItem.PodcastFavorite(json.decodeFromString(summary(true,false,0))))
                container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,false,0)),FavoriteArticleOrigin.POST))
                container.preferencesRepository.toggleFavorite(FavoriteItem.ArticleFavorite(json.decodeFromString(summary(false,true,0)),FavoriteArticleOrigin.PAGE))
            }
        }
    }
    @Test fun newsManual() = scenario("news")
    @Test fun allPodcastsManual() = scenario("podcasts")
    @Test fun podcastCategoryManual() = scenario("podcast-category")
    @Test fun allArticlesManual() = scenario("articles")
    @Test fun articleCategoryManual() = scenario("article-category")
    @Test fun searchManual() = scenario("search")
    @Test fun oldFavoritesManual() = scenario("favorites")
    @Test fun issuePagesManual() = scenario("issue")
    @Test fun oldFavoritesResume() = scenario("favorites",true)
    @Test fun articleCategoryResume() = scenario("article-category",true)
    @Test fun allArticlesResume() = scenario("articles",true)
    @Test fun podcastCategoryResume() = scenario("podcast-category",true)
    @Test fun allPodcastsResume() = scenario("podcasts",true)
    @Test fun issuePagesResume() = scenario("issue",true)
    @Test fun searchResume() = scenario("search",true)
    @Test fun newsResume() = scenario("news",true)
}
