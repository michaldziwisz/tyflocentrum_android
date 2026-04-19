package org.tyflocentrum.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.tyflocentrum.android.core.model.AppSettings
import org.tyflocentrum.android.core.model.ContentKind
import org.tyflocentrum.android.core.model.FavoriteArticleOrigin
import org.tyflocentrum.android.core.model.FavoriteItem
import org.tyflocentrum.android.core.model.MagazineParser
import org.tyflocentrum.android.core.model.NewsItem
import org.tyflocentrum.android.core.model.SearchItem
import org.tyflocentrum.android.core.model.SearchRanking
import org.tyflocentrum.android.core.model.WpPostSummary
import org.tyflocentrum.android.core.network.NewsScreenCache
import org.tyflocentrum.android.core.network.PagedScreenCache
import org.tyflocentrum.android.ui.AppRoutes
import org.tyflocentrum.android.ui.LocalAppContainer
import org.tyflocentrum.android.ui.common.Announcement
import org.tyflocentrum.android.ui.common.AppScreenScaffold
import org.tyflocentrum.android.ui.common.ContentListItem
import org.tyflocentrum.android.ui.common.FilterChipRow
import org.tyflocentrum.android.ui.common.RootDestination
import org.tyflocentrum.android.ui.common.StatePane

private const val MAGAZINE_ROOT_PAGE_ID = 1409

@Composable
fun NewsScreen(
    navController: NavHostController,
    rootDestination: RootDestination
) {
    val appContainer = LocalAppContainer.current
    val cachedState = remember { appContainer.repository.peekNewsScreenCache() }
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val items = remember {
        mutableStateListOf<NewsItem>().apply {
            addAll(cachedState?.items.orEmpty())
        }
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(cachedState == null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var podcastPage by remember { mutableIntStateOf(cachedState?.nextPodcastPage ?: 1) }
    var articlePage by remember { mutableIntStateOf(cachedState?.nextArticlePage ?: 1) }
    var podcastTotalPages by remember { mutableStateOf(cachedState?.podcastTotalPages) }
    var articleTotalPages by remember { mutableStateOf(cachedState?.articleTotalPages) }

    fun syncCache() {
        appContainer.repository.storeNewsScreenCache(
            NewsScreenCache(
                items = items.toList(),
                nextPodcastPage = podcastPage,
                nextArticlePage = articlePage,
                podcastTotalPages = podcastTotalPages,
                articleTotalPages = articleTotalPages
            )
        )
    }

    fun mergeAndStore(next: List<NewsItem>, reset: Boolean) {
        val merged = (if (reset) emptyList() else items.toList()) + next
        val sorted = merged
            .distinctBy { it.uniqueId }
            .sortedWith(
                compareByDescending<NewsItem> { it.post.date }
                    .thenBy { it.kind.ordinal }
                    .thenByDescending { it.post.id }
            )
        items.clear()
        items.addAll(sorted)
        syncCache()
    }

    fun canLoadMore(): Boolean {
        val podcastsMore = podcastTotalPages == null || podcastPage <= (podcastTotalPages ?: Int.MAX_VALUE)
        val articlesMore = articleTotalPages == null || articlePage <= (articleTotalPages ?: Int.MAX_VALUE)
        return podcastsMore || articlesMore
    }

    fun load(reset: Boolean) {
        scope.launch {
            if (reset) {
                isLoading = true
                errorMessage = null
                podcastPage = 1
                articlePage = 1
                podcastTotalPages = null
                articleTotalPages = null
            } else {
                isLoadingMore = true
            }
            val currentPodcastPage = podcastPage
            val currentArticlePage = articlePage

            runCatching {
                val podcasts = async {
                    if (podcastTotalPages != null && currentPodcastPage > (podcastTotalPages ?: 0)) {
                        null
                    } else {
                        appContainer.repository.fetchPodcastSummariesPage(currentPodcastPage, perPage = 20)
                    }
                }
                val articles = async {
                    if (articleTotalPages != null && currentArticlePage > (articleTotalPages ?: 0)) {
                        null
                    } else {
                        appContainer.repository.fetchArticleSummariesPage(currentArticlePage, perPage = 20)
                    }
                }

                podcasts.await() to articles.await()
            }.onSuccess { (podcasts, articles) ->
                val merged = buildList {
                    podcasts?.items?.forEach { add(NewsItem(ContentKind.PODCAST, it)) }
                    articles?.items?.forEach { add(NewsItem(ContentKind.ARTICLE, it)) }
                }
                mergeAndStore(merged, reset)
                podcastTotalPages = podcasts?.totalPages ?: podcastTotalPages
                articleTotalPages = articles?.totalPages ?: articleTotalPages
                if (podcasts != null) podcastPage = currentPodcastPage + 1
                if (articles != null) articlePage = currentArticlePage + 1
                syncCache()
                errorMessage = if (items.isEmpty()) "Nie udało się pobrać danych. Spróbuj ponownie." else null
            }.onFailure {
                errorMessage = "Nie udało się pobrać danych. Spróbuj ponownie."
            }

            isLoading = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        if (items.isEmpty()) {
            load(reset = true)
        }
    }

    AppScreenScaffold(
        navController = navController,
        title = "Nowości",
        rootDestination = rootDestination,
        snackbarHostState = snackbarHostState
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && items.isEmpty()) {
                item {
                    StatePane(message = "Ładowanie nowości…", showLoading = true)
                }
            }
            if (errorMessage != null && items.isEmpty()) {
                item {
                    StatePane(
                        message = errorMessage.orEmpty(),
                        retryLabel = "Spróbuj ponownie",
                        onRetry = { load(reset = true) }
                    )
                }
            }
            items(items, key = { it.uniqueId }) { item ->
                val favoriteItem = when (item.kind) {
                    ContentKind.PODCAST -> FavoriteItem.PodcastFavorite(item.post)
                    ContentKind.ARTICLE -> FavoriteItem.ArticleFavorite(item.post, FavoriteArticleOrigin.POST)
                }
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = item.post.title.plainText,
                    date = item.post.formattedDate,
                    kind = item.kind,
                    contentKindLabelPosition = settings.contentKindLabelPosition,
                    leadingContent = {
                        androidx.compose.material3.Icon(
                            imageVector = if (item.kind == ContentKind.PODCAST) Icons.Outlined.LibraryMusic else Icons.Outlined.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    onOpen = {
                        navController.navigate(
                            when (item.kind) {
                                ContentKind.PODCAST -> AppRoutes.podcastDetail(item.post.id)
                                ContentKind.ARTICLE -> AppRoutes.articleDetail(item.post.id, FavoriteArticleOrigin.POST)
                            }
                        )
                    },
                    onListen = if (item.kind == ContentKind.PODCAST) {
                        {
                            navController.navigate(
                                AppRoutes.player(
                                    url = appContainer.repository.getListenableUrl(item.post.id),
                                    title = item.post.title.plainText,
                                    subtitle = item.post.formattedDate,
                                    live = false,
                                    postId = item.post.id
                                )
                            )
                        }
                    } else {
                        null
                    },
                    onCopyLink = {
                        copyToClipboard(context, item.post.link)
                        scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                    },
                    favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                    onToggleFavorite = {
                        scope.launch {
                            appContainer.preferencesRepository.toggleFavorite(favoriteItem)
                        }
                    }
                )
            }
            if (items.isNotEmpty() && canLoadMore()) {
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { load(reset = false) },
                        enabled = !isLoadingMore
                    ) {
                        Text(if (isLoadingMore) "Ładowanie…" else "Wczytaj starsze treści")
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastsHomeScreen(
    navController: NavHostController,
    rootDestination: RootDestination
) {
    val appContainer = LocalAppContainer.current
    CategoriesHomeScreen(
        navController = navController,
        rootDestination = rootDestination,
        title = "Podcasty",
        allLabel = "Wszystkie kategorie",
        loadPage = { page, perPage ->
            appContainer.repository.fetchPodcastCategoriesPage(page, perPage)
        },
        onAllClick = { navController.navigate(AppRoutes.podcastList("Wszystkie podcasty", -1)) },
        onCategoryClick = { categoryId, name ->
            navController.navigate(AppRoutes.podcastList(name, categoryId))
        }
    )
}

@Composable
fun ArticlesHomeScreen(
    navController: NavHostController,
    rootDestination: RootDestination
) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val categories = remember { mutableStateListOf<org.tyflocentrum.android.core.model.Category>() }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun load(reset: Boolean) {
        scope.launch {
            isLoading = true
            if (reset) {
                page = 1
                totalPages = null
                categories.clear()
            }
            runCatching {
                appContainer.repository.fetchArticleCategoriesPage(page, 100)
            }.onSuccess { response ->
                categories.addAll(response.items.filterNot { item -> categories.any { it.id == item.id } })
                totalPages = response.totalPages
                page += 1
                error = null
            }.onFailure {
                error = "Nie udało się pobrać kategorii artykułów."
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (categories.isEmpty()) load(reset = true)
    }

    AppScreenScaffold(
        navController = navController,
        title = "Artykuły",
        rootDestination = rootDestination
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ActionCard(
                    title = "Czasopismo TyfloŚwiat",
                    supportingText = "Roczniki, numery i spis treści",
                    onClick = { navController.navigate(AppRoutes.MAGAZINE) }
                )
            }
            item {
                ActionCard(
                    title = "Wszystkie kategorie",
                    supportingText = "Pełna lista artykułów Tyfloświat",
                    onClick = { navController.navigate(AppRoutes.articleList("Wszystkie artykuły", -1)) }
                )
            }
            if (isLoading && categories.isEmpty()) {
                item { StatePane(message = "Ładowanie kategorii…", showLoading = true) }
            }
            if (error != null && categories.isEmpty()) {
                item {
                    StatePane(
                        message = error.orEmpty(),
                        retryLabel = "Spróbuj ponownie",
                        onRetry = { load(reset = true) }
                    )
                }
            }
            items(categories, key = { it.id }) { category ->
                ActionCard(
                    title = category.name,
                    supportingText = "${category.count} wpisów",
                    onClick = { navController.navigate(AppRoutes.articleList(category.name, category.id)) }
                )
            }
            if (categories.isNotEmpty() && (totalPages == null || page <= (totalPages ?: 0))) {
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { load(reset = false) },
                        enabled = !isLoading
                    ) {
                        Text(if (isLoading) "Ładowanie…" else "Wczytaj kolejne kategorie")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoriesHomeScreen(
    navController: NavHostController,
    rootDestination: RootDestination,
    title: String,
    allLabel: String,
    loadPage: suspend (page: Int, perPage: Int) -> org.tyflocentrum.android.core.model.PagedResult<org.tyflocentrum.android.core.model.Category>,
    onAllClick: () -> Unit,
    onCategoryClick: (categoryId: Int, name: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val categories = remember { mutableStateListOf<org.tyflocentrum.android.core.model.Category>() }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun load(reset: Boolean) {
        scope.launch {
            isLoading = true
            if (reset) {
                page = 1
                totalPages = null
                categories.clear()
            }
            runCatching {
                loadPage(page, 100)
            }.onSuccess { response ->
                categories.addAll(response.items.filterNot { newItem -> categories.any { it.id == newItem.id } })
                totalPages = response.totalPages
                page += 1
                error = null
            }.onFailure {
                error = "Nie udało się pobrać kategorii."
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (categories.isEmpty()) load(reset = true)
    }

    AppScreenScaffold(
        navController = navController,
        title = title,
        rootDestination = rootDestination
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ActionCard(
                    title = allLabel,
                    supportingText = "Przeglądaj pełny katalog treści",
                    onClick = onAllClick
                )
            }
            if (isLoading && categories.isEmpty()) {
                item { StatePane(message = "Ładowanie kategorii…", showLoading = true) }
            }
            if (error != null && categories.isEmpty()) {
                item {
                    StatePane(
                        message = error.orEmpty(),
                        retryLabel = "Spróbuj ponownie",
                        onRetry = { load(reset = true) }
                    )
                }
            }
            items(categories, key = { it.id }) { category ->
                ActionCard(
                    title = category.name,
                    supportingText = "${category.count} wpisów",
                    onClick = { onCategoryClick(category.id, category.name) }
                )
            }
            if (categories.isNotEmpty() && (totalPages == null || page <= (totalPages ?: 0))) {
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { load(reset = false) },
                        enabled = !isLoading
                    ) {
                        Text(if (isLoading) "Ładowanie…" else "Wczytaj kolejne kategorie")
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastListScreen(
    navController: NavHostController,
    title: String,
    categoryId: Int?
) {
    val appContainer = LocalAppContainer.current
    val cachedState = remember(categoryId) { appContainer.repository.peekPodcastListScreenCache(categoryId) }
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val items = remember(categoryId) {
        mutableStateListOf<WpPostSummary>().apply {
            addAll(cachedState?.items.orEmpty())
        }
    }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var page by remember(categoryId) { mutableIntStateOf(cachedState?.nextPage ?: 1) }
    var totalPages by remember(categoryId) { mutableStateOf(cachedState?.totalPages) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(categoryId) { mutableStateOf(cachedState == null) }

    fun syncCache() {
        appContainer.repository.storePodcastListScreenCache(
            categoryId = categoryId,
            cache = PagedScreenCache(
                items = items.toList(),
                nextPage = page,
                totalPages = totalPages
            )
        )
    }

    fun load(reset: Boolean) {
        scope.launch {
            isLoading = true
            if (reset) {
                page = 1
                totalPages = null
                items.clear()
            }
            runCatching {
                appContainer.repository.fetchPodcastSummariesPage(page, 20, categoryId?.takeIf { it >= 0 })
            }.onSuccess { response ->
                items.addAll(response.items.filterNot { newItem -> items.any { it.id == newItem.id } })
                totalPages = response.totalPages
                page += 1
                syncCache()
                error = null
            }.onFailure {
                error = "Nie udało się pobrać podcastów."
            }
            isLoading = false
        }
    }

    LaunchedEffect(categoryId) {
        if (items.isEmpty()) load(reset = true)
    }

    AppScreenScaffold(
        navController = navController,
        title = title
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && items.isEmpty()) {
                item { StatePane(message = "Ładowanie podcastów…", showLoading = true) }
            }
            if (error != null && items.isEmpty()) {
                item {
                    StatePane(message = error.orEmpty(), retryLabel = "Spróbuj ponownie", onRetry = { load(reset = true) })
                }
            }
            items(items, key = { it.id }) { item ->
                val favoriteItem = FavoriteItem.PodcastFavorite(item)
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = item.title.plainText,
                    date = item.formattedDate,
                    kind = ContentKind.PODCAST,
                    contentKindLabelPosition = settings.contentKindLabelPosition,
                    leadingContent = {
                        androidx.compose.material3.Icon(Icons.Outlined.LibraryMusic, contentDescription = null)
                    },
                    onOpen = { navController.navigate(AppRoutes.podcastDetail(item.id)) },
                    onListen = {
                        navController.navigate(
                            AppRoutes.player(
                                url = appContainer.repository.getListenableUrl(item.id),
                                title = item.title.plainText,
                                subtitle = item.formattedDate,
                                live = false,
                                postId = item.id
                            )
                        )
                    },
                    onCopyLink = {
                        copyToClipboard(context, item.link)
                        scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                    },
                    favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                    onToggleFavorite = {
                        scope.launch { appContainer.preferencesRepository.toggleFavorite(favoriteItem) }
                    }
                )
            }
            if (items.isNotEmpty() && (totalPages == null || page <= (totalPages ?: 0))) {
                item {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { load(reset = false) }, enabled = !isLoading) {
                        Text(if (isLoading) "Ładowanie…" else "Wczytaj starsze treści")
                    }
                }
            }
        }
    }
}

@Composable
fun ArticleListScreen(
    navController: NavHostController,
    title: String,
    categoryId: Int?
) {
    val appContainer = LocalAppContainer.current
    val cachedState = remember(categoryId) { appContainer.repository.peekArticleListScreenCache(categoryId) }
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val items = remember(categoryId) {
        mutableStateListOf<WpPostSummary>().apply {
            addAll(cachedState?.items.orEmpty())
        }
    }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var page by remember(categoryId) { mutableIntStateOf(cachedState?.nextPage ?: 1) }
    var totalPages by remember(categoryId) { mutableStateOf(cachedState?.totalPages) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(categoryId) { mutableStateOf(cachedState == null) }

    fun syncCache() {
        appContainer.repository.storeArticleListScreenCache(
            categoryId = categoryId,
            cache = PagedScreenCache(
                items = items.toList(),
                nextPage = page,
                totalPages = totalPages
            )
        )
    }

    fun load(reset: Boolean) {
        scope.launch {
            isLoading = true
            if (reset) {
                page = 1
                totalPages = null
                items.clear()
            }
            runCatching {
                appContainer.repository.fetchArticleSummariesPage(page, 20, categoryId?.takeIf { it >= 0 })
            }.onSuccess { response ->
                items.addAll(response.items.filterNot { newItem -> items.any { it.id == newItem.id } })
                totalPages = response.totalPages
                page += 1
                syncCache()
                error = null
            }.onFailure {
                error = "Nie udało się pobrać artykułów."
            }
            isLoading = false
        }
    }

    LaunchedEffect(categoryId) {
        if (items.isEmpty()) load(reset = true)
    }

    AppScreenScaffold(
        navController = navController,
        title = title,
        snackbarHostState = snackbarHostState
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && items.isEmpty()) {
                item { StatePane(message = "Ładowanie artykułów…", showLoading = true) }
            }
            if (error != null && items.isEmpty()) {
                item {
                    StatePane(message = error.orEmpty(), retryLabel = "Spróbuj ponownie", onRetry = { load(reset = true) })
                }
            }
            items(items, key = { it.id }) { item ->
                val favoriteItem = FavoriteItem.ArticleFavorite(item, FavoriteArticleOrigin.POST)
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = item.title.plainText,
                    date = item.formattedDate,
                    kind = ContentKind.ARTICLE,
                    contentKindLabelPosition = settings.contentKindLabelPosition,
                    leadingContent = {
                        androidx.compose.material3.Icon(Icons.Outlined.Article, contentDescription = null)
                    },
                    onOpen = { navController.navigate(AppRoutes.articleDetail(item.id, FavoriteArticleOrigin.POST)) },
                    onCopyLink = {
                        copyToClipboard(context, item.link)
                        scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                    },
                    favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                    onToggleFavorite = {
                        scope.launch { appContainer.preferencesRepository.toggleFavorite(favoriteItem) }
                    }
                )
            }
            if (items.isNotEmpty() && (totalPages == null || page <= (totalPages ?: 0))) {
                item {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { load(reset = false) }, enabled = !isLoading) {
                        Text(if (isLoading) "Ładowanie…" else "Wczytaj starsze treści")
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    navController: NavHostController,
    rootDestination: RootDestination
) {
    val appContainer = LocalAppContainer.current
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var selectedScope by remember { mutableIntStateOf(0) }
    val results = remember { mutableStateListOf<SearchItem>() }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var announcement by remember { mutableStateOf<String?>(null) }

    fun search() {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            isLoading = true
            error = null
            runCatching {
                when (selectedScope) {
                    0 -> {
                        val podcasts = async {
                            appContainer.repository.fetchPodcastSearchSummaries(trimmed)
                        }
                        val articles = async {
                            appContainer.repository.fetchArticleSearchSummaries(trimmed)
                        }
                        podcasts.await().map { SearchItem(ContentKind.PODCAST, it) } +
                            articles.await().map { SearchItem(ContentKind.ARTICLE, it) }
                    }
                    1 -> appContainer.repository.fetchPodcastSearchSummaries(trimmed)
                        .map { SearchItem(ContentKind.PODCAST, it) }
                    else -> appContainer.repository.fetchArticleSearchSummaries(trimmed)
                        .map { SearchItem(ContentKind.ARTICLE, it) }
                }
            }.onSuccess { fetched ->
                val sorted = SearchRanking.sort(fetched, trimmed)
                results.clear()
                results.addAll(sorted)
                announcement = if (sorted.isEmpty()) {
                    "Brak wyników wyszukiwania."
                } else {
                    "Znaleziono ${sorted.size} wyników."
                }
            }.onFailure {
                error = "Nie udało się wyszukać treści."
                announcement = error
            }
            isLoading = false
        }
    }

    Announcement(announcement)

    AppScreenScaffold(
        navController = navController,
        title = "Szukaj",
        rootDestination = rootDestination,
        snackbarHostState = snackbarHostState
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChipRow(
                            options = listOf("Wszystko", "Podcasty", "Artykuły"),
                            selectedIndex = selectedScope,
                            onSelected = { selectedScope = it }
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Podaj frazę do wyszukania") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { search() },
                            enabled = query.trim().isNotBlank() && !isLoading
                        ) {
                            Text(if (isLoading) "Wyszukiwanie…" else "Szukaj")
                        }
                    }
                }
            }

            if (error != null) {
                item {
                    StatePane(message = error.orEmpty(), retryLabel = "Ponów wyszukiwanie", onRetry = { search() })
                }
            } else if (isLoading && results.isEmpty()) {
                item {
                    StatePane(message = "Wyszukiwanie…", showLoading = true)
                }
            } else if (!isLoading && results.isEmpty() && query.isNotBlank()) {
                item {
                    StatePane(message = "Brak wyników wyszukiwania dla podanej frazy.")
                }
            }

            items(results, key = { "${it.kind.name}.${it.post.id}" }) { item ->
                val favoriteItem = when (item.kind) {
                    ContentKind.PODCAST -> FavoriteItem.PodcastFavorite(item.post)
                    ContentKind.ARTICLE -> FavoriteItem.ArticleFavorite(item.post, FavoriteArticleOrigin.POST)
                }
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = item.post.title.plainText,
                    date = item.post.formattedDate,
                    kind = item.kind,
                    contentKindLabelPosition = settings.contentKindLabelPosition,
                    leadingContent = {
                        androidx.compose.material3.Icon(
                            imageVector = if (item.kind == ContentKind.PODCAST) Icons.Outlined.LibraryMusic else Icons.Outlined.Article,
                            contentDescription = null
                        )
                    },
                    onOpen = {
                        navController.navigate(
                            when (item.kind) {
                                ContentKind.PODCAST -> AppRoutes.podcastDetail(item.post.id)
                                ContentKind.ARTICLE -> AppRoutes.articleDetail(item.post.id, FavoriteArticleOrigin.POST)
                            }
                        )
                    },
                    onListen = if (item.kind == ContentKind.PODCAST) {
                        {
                            navController.navigate(
                                AppRoutes.player(
                                    url = appContainer.repository.getListenableUrl(item.post.id),
                                    title = item.post.title.plainText,
                                    subtitle = item.post.formattedDate,
                                    live = false,
                                    postId = item.post.id
                                )
                            )
                        }
                    } else null,
                    onCopyLink = {
                        copyToClipboard(context, item.post.link)
                        scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                    },
                    favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                    onToggleFavorite = {
                        scope.launch { appContainer.preferencesRepository.toggleFavorite(favoriteItem) }
                    }
                )
            }
        }
    }
}

@Composable
fun MagazineScreen(
    navController: NavHostController
) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val cachedIssues = remember { appContainer.repository.peekMagazineScreenCache().orEmpty() }
    val issues = remember {
        mutableStateListOf<WpPostSummary>().apply {
            addAll(cachedIssues)
        }
    }
    var isLoading by remember { mutableStateOf(cachedIssues.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            runCatching {
                appContainer.repository.fetchTyfloswiatPageSummaries(MAGAZINE_ROOT_PAGE_ID)
                    .ifEmpty {
                        val roots = appContainer.repository.fetchTyfloswiatPages("czasopismo", 1)
                        val rootId = roots.firstOrNull()?.id ?: MAGAZINE_ROOT_PAGE_ID
                        appContainer.repository.fetchTyfloswiatPageSummaries(rootId)
                    }
            }.onSuccess { fetched ->
                issues.clear()
                issues.addAll(fetched)
                appContainer.repository.storeMagazineScreenCache(fetched)
                error = null
            }.onFailure {
                error = "Nie udało się pobrać numerów czasopisma."
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (issues.isEmpty()) load()
    }

    val grouped = remember(issues.toList()) {
        issues.groupBy {
            MagazineParser.parseIssueNumberAndYear(it.title.plainText).second ?: 0
        }.toSortedMap(compareByDescending { it })
    }

    AppScreenScaffold(
        navController = navController,
        title = "Czasopismo TyfloŚwiat"
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPaddingWithInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && issues.isEmpty()) {
                item { StatePane(message = "Ładowanie numerów czasopisma…", showLoading = true) }
            }
            if (error != null && issues.isEmpty()) {
                item { StatePane(message = error.orEmpty(), retryLabel = "Spróbuj ponownie", onRetry = { load() }) }
            }
            grouped.forEach { (year, yearIssues) ->
                item {
                    Text(
                        text = if (year == 0) "Bez roku" else year.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(
                    items = yearIssues.sortedByDescending { MagazineParser.parseIssueNumberAndYear(it.title.plainText).first ?: -1 },
                    key = { it.id }
                ) { issue ->
                    ContentListItem(
                        title = issue.title.plainText,
                        date = issue.formattedDate,
                        kind = ContentKind.ARTICLE,
                        leadingContent = {
                            androidx.compose.material3.Icon(Icons.Outlined.MenuBook, contentDescription = null)
                        },
                        onOpen = { navController.navigate(AppRoutes.magazineIssue(issue.id)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    supportingText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun contentPaddingWithInsets(paddingValues: PaddingValues): PaddingValues {
    return PaddingValues(
        start = 16.dp,
        top = 16.dp + paddingValues.calculateTopPadding(),
        end = 16.dp,
        bottom = 16.dp + paddingValues.calculateBottomPadding()
    )
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("tyflocentrum", value))
}
