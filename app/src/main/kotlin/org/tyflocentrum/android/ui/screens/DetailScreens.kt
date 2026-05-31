package net.tyflopodcast.tyflocentrum.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.tyflopodcast.tyflocentrum.BuildConfig
import net.tyflopodcast.tyflocentrum.core.model.AppSettings
import net.tyflopodcast.tyflocentrum.core.model.Comment
import net.tyflopodcast.tyflocentrum.core.model.CommentDraft
import net.tyflopodcast.tyflocentrum.core.model.ContactDraft
import net.tyflopodcast.tyflocentrum.core.model.FavoriteArticleOrigin
import net.tyflopodcast.tyflocentrum.core.model.FavoriteItem
import net.tyflopodcast.tyflocentrum.core.model.FavoritesFilter
import net.tyflopodcast.tyflocentrum.core.model.PlaybackRateRememberMode
import net.tyflopodcast.tyflocentrum.core.model.PushPreferences
import net.tyflopodcast.tyflocentrum.core.model.ThreadedComment
import net.tyflopodcast.tyflocentrum.core.model.toThreadedComments
import net.tyflopodcast.tyflocentrum.core.model.WpPostDetail
import net.tyflopodcast.tyflocentrum.ui.AppRoutes
import net.tyflopodcast.tyflocentrum.ui.LocalAppContainer
import net.tyflopodcast.tyflocentrum.ui.common.AccessibleHtmlText
import net.tyflopodcast.tyflocentrum.ui.common.AppScreenScaffold
import net.tyflopodcast.tyflocentrum.ui.common.ContentListItem
import net.tyflopodcast.tyflocentrum.ui.common.FilterChipRow
import net.tyflopodcast.tyflocentrum.ui.common.FullScreenScrollable
import net.tyflopodcast.tyflocentrum.ui.common.OpenExternalButton
import net.tyflopodcast.tyflocentrum.ui.common.StatePane
import net.tyflopodcast.tyflocentrum.ui.common.ToggleRow

private const val APP_SUPPORT_URL = "https://michaldziwisz.github.io/tyflocentrum_android/"
private const val APP_PRIVACY_POLICY_URL = "https://michaldziwisz.github.io/tyflocentrum_android/privacy/"
private const val APP_SUPPORT_EMAIL = "mailto:michal@dziwisz.net"

@Composable
fun PodcastDetailScreen(
    navController: NavHostController,
    podcastId: Int
) {
    val appContainer = LocalAppContainer.current
    val cachedDetail = remember(podcastId) { appContainer.repository.peekPodcastDetail(podcastId) }
    val cachedCommentsCount = remember(podcastId) { appContainer.repository.peekCommentsCount(podcastId) }
    val cachedTextVersionReference = remember(podcastId) { appContainer.repository.peekPodcastTextVersionReference(podcastId) }
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var detail by remember(podcastId) { mutableStateOf(cachedDetail) }
    var commentsCount by remember(podcastId) { mutableStateOf(cachedCommentsCount) }
    var textVersionReference by remember(podcastId) { mutableStateOf(cachedTextVersionReference) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(podcastId) { mutableStateOf(cachedDetail == null) }

    LaunchedEffect(podcastId) {
        if (detail == null) {
            isLoading = true
        }
        runCatching {
            val loaded = appContainer.repository.fetchPodcastDetail(podcastId)
            val count = runCatching { appContainer.repository.fetchCommentsCount(podcastId) }.getOrNull()
            val textVersion = runCatching { appContainer.repository.fetchPodcastTextVersionReference(podcastId) }.getOrNull()
            Triple(loaded, count, textVersion)
        }.onSuccess { (loaded, count, textVersion) ->
            detail = loaded
            commentsCount = count
            textVersionReference = textVersion
            error = null
        }.onFailure {
            error = "Nie udało się pobrać szczegółów podcastu."
        }
        isLoading = false
    }

    val podcast = detail
    val favoriteItem = podcast?.let {
        FavoriteItem.PodcastFavorite(
            summary = net.tyflopodcast.tyflocentrum.core.model.WpPostSummary(
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

                if (textVersionReference != null) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navController.navigate(AppRoutes.podcastTextVersion(podcast.id)) }
                    ) {
                        Text("Wersja tekstowa audycji")
                    }
                }

                AccessibleHtmlText(html = podcast.content.rendered)
            }
        }
    }
}

@Composable
fun PodcastTextVersionScreen(
    navController: NavHostController,
    postId: Int
) {
    val appContainer = LocalAppContainer.current
    val cachedTextVersion = remember(postId) { appContainer.repository.peekPodcastTextVersion(postId) }
    var textVersion by remember(postId) { mutableStateOf(cachedTextVersion) }
    var error by remember(postId) { mutableStateOf<String?>(null) }
    var isLoading by remember(postId) { mutableStateOf(cachedTextVersion == null) }

    LaunchedEffect(postId) {
        if (textVersion == null) {
            isLoading = true
        }
        runCatching {
            appContainer.repository.fetchPodcastTextVersion(postId)
        }.onSuccess { loaded ->
            textVersion = loaded
            error = if (loaded == null) "Ten odcinek nie ma tekstowej wersji audycji." else null
        }.onFailure {
            error = "Nie udało się pobrać tekstowej wersji audycji."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = textVersion?.title?.plainText ?: "Wersja tekstowa"
    ) { padding ->
        if (isLoading && textVersion == null) {
            DetailStatePane(padding, "Ładowanie tekstowej wersji audycji…", true)
        } else if (error != null && textVersion == null) {
            DetailStatePane(padding, error.orEmpty(), false)
        } else if (textVersion != null) {
            FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
                Text(
                    text = textVersion!!.formattedDate,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AccessibleHtmlText(html = textVersion!!.content.rendered)
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
            summary = net.tyflopodcast.tyflocentrum.core.model.WpPostSummary(
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
    val commentDraft by appContainer.preferencesRepository.commentDraftFlow.collectAsStateWithLifecycle(CommentDraft())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val authorNameFocusRequester = remember { FocusRequester() }
    val authorEmailFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    var comments by remember(postId) { mutableStateOf(cachedComments) }
    var isLoading by remember(postId) { mutableStateOf(cachedComments.isEmpty()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var authorName by rememberSaveable(postId) { mutableStateOf("") }
    var authorEmail by rememberSaveable(postId) { mutableStateOf("") }
    var content by rememberSaveable(postId) { mutableStateOf("") }
    var didEditAuthorName by rememberSaveable(postId) { mutableStateOf(false) }
    var didEditAuthorEmail by rememberSaveable(postId) { mutableStateOf(false) }
    var isCommentFormVisible by rememberSaveable(postId) { mutableStateOf(false) }
    var authorNameError by remember { mutableStateOf<String?>(null) }
    var authorEmailError by remember { mutableStateOf<String?>(null) }
    var contentError by remember { mutableStateOf<String?>(null) }
    var formMessage by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<Comment?>(null) }
    val threadedComments = remember(comments) { comments.toThreadedComments() }

    LaunchedEffect(commentDraft.authorName) {
        if (!didEditAuthorName) {
            authorName = commentDraft.authorName
        }
    }

    LaunchedEffect(commentDraft.authorEmail) {
        if (!didEditAuthorEmail) {
            authorEmail = commentDraft.authorEmail
        }
    }

    LaunchedEffect(authorNameError, authorEmailError, contentError) {
        when {
            authorNameError != null -> authorNameFocusRequester.requestFocus()
            authorEmailError != null -> authorEmailFocusRequester.requestFocus()
            contentError != null -> contentFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(postId) {
        if (comments.isEmpty()) {
            isLoading = true
        }
        runCatching {
            appContainer.repository.fetchComments(postId)
        }.onSuccess {
            comments = it
            listError = null
        }.onFailure {
            listError = "Nie udało się pobrać komentarzy."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = "Komentarze",
        snackbarHostState = snackbarHostState
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                if (isCommentFormVisible) {
                    CommentFormCard(
                        authorName = authorName,
                        onAuthorNameChange = {
                            didEditAuthorName = true
                            authorName = it
                            authorNameError = null
                            formMessage = null
                        },
                        authorEmail = authorEmail,
                        onAuthorEmailChange = {
                            didEditAuthorEmail = true
                            authorEmail = it
                            authorEmailError = null
                            formMessage = null
                        },
                        content = content,
                        onContentChange = {
                            content = it
                            contentError = null
                            formMessage = null
                        },
                        replyTarget = replyTarget,
                        onCancelReply = {
                            replyTarget = null
                            formMessage = null
                        },
                        onCancelForm = {
                            replyTarget = null
                            formMessage = null
                            authorNameError = null
                            authorEmailError = null
                            contentError = null
                            isCommentFormVisible = false
                        },
                        authorNameError = authorNameError,
                        authorEmailError = authorEmailError,
                        contentError = contentError,
                        formMessage = formMessage,
                        isSending = isSending,
                        authorNameFocusRequester = authorNameFocusRequester,
                        authorEmailFocusRequester = authorEmailFocusRequester,
                        contentFocusRequester = contentFocusRequester,
                        onSubmit = {
                            val trimmedAuthorName = authorName.trim()
                            val trimmedAuthorEmail = authorEmail.trim()
                            val trimmedContent = content.trim()
                            authorNameError = if (trimmedAuthorName.isBlank()) "Wpisz imię lub nick." else null
                            authorEmailError = when {
                                trimmedAuthorEmail.isBlank() -> "Wpisz adres e-mail."
                                !trimmedAuthorEmail.isLikelyEmailAddress() -> "Wpisz poprawny adres e-mail."
                                else -> null
                            }
                            contentError = if (trimmedContent.isBlank()) "Wpisz treść komentarza." else null
                            if (authorNameError != null || authorEmailError != null || contentError != null) {
                                formMessage = "Popraw pola oznaczone jako błędne."
                                return@CommentFormCard
                            }
                            scope.launch {
                                isSending = true
                                formMessage = null
                                val result = appContainer.repository.publishComment(
                                    postId = postId,
                                    authorName = trimmedAuthorName,
                                    authorEmail = trimmedAuthorEmail,
                                    content = trimmedContent,
                                    parentId = replyTarget?.id ?: 0
                                )
                                result.onSuccess { published ->
                                    appContainer.preferencesRepository.updateCommentDraft(
                                        authorName = trimmedAuthorName,
                                        authorEmail = trimmedAuthorEmail
                                    )
                                    content = ""
                                    replyTarget = null
                                    isCommentFormVisible = false
                                    formMessage = published.message
                                    snackbarHostState.showSnackbar(published.message)
                                    comments = runCatching {
                                        appContainer.repository.fetchComments(postId, refresh = true)
                                    }.getOrDefault(comments)
                                }.onFailure {
                                    val message = it.message ?: "Nie udało się wysłać komentarza."
                                    formMessage = message
                                    snackbarHostState.showSnackbar(message)
                                }
                                isSending = false
                            }
                        }
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        formMessage?.let { StatePane(message = it) }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                formMessage = null
                                authorNameError = null
                                authorEmailError = null
                                contentError = null
                                isCommentFormVisible = true
                            }
                        ) {
                            Text("Dodaj komentarz")
                        }
                    }
                }
            }
            if (isLoading && comments.isEmpty()) {
                item { StatePane(message = "Ładowanie komentarzy…", showLoading = true) }
            }
            if (listError != null && comments.isEmpty()) {
                item { StatePane(message = listError.orEmpty()) }
            }
            if (!isLoading && listError.isNullOrBlank() && comments.isEmpty()) {
                item { StatePane(message = "Brak komentarzy. Możesz dodać pierwszy komentarz.") }
            }
            items(threadedComments, key = { it.comment.id }) { threadedComment ->
                CommentCard(
                    threadedComment = threadedComment,
                    onReply = { comment ->
                        replyTarget = comment
                        isCommentFormVisible = true
                        formMessage = "Odpowiadasz na komentarz użytkownika ${comment.authorName}."
                        scope.launch {
                            listState.animateScrollToItem(0)
                            contentFocusRequester.requestFocus()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CommentFormCard(
    authorName: String,
    onAuthorNameChange: (String) -> Unit,
    authorEmail: String,
    onAuthorEmailChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
    replyTarget: Comment?,
    onCancelReply: () -> Unit,
    onCancelForm: () -> Unit,
    authorNameError: String?,
    authorEmailError: String?,
    contentError: String?,
    formMessage: String?,
    isSending: Boolean,
    authorNameFocusRequester: FocusRequester,
    authorEmailFocusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Dodaj komentarz", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "Adres e-mail jest wymagany przez WordPress i nie będzie publicznie pokazywany.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (replyTarget != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Odpowiedź na komentarz użytkownika ${replyTarget.authorName}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onCancelReply) {
                            Text("Anuluj odpowiedź")
                        }
                    }
                }
            }
            formMessage?.let { StatePane(message = it) }
            OutlinedTextField(
                value = authorName,
                onValueChange = onAuthorNameChange,
                label = { Text("Imię lub nick") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(authorNameFocusRequester),
                singleLine = true,
                isError = authorNameError != null,
                supportingText = {
                    authorNameError?.let { Text(it) }
                }
            )
            OutlinedTextField(
                value = authorEmail,
                onValueChange = onAuthorEmailChange,
                label = { Text("E-mail") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(authorEmailFocusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isError = authorEmailError != null,
                supportingText = {
                    authorEmailError?.let { Text(it) }
                }
            )
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("Komentarz") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(contentFocusRequester),
                minLines = 4,
                isError = contentError != null,
                supportingText = {
                    contentError?.let { Text(it) }
                }
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSubmit,
                enabled = !isSending
            ) {
                Text(if (isSending) "Wysyłanie…" else "Wyślij komentarz")
            }
            TextButton(
                onClick = onCancelForm,
                enabled = !isSending
            ) {
                Text("Anuluj")
            }
        }
    }
}

@Composable
private fun CommentCard(
    threadedComment: ThreadedComment,
    onReply: (Comment) -> Unit
) {
    val comment = threadedComment.comment
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (threadedComment.depth.coerceAtMost(4) * 16).dp),
        colors = CardDefaults.cardColors(
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
            if (threadedComment.parentAuthorName != null) {
                Text(
                    text = "Odpowiedź na komentarz: ${threadedComment.parentAuthorName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            comment.formattedDate?.let { formattedDate ->
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AccessibleHtmlText(html = comment.content.rendered)
            TextButton(onClick = { onReply(comment) }) {
                Text("Odpowiedz")
            }
        }
    }
}

private fun String.isLikelyEmailAddress(): Boolean {
    return Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(this)
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
                            kind = net.tyflopodcast.tyflocentrum.core.model.ContentKind.PODCAST,
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
                            kind = net.tyflopodcast.tyflocentrum.core.model.ContentKind.ARTICLE,
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
    val context = LocalContext.current
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
                selectedIndex = if (settings.contentKindLabelPosition == net.tyflopodcast.tyflocentrum.core.model.ContentKindLabelPosition.BEFORE) 0 else 1,
                onSelected = { selected ->
                    scope.launch {
                        appContainer.preferencesRepository.setContentKindLabelPosition(
                            if (selected == 0) net.tyflopodcast.tyflocentrum.core.model.ContentKindLabelPosition.BEFORE
                            else net.tyflopodcast.tyflocentrum.core.model.ContentKindLabelPosition.AFTER
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

            Text(text = "Prywatność i wsparcie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "Znajdziesz tu publiczną politykę prywatności i kanał wsparcia dla aplikacji.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { openUri(context, APP_PRIVACY_POLICY_URL) }
            ) {
                Text("Otwórz politykę prywatności")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { openUri(context, APP_SUPPORT_URL) }
            ) {
                Text("Otwórz stronę wsparcia")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { openUri(context, APP_SUPPORT_EMAIL) }
            ) {
                Text("Napisz wiadomość e-mail")
            }

            if (BuildConfig.DEBUG) {
                Text(text = "Diagnostyka Cast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "Odtwórz problem z Chromecastem, a potem udostępnij log z tej sekcji.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        shareText(
                            context = context,
                            text = appContainer.castDiagnostics.snapshot(),
                            subject = "Log Cast Tyflocentrum"
                        )
                    }
                ) {
                    Text("Udostępnij log Cast")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { appContainer.castDiagnostics.clear() }
                ) {
                    Text("Wyczyść log Cast")
                }
            }
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
