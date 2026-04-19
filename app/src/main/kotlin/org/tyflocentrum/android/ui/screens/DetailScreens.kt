package org.tyflocentrum.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.tyflocentrum.android.core.model.AppSettings
import org.tyflocentrum.android.core.model.ContactDraft
import org.tyflocentrum.android.core.model.FavoriteArticleOrigin
import org.tyflocentrum.android.core.model.FavoriteItem
import org.tyflocentrum.android.core.model.FavoritesFilter
import org.tyflocentrum.android.core.model.PlaybackRateRememberMode
import org.tyflocentrum.android.core.model.PushPreferences
import org.tyflocentrum.android.core.model.WpPostDetail
import org.tyflocentrum.android.ui.AppRoutes
import org.tyflocentrum.android.ui.LocalAppContainer
import org.tyflocentrum.android.ui.common.AccessibleHtmlText
import org.tyflocentrum.android.ui.common.AppScreenScaffold
import org.tyflocentrum.android.ui.common.ContentListItem
import org.tyflocentrum.android.ui.common.FilterChipRow
import org.tyflocentrum.android.ui.common.FullScreenScrollable
import org.tyflocentrum.android.ui.common.OpenExternalButton
import org.tyflocentrum.android.ui.common.PlainTextScreen
import org.tyflocentrum.android.ui.common.StatePane
import org.tyflocentrum.android.ui.common.ToggleRow

@Composable
fun PodcastDetailScreen(
    navController: NavHostController,
    podcastId: Int
) {
    val appContainer = LocalAppContainer.current
    val cachedDetail = remember(podcastId) { appContainer.repository.peekPodcastDetail(podcastId) }
    val cachedCommentsCount = remember(podcastId) { appContainer.repository.peekCommentsCount(podcastId) }
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var detail by remember(podcastId) { mutableStateOf(cachedDetail) }
    var commentsCount by remember(podcastId) { mutableStateOf(cachedCommentsCount) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(podcastId) { mutableStateOf(cachedDetail == null) }

    LaunchedEffect(podcastId) {
        if (detail == null) {
            isLoading = true
        }
        runCatching {
            val loaded = appContainer.repository.fetchPodcastDetail(podcastId)
            val count = runCatching { appContainer.repository.fetchCommentsCount(podcastId) }.getOrNull()
            loaded to count
        }.onSuccess { (loaded, count) ->
            detail = loaded
            commentsCount = count
            error = null
        }.onFailure {
            error = "Nie udało się pobrać szczegółów podcastu."
        }
        isLoading = false
    }

    val podcast = detail
    val favoriteItem = podcast?.let {
        FavoriteItem.PodcastFavorite(
            summary = org.tyflocentrum.android.core.model.WpPostSummary(
                id = it.id,
                date = it.date,
                title = it.title,
                excerpt = it.excerpt,
                link = it.guid.plainText
            )
        )
    }
    val isFavorite = favoriteItem != null && favorites.any { it.id == favoriteItem.id }

    AppScreenScaffold(
        navController = navController,
        title = podcast?.title?.plainText ?: "Podcast",
        snackbarHostState = snackbarHostState,
        actions = {
            if (favoriteItem != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            appContainer.preferencesRepository.toggleFavorite(favoriteItem)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) { padding ->
        if (isLoading && podcast == null) {
            DetailStatePane(padding, "Ładowanie szczegółów podcastu…", true)
        } else if (error != null && podcast == null) {
            DetailStatePane(padding, error.orEmpty(), false)
        } else if (podcast != null) {
            FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
                Text(text = podcast.title.plainText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(text = podcast.formattedDate, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navController.navigate(
                            AppRoutes.player(
                                url = appContainer.repository.getListenableUrl(podcast.id),
                                title = podcast.title.plainText,
                                subtitle = podcast.formattedDate,
                                live = false,
                                postId = podcast.id
                            )
                        )
                    }
                ) {
                    Text("Słuchaj audycji")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = "Udostępnij",
                        icon = Icons.Filled.Share,
                        onClick = { shareText(context, podcast.guid.plainText, podcast.title.plainText) }
                    )
                    ActionButton(
                        label = "Skopiuj link",
                        icon = Icons.Filled.ContentCopy,
                        onClick = {
                            copyToClipboard(context, podcast.guid.plainText)
                            scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                        }
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navController.navigate(AppRoutes.comments(podcast.id)) }
                ) {
                    Text("Komentarze${commentsCount?.let { ": $it" } ?: ""}")
                }

                PlainTextScreen(text = podcast.content.plainText)
            }
        }
    }
}

@Composable
fun ArticleDetailScreen(
    navController: NavHostController,
    articleId: Int,
    origin: FavoriteArticleOrigin
) {
    val appContainer = LocalAppContainer.current
    val cachedDetail = remember(articleId, origin) {
        when (origin) {
            FavoriteArticleOrigin.POST -> appContainer.repository.peekArticleDetail(articleId)
            FavoriteArticleOrigin.PAGE -> appContainer.repository.peekTyfloswiatPage(articleId)
        }
    }
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var detail by remember(articleId, origin) { mutableStateOf(cachedDetail) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(articleId, origin) { mutableStateOf(cachedDetail == null) }

    LaunchedEffect(articleId, origin) {
        if (detail == null) {
            isLoading = true
        }
        runCatching {
            when (origin) {
                FavoriteArticleOrigin.POST -> appContainer.repository.fetchArticleDetail(articleId)
                FavoriteArticleOrigin.PAGE -> appContainer.repository.fetchTyfloswiatPage(articleId)
            }
        }.onSuccess {
            detail = it
            error = null
        }.onFailure {
            error = "Nie udało się pobrać szczegółów artykułu."
        }
        isLoading = false
    }

    val article = detail
    val favoriteItem = article?.let {
        FavoriteItem.ArticleFavorite(
            summary = org.tyflocentrum.android.core.model.WpPostSummary(
                id = it.id,
                date = it.date,
                title = it.title,
                excerpt = it.excerpt,
                link = it.guid.plainText
            ),
            origin = origin
        )
    }
    val isFavorite = favoriteItem != null && favorites.any { it.id == favoriteItem.id }

    AppScreenScaffold(
        navController = navController,
        title = article?.title?.plainText ?: "Artykuł",
        snackbarHostState = snackbarHostState,
        actions = {
            if (favoriteItem != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            appContainer.preferencesRepository.toggleFavorite(favoriteItem)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) { padding ->
        if (isLoading && article == null) {
            DetailStatePane(padding, "Ładowanie artykułu…", true)
        } else if (error != null && article == null) {
            DetailStatePane(padding, error.orEmpty(), false)
        } else if (article != null) {
            FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
                Text(text = article.title.plainText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(text = article.formattedDate, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = "Udostępnij",
                        icon = Icons.Filled.Share,
                        onClick = { shareText(context, article.guid.plainText, article.title.plainText) }
                    )
                    ActionButton(
                        label = "Skopiuj link",
                        icon = Icons.Filled.ContentCopy,
                        onClick = {
                            copyToClipboard(context, article.guid.plainText)
                            scope.launch { snackbarHostState.showSnackbar("Skopiowano link.") }
                        }
                    )
                }

                AccessibleHtmlText(html = article.content.rendered)
            }
        }
    }
}

@Composable
fun PodcastCommentsScreen(
    navController: NavHostController,
    postId: Int
) {
    val appContainer = LocalAppContainer.current
    val cachedComments = remember(postId) { appContainer.repository.peekComments(postId).orEmpty() }
    var comments by remember(postId) { mutableStateOf(cachedComments) }
    var isLoading by remember(postId) { mutableStateOf(cachedComments.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(postId) {
        if (comments.isEmpty()) {
            isLoading = true
        }
        runCatching {
            appContainer.repository.fetchComments(postId)
        }.onSuccess {
            comments = it
            error = null
        }.onFailure {
            error = "Nie udało się pobrać komentarzy."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = "Komentarze"
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && comments.isEmpty()) {
                item { StatePane(message = "Ładowanie komentarzy…", showLoading = true) }
            }
            if (error != null && comments.isEmpty()) {
                item { StatePane(message = error.orEmpty()) }
            }
            items(comments, key = { it.id }) { comment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(text = comment.authorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        AccessibleHtmlText(html = comment.content.rendered)
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    navController: NavHostController
) {
    val appContainer = LocalAppContainer.current
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var filterIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val filtered = favorites.filter {
        FavoritesFilter.entries[filterIndex].kind?.let { kind -> it.kind == kind } ?: true
    }

    AppScreenScaffold(
        navController = navController,
        title = "Ulubione"
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChipRow(
                    options = FavoritesFilter.entries.map { it.title },
                    selectedIndex = filterIndex,
                    onSelected = { filterIndex = it }
                )
            }

            if (filtered.isEmpty()) {
                item { StatePane(message = "Brak ulubionych.") }
            }

            items(filtered, key = { it.id }) { item ->
                when (item) {
                    is FavoriteItem.PodcastFavorite -> {
                        ContentListItem(
                            title = item.summary.title.plainText,
                            date = item.summary.formattedDate,
                            kind = org.tyflocentrum.android.core.model.ContentKind.PODCAST,
                            contentKindLabelPosition = settings.contentKindLabelPosition,
                            onOpen = { navController.navigate(AppRoutes.podcastDetail(item.summary.id)) },
                            onListen = {
                                navController.navigate(
                                    AppRoutes.player(
                                        url = appContainer.repository.getListenableUrl(item.summary.id),
                                        title = item.summary.title.plainText,
                                        subtitle = item.summary.formattedDate,
                                        live = false,
                                        postId = item.summary.id
                                    )
                                )
                            },
                            favoriteLabel = "Usuń z ulubionych",
                            onToggleFavorite = { scope.launch { appContainer.preferencesRepository.removeFavorite(item) } }
                        )
                    }
                    is FavoriteItem.ArticleFavorite -> {
                        ContentListItem(
                            title = item.summary.title.plainText,
                            date = item.summary.formattedDate,
                            kind = org.tyflocentrum.android.core.model.ContentKind.ARTICLE,
                            contentKindLabelPosition = settings.contentKindLabelPosition,
                            onOpen = { navController.navigate(AppRoutes.articleDetail(item.summary.id, item.origin)) },
                            favoriteLabel = "Usuń z ulubionych",
                            onToggleFavorite = { scope.launch { appContainer.preferencesRepository.removeFavorite(item) } }
                        )
                    }
                    is FavoriteItem.TopicFavorite -> {
                        ContentListItem(
                            title = item.topicTitle,
                            date = item.podcastTitle,
                            onOpen = {
                                navController.navigate(
                                    AppRoutes.player(
                                        url = appContainer.repository.getListenableUrl(item.podcastId),
                                        title = item.podcastTitle,
                                        subtitle = item.podcastSubtitle,
                                        live = false,
                                        postId = item.podcastId,
                                        seekMs = (item.seconds * 1000).toLong()
                                    )
                                )
                            },
                            favoriteLabel = "Usuń z ulubionych",
                            onToggleFavorite = { scope.launch { appContainer.preferencesRepository.removeFavorite(item) } }
                        )
                    }
                    is FavoriteItem.LinkFavorite -> {
                        ContentListItem(
                            title = item.linkTitle,
                            date = item.podcastTitle,
                            leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                            onOpen = {
                                openUri(context, item.urlString)
                            },
                            onCopyLink = {
                                copyToClipboard(context, item.urlString)
                            },
                            favoriteLabel = "Usuń z ulubionych",
                            onToggleFavorite = { scope.launch { appContainer.preferencesRepository.removeFavorite(item) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    navController: NavHostController
) {
    val appContainer = LocalAppContainer.current
    val settings by appContainer.preferencesRepository.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    AppScreenScaffold(
        navController = navController,
        title = "Ustawienia"
    ) { padding ->
        FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
            Text(text = "Wskazuj typ treści", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FilterChipRow(
                options = listOf("Przed", "Po"),
                selectedIndex = if (settings.contentKindLabelPosition == org.tyflocentrum.android.core.model.ContentKindLabelPosition.BEFORE) 0 else 1,
                onSelected = { selected ->
                    scope.launch {
                        appContainer.preferencesRepository.setContentKindLabelPosition(
                            if (selected == 0) org.tyflocentrum.android.core.model.ContentKindLabelPosition.BEFORE
                            else org.tyflocentrum.android.core.model.ContentKindLabelPosition.AFTER
                        )
                    }
                }
            )

            Text(text = "Zapamiętywanie prędkości", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FilterChipRow(
                options = listOf("Globalnie", "Dla każdego odcinka"),
                selectedIndex = if (settings.playbackRateRememberMode == PlaybackRateRememberMode.GLOBAL) 0 else 1,
                onSelected = { selected ->
                    scope.launch {
                        appContainer.preferencesRepository.setPlaybackRateRememberMode(
                            if (selected == 0) PlaybackRateRememberMode.GLOBAL else PlaybackRateRememberMode.PER_EPISODE
                        )
                    }
                }
            )

            Text(text = "Powiadomienia push", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ToggleRow(
                title = "Wszystkie",
                checked = settings.pushPreferences.allEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        appContainer.preferencesRepository.updatePushPreferences {
                            PushPreferences(enabled, enabled, enabled, enabled)
                        }
                    }
                },
                supportingText = "Zapisuje preferencje kategorii, gotowe pod przyszłą konfigurację FCM."
            )
            ToggleRow(
                title = "Nowe odcinki Tyflopodcast",
                checked = settings.pushPreferences.podcast,
                onCheckedChange = { checked ->
                    scope.launch {
                        appContainer.preferencesRepository.updatePushPreferences { it.copy(podcast = checked) }
                    }
                }
            )
            ToggleRow(
                title = "Nowe artykuły Tyfloświat",
                checked = settings.pushPreferences.article,
                onCheckedChange = { checked ->
                    scope.launch {
                        appContainer.preferencesRepository.updatePushPreferences { it.copy(article = checked) }
                    }
                }
            )
            ToggleRow(
                title = "Start audycji interaktywnej Tyfloradio",
                checked = settings.pushPreferences.live,
                onCheckedChange = { checked ->
                    scope.launch {
                        appContainer.preferencesRepository.updatePushPreferences { it.copy(live = checked) }
                    }
                }
            )
            ToggleRow(
                title = "Zmiana ramówki Tyfloradio",
                checked = settings.pushPreferences.schedule,
                onCheckedChange = { checked ->
                    scope.launch {
                        appContainer.preferencesRepository.updatePushPreferences { it.copy(schedule = checked) }
                    }
                }
            )
            Text(
                text = "Ten build zapisuje preferencje powiadomień, ale nie zawiera jeszcze konfiguracji FCM i kluczy projektu Firebase.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DetailStatePane(
    paddingValues: PaddingValues,
    message: String,
    loading: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(detailPadding(paddingValues))
    ) {
        StatePane(message = message, showLoading = loading)
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Button(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun detailPadding(paddingValues: PaddingValues): PaddingValues {
    return PaddingValues(
        start = 16.dp,
        top = 16.dp + paddingValues.calculateTopPadding(),
        end = 16.dp,
        bottom = 16.dp + paddingValues.calculateBottomPadding()
    )
}

private fun shareText(context: Context, text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(intent, subject))
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("tyflocentrum", value))
}

private fun openUri(context: Context, value: String) {
    val intent = Intent(Intent.ACTION_VIEW, value.toUri())
    startActivity(context, intent, null)
}
