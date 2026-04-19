package org.tyflocentrum.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.tyflocentrum.android.core.AppContainer
import org.tyflocentrum.android.core.model.FavoriteArticleOrigin
import org.tyflocentrum.android.ui.common.RootDestination
import org.tyflocentrum.android.ui.screens.ArticleDetailScreen
import org.tyflocentrum.android.ui.screens.ArticleListScreen
import org.tyflocentrum.android.ui.screens.ArticlesHomeScreen
import org.tyflocentrum.android.ui.screens.ContactMenuScreen
import org.tyflocentrum.android.ui.screens.ContactTextMessageScreen
import org.tyflocentrum.android.ui.screens.ContactVoiceMessageScreen
import org.tyflocentrum.android.ui.screens.FavoritesScreen
import org.tyflocentrum.android.ui.screens.MagazineScreen
import org.tyflocentrum.android.ui.screens.MagazineIssueScreen
import org.tyflocentrum.android.ui.screens.NewsScreen
import org.tyflocentrum.android.ui.screens.PlayerScreen
import org.tyflocentrum.android.ui.screens.PodcastCommentsScreen
import org.tyflocentrum.android.ui.screens.PodcastDetailScreen
import org.tyflocentrum.android.ui.screens.PodcastListScreen
import org.tyflocentrum.android.ui.screens.PodcastsHomeScreen
import org.tyflocentrum.android.ui.screens.RadioHomeScreen
import org.tyflocentrum.android.ui.screens.SearchScreen
import org.tyflocentrum.android.ui.screens.SettingsScreen

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

private const val NULL_ROUTE_SEGMENT = "__null__"

object AppRoutes {
    const val NEWS = "news"
    const val PODCASTS = "podcasts"
    const val ARTICLES = "articles"
    const val SEARCH = "search"
    const val RADIO = "radio"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val PODCAST_LIST = "podcastList/{title}/{categoryId}"
    const val ARTICLE_LIST = "articleList/{title}/{categoryId}"
    const val PODCAST_DETAIL = "podcastDetail/{id}"
    const val ARTICLE_DETAIL = "articleDetail/{id}/{origin}"
    const val MAGAZINE = "magazine"
    const val MAGAZINE_ISSUE = "magazineIssue/{id}"
    const val COMMENTS = "comments/{postId}"
    const val PLAYER = "player/{url}/{title}/{subtitle}/{live}/{postId}/{seekMs}"
    const val CONTACT_MENU = "contactMenu"
    const val CONTACT_TEXT = "contactText"
    const val CONTACT_VOICE = "contactVoice"

    fun podcastList(title: String, categoryId: Int) = "podcastList/${title.encoded()}/$categoryId"
    fun articleList(title: String, categoryId: Int) = "articleList/${title.encoded()}/$categoryId"
    fun podcastDetail(id: Int) = "podcastDetail/$id"
    fun articleDetail(id: Int, origin: FavoriteArticleOrigin) = "articleDetail/$id/${origin.name.lowercase()}"
    fun magazineIssue(id: Int) = "magazineIssue/$id"
    fun comments(postId: Int) = "comments/$postId"
    fun player(
        url: String,
        title: String,
        subtitle: String?,
        live: Boolean,
        postId: Int?,
        seekMs: Long? = null
    ) = buildString {
        append("player/")
        append(url.encoded())
        append("/")
        append(title.encoded())
        append("/")
        append(subtitle?.takeUnless { it.isBlank() }?.encoded() ?: NULL_ROUTE_SEGMENT)
        append("/")
        append(if (live) "1" else "0")
        append("/")
        append(postId ?: -1)
        append("/")
        append(seekMs ?: -1L)
    }
}

private fun String.encoded(): String = Uri.encode(this)

@Composable
fun TyflocentrumApp(
    appContainer: AppContainer
) {
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        val navController = rememberNavController()
        val backStackEntry = navController.currentBackStackEntryAsState().value
        val rootDestination = RootDestination.fromDestination(backStackEntry?.destination)

        NavHost(
            navController = navController,
            startDestination = AppRoutes.NEWS
        ) {
            composable(AppRoutes.NEWS) {
                NewsScreen(navController = navController, rootDestination = rootDestination ?: RootDestination.NEWS)
            }
            composable(AppRoutes.PODCASTS) {
                PodcastsHomeScreen(navController = navController, rootDestination = rootDestination ?: RootDestination.PODCASTS)
            }
            composable(AppRoutes.ARTICLES) {
                ArticlesHomeScreen(navController = navController, rootDestination = rootDestination ?: RootDestination.ARTICLES)
            }
            composable(AppRoutes.SEARCH) {
                SearchScreen(navController = navController, rootDestination = rootDestination ?: RootDestination.SEARCH)
            }
            composable(AppRoutes.RADIO) {
                RadioHomeScreen(navController = navController, rootDestination = rootDestination ?: RootDestination.RADIO)
            }
            composable(AppRoutes.FAVORITES) {
                FavoritesScreen(navController = navController)
            }
            composable(AppRoutes.SETTINGS) {
                SettingsScreen(navController = navController)
            }
            composable(
                route = AppRoutes.PODCAST_LIST,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType },
                    navArgument("categoryId") { type = NavType.IntType }
                )
            ) { entry ->
                PodcastListScreen(
                    navController = navController,
                    title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                    categoryId = entry.arguments?.getInt("categoryId")
                )
            }
            composable(
                route = AppRoutes.ARTICLE_LIST,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType },
                    navArgument("categoryId") { type = NavType.IntType }
                )
            ) { entry ->
                ArticleListScreen(
                    navController = navController,
                    title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                    categoryId = entry.arguments?.getInt("categoryId")
                )
            }
            composable(
                route = AppRoutes.PODCAST_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.IntType })
            ) { entry ->
                PodcastDetailScreen(
                    navController = navController,
                    podcastId = entry.arguments?.getInt("id") ?: return@composable
                )
            }
            composable(
                route = AppRoutes.ARTICLE_DETAIL,
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("origin") { type = NavType.StringType }
                )
            ) { entry ->
                ArticleDetailScreen(
                    navController = navController,
                    articleId = entry.arguments?.getInt("id") ?: return@composable,
                    origin = when (entry.arguments?.getString("origin")) {
                        "page" -> FavoriteArticleOrigin.PAGE
                        else -> FavoriteArticleOrigin.POST
                    }
                )
            }
            composable(AppRoutes.MAGAZINE) {
                MagazineScreen(navController = navController)
            }
            composable(
                route = AppRoutes.MAGAZINE_ISSUE,
                arguments = listOf(navArgument("id") { type = NavType.IntType })
            ) { entry ->
                MagazineIssueScreen(
                    navController = navController,
                    issueId = entry.arguments?.getInt("id") ?: return@composable
                )
            }
            composable(
                route = AppRoutes.COMMENTS,
                arguments = listOf(navArgument("postId") { type = NavType.IntType })
            ) { entry ->
                PodcastCommentsScreen(
                    navController = navController,
                    postId = entry.arguments?.getInt("postId") ?: return@composable
                )
            }
            composable(
                route = AppRoutes.PLAYER,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("subtitle") { type = NavType.StringType },
                    navArgument("live") { type = NavType.StringType },
                    navArgument("postId") { type = NavType.IntType },
                    navArgument("seekMs") { type = NavType.LongType }
                )
            ) { entry ->
                PlayerScreen(
                    navController = navController,
                    url = Uri.decode(entry.arguments?.getString("url").orEmpty()),
                    title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                    subtitle = Uri.decode(entry.arguments?.getString("subtitle").orEmpty())
                        .takeUnless { it == NULL_ROUTE_SEGMENT || it.isBlank() },
                    isLive = entry.arguments?.getString("live") == "1",
                    postId = entry.arguments?.getInt("postId")?.takeIf { it >= 0 },
                    seekMs = entry.arguments?.getLong("seekMs")?.takeIf { it >= 0L }
                )
            }
            composable(AppRoutes.CONTACT_MENU) {
                ContactMenuScreen(navController = navController)
            }
            composable(AppRoutes.CONTACT_TEXT) {
                ContactTextMessageScreen(navController = navController)
            }
            composable(AppRoutes.CONTACT_VOICE) {
                ContactVoiceMessageScreen(navController = navController)
            }
        }
    }
}
