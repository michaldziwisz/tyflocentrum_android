package org.tyflocentrum.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tyflocentrum.android.core.model.AppSettings
import org.tyflocentrum.android.core.model.ChapterMarker
import org.tyflocentrum.android.core.model.Comment
import org.tyflocentrum.android.core.model.ContactDraft
import org.tyflocentrum.android.core.model.FavoriteArticleOrigin
import org.tyflocentrum.android.core.model.FavoriteItem
import org.tyflocentrum.android.core.model.MagazineParser
import org.tyflocentrum.android.core.model.PlayerRequest
import org.tyflocentrum.android.core.model.PlaybackRatePolicy
import org.tyflocentrum.android.core.model.RelatedLink
import org.tyflocentrum.android.core.model.ShowNotesData
import org.tyflocentrum.android.core.model.ShowNotesParser
import org.tyflocentrum.android.core.model.WpPostDetail
import org.tyflocentrum.android.core.network.TyfloRepository
import org.tyflocentrum.android.core.recording.RecorderState
import org.tyflocentrum.android.core.recording.VoiceRecorderController
import org.tyflocentrum.android.ui.AppRoutes
import org.tyflocentrum.android.ui.LocalAppContainer
import org.tyflocentrum.android.ui.common.AccessibleHtmlText
import org.tyflocentrum.android.ui.common.AppScreenScaffold
import org.tyflocentrum.android.ui.common.CastRouteButton
import org.tyflocentrum.android.ui.common.ContentListItem
import org.tyflocentrum.android.ui.common.FullScreenScrollable
import org.tyflocentrum.android.ui.common.LinkifiedPlainText
import org.tyflocentrum.android.ui.common.StatePane
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val RADIO_STREAM_URL = "https://radio.tyflopodcast.net/hls/stream.m3u8"

private enum class VoiceCueMode {
    SPOKEN_PROMPT,
    TONE_ONLY
}

@Composable
fun RadioHomeScreen(
    navController: NavHostController,
    rootDestination: org.tyflocentrum.android.ui.common.RootDestination
) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var scheduleText by remember { mutableStateOf<String?>(null) }
    var isLoadingSchedule by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun loadSchedule() {
        scope.launch {
            isLoadingSchedule = true
            runCatching {
                appContainer.repository.getRadioSchedule()
            }.onSuccess { schedule ->
                scheduleText = schedule.text?.trim().orEmpty().ifBlank { null }
                errorMessage = schedule.error
            }.onFailure {
                errorMessage = "Nie udało się pobrać ramówki."
            }
            isLoadingSchedule = false
        }
    }

    AppScreenScaffold(
        navController = navController,
        title = "Tyfloradio",
        rootDestination = rootDestination,
        snackbarHostState = snackbarHostState,
        actions = {
            CastRouteButton()
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(detailPadding(padding)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navController.navigate(
                            AppRoutes.player(
                                url = RADIO_STREAM_URL,
                                title = "Tyfloradio",
                                subtitle = null,
                                live = true,
                                postId = null
                            )
                        )
                    }
                ) {
                    Icon(Icons.Outlined.Radio, contentDescription = null)
                    Text("Posłuchaj Tyfloradia", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { loadSchedule() }) {
                    Icon(Icons.Filled.Schedule, contentDescription = null)
                    Text("Sprawdź ramówkę", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val available = runCatching { appContainer.repository.getTpAvailability() }.getOrNull()?.available == true
                            if (available) {
                                navController.navigate(AppRoutes.CONTACT_MENU)
                            } else {
                                snackbarHostState.showSnackbar("Na antenie nie trwa teraz audycja interaktywna.")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null)
                    Text("Skontaktuj się z Tyfloradiem", modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (isLoadingSchedule) {
                item { StatePane(message = "Ładowanie ramówki…", showLoading = true) }
            } else if (scheduleText != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Ramówka", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            LinkifiedPlainText(scheduleText.orEmpty())
                        }
                    }
                }
            } else if (errorMessage != null) {
                item { StatePane(message = errorMessage.orEmpty()) }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    navController: NavHostController,
    url: String,
    title: String,
    subtitle: String?,
    isLive: Boolean,
    postId: Int?,
    seekMs: Long?
) {
    val appContainer = LocalAppContainer.current
    val playerState by appContainer.playerController.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val cachedComments = remember(postId) { postId?.let(appContainer.repository::peekComments).orEmpty() }
    val cachedShowNotes = remember(postId) { postId?.let(appContainer.repository::peekShowNotes) }
    var comments by remember(postId) { mutableStateOf(cachedComments) }
    var chapterMarkers by remember(postId) { mutableStateOf(cachedShowNotes?.markers.orEmpty()) }
    var relatedLinks by remember(postId) { mutableStateOf(cachedShowNotes?.links.orEmpty()) }
    var isShowNotesLoading by remember(postId, isLive) {
        mutableStateOf(postId != null && !isLive && cachedComments.isEmpty() && cachedShowNotes == null)
    }
    var showNotesError by remember(postId) { mutableStateOf<String?>(null) }

    val request = remember(url, title, subtitle, isLive, postId, seekMs) {
        PlayerRequest(
            url = url,
            title = title,
            subtitle = subtitle,
            isLive = isLive,
            podcastPostId = postId,
            initialSeekMs = seekMs
        )
    }

    LaunchedEffect(request) {
        appContainer.playerController.play(request)
    }

    LaunchedEffect(postId, isLive) {
        if (postId != null && !isLive) {
            runCatching {
                loadShowNotesData(appContainer.repository, postId)
            }.onSuccess { (loadedComments, showNotes) ->
                comments = loadedComments
                chapterMarkers = showNotes.markers
                relatedLinks = showNotes.links
                showNotesError = null
            }.onFailure {
                showNotesError = "Nie udało się pobrać dodatków do audycji."
            }
            isShowNotesLoading = false
        } else {
            comments = emptyList()
            chapterMarkers = emptyList()
            relatedLinks = emptyList()
            showNotesError = null
            isShowNotesLoading = false
        }
    }

    val hasSupplementaryContent = chapterMarkers.isNotEmpty() || relatedLinks.isNotEmpty() || comments.isNotEmpty()

    AppScreenScaffold(
        navController = navController,
        title = "Odtwarzacz",
        snackbarHostState = snackbarHostState,
        showMiniPlayer = false,
        actions = {
            CastRouteButton()
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (!subtitle.isNullOrBlank()) {
                            Text(text = subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (isLive && playerState.isRemotePlayback) {
                            Text(
                                text = "Trwa odtwarzanie na urządzeniu Cast.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isLive) {
                                IconButton(onClick = { appContainer.playerController.skipBackward() }) {
                                    Icon(Icons.Filled.VolumeDown, contentDescription = "Cofnij 30 sekund")
                                }
                            }
                            IconButton(
                                onClick = { appContainer.playerController.togglePlayPause(request) }
                            ) {
                                Icon(
                                    imageVector = if (playerState.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                                    contentDescription = if (playerState.isPlaying) "Pauza" else "Odtwarzaj",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (!isLive) {
                                IconButton(onClick = { appContainer.playerController.skipForward() }) {
                                    Icon(Icons.Filled.VolumeUp, contentDescription = "Przewiń do przodu 30 sekund")
                                }
                            }
                        }

                        if (!isLive) {
                            val duration = playerState.durationMs ?: 0L
                            Text(
                                text = "${formatPlaybackTime(playerState.elapsedMs)} / ${formatPlaybackTime(duration)}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Slider(
                                modifier = Modifier.semantics {
                                    contentDescription = "Pozycja odtwarzania"
                                    stateDescription =
                                        "${formatPlaybackTime(playerState.elapsedMs)} z ${formatPlaybackTime(duration)}"
                                },
                                value = playerState.elapsedMs.toFloat(),
                                onValueChange = { appContainer.playerController.seekTo(it.toLong()) },
                                valueRange = 0f..duration.coerceAtLeast(1L).toFloat()
                            )
                            PlaybackRateControls(
                                playbackRate = playerState.playbackRate,
                                onPrevious = {
                                    appContainer.playerController.setPlaybackRate(
                                        PlaybackRatePolicy.previous(playerState.playbackRate)
                                    )
                                },
                                onCycle = { appContainer.playerController.cyclePlaybackRate() },
                                onNext = {
                                    appContainer.playerController.setPlaybackRate(
                                        PlaybackRatePolicy.next(playerState.playbackRate)
                                    )
                                }
                            )
                        } else {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        val available = runCatching { appContainer.repository.getTpAvailability() }.getOrNull()?.available == true
                                        if (available) {
                                            navController.navigate(AppRoutes.CONTACT_MENU)
                                        } else {
                                            snackbarHostState.showSnackbar("Na antenie nie trwa teraz audycja interaktywna.")
                                        }
                                    }
                                }
                            ) {
                                Text("Skontaktuj się z Tyfloradiem")
                            }
                        }

                        playerState.errorMessage?.let {
                            StatePane(message = it)
                        }
                    }
                }
            }

            if (!isLive && postId != null) {
                if (isShowNotesLoading && !hasSupplementaryContent) {
                    item {
                        StatePane(message = "Ładowanie dodatków do audycji…", showLoading = true)
                    }
                } else if (hasSupplementaryContent) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Dodatki do audycji", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                                if (chapterMarkers.isNotEmpty()) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { navController.navigate(AppRoutes.playerMarkers(postId, title, subtitle)) }
                                    ) {
                                        Text("Znaczniki czasu: ${chapterMarkers.size}")
                                    }
                                }

                                if (relatedLinks.isNotEmpty()) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { navController.navigate(AppRoutes.playerLinks(postId, title, subtitle)) }
                                    ) {
                                        Text("Odnośniki: ${relatedLinks.size}")
                                    }
                                }

                                if (comments.isNotEmpty()) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { navController.navigate(AppRoutes.comments(postId)) }
                                    ) {
                                        Text("Komentarze: ${comments.size}")
                                    }
                                }
                            }
                        }
                    }
                } else if (!showNotesError.isNullOrBlank()) {
                    item {
                        StatePane(message = showNotesError.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerChapterMarkersScreen(
    navController: NavHostController,
    postId: Int,
    title: String,
    subtitle: String?
) {
    val appContainer = LocalAppContainer.current
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val cachedShowNotes = remember(postId) { appContainer.repository.peekShowNotes(postId) }
    var markers by remember(postId) { mutableStateOf(cachedShowNotes?.markers.orEmpty()) }
    var isLoading by remember(postId) { mutableStateOf(cachedShowNotes == null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }

    LaunchedEffect(postId) {
        runCatching {
            loadShowNotesData(appContainer.repository, postId).second.markers
        }.onSuccess {
            markers = it
            error = null
        }.onFailure {
            error = "Nie udało się pobrać znaczników czasu."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = "Znaczniki czasu"
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && markers.isEmpty()) {
                item { StatePane(message = "Ładowanie znaczników czasu…", showLoading = true) }
            }
            if (!error.isNullOrBlank() && markers.isEmpty()) {
                item { StatePane(message = error.orEmpty()) }
            }
            if (!isLoading && error.isNullOrBlank() && markers.isEmpty()) {
                item { StatePane(message = "Brak znaczników czasu.") }
            }
            items(markers, key = { it.id }) { marker ->
                val favoriteItem = FavoriteItem.TopicFavorite(
                    podcastId = postId,
                    podcastTitle = title,
                    podcastSubtitle = subtitle,
                    topicTitle = marker.title,
                    seconds = marker.seconds
                )
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = marker.title,
                    date = formatPlaybackTime((marker.seconds * 1000).toLong()),
                    onOpen = {
                        appContainer.playerController.seekTo((marker.seconds * 1000).toLong())
                        appContainer.playerController.resume()
                        navController.navigateUp()
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
fun PlayerRelatedLinksScreen(
    navController: NavHostController,
    postId: Int,
    title: String,
    subtitle: String?
) {
    val appContainer = LocalAppContainer.current
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val cachedShowNotes = remember(postId) { appContainer.repository.peekShowNotes(postId) }
    var links by remember(postId) { mutableStateOf(cachedShowNotes?.links.orEmpty()) }
    var isLoading by remember(postId) { mutableStateOf(cachedShowNotes == null) }
    var error by remember(postId) { mutableStateOf<String?>(null) }

    LaunchedEffect(postId) {
        runCatching {
            loadShowNotesData(appContainer.repository, postId).second.links
        }.onSuccess {
            links = it
            error = null
        }.onFailure {
            error = "Nie udało się pobrać odnośników."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = "Odnośniki",
        snackbarHostState = snackbarHostState
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = detailPadding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading && links.isEmpty()) {
                item { StatePane(message = "Ładowanie odnośników…", showLoading = true) }
            }
            if (!error.isNullOrBlank() && links.isEmpty()) {
                item { StatePane(message = error.orEmpty()) }
            }
            if (!isLoading && error.isNullOrBlank() && links.isEmpty()) {
                item { StatePane(message = "Brak odnośników.") }
            }
            items(links, key = { it.id }) { link ->
                val favoriteItem = FavoriteItem.LinkFavorite(
                    podcastId = postId,
                    podcastTitle = title,
                    podcastSubtitle = subtitle,
                    linkTitle = link.title,
                    urlString = link.url
                )
                val isFavorite = favorites.any { it.id == favoriteItem.id }
                ContentListItem(
                    title = link.title,
                    date = "",
                    supportingText = linkHostLabel(link.url),
                    leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    onOpen = { openUri(context, link.url) },
                    onCopyLink = {
                        copyToClipboard(context, copyableLinkValue(link.url))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackRateControls(
    playbackRate: Float,
    onPrevious: () -> Unit,
    onCycle: () -> Unit,
    onNext: () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useStackedLayout = maxWidth < 420.dp || fontScale > 1.1f

        if (useStackedLayout) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPrevious
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Text("Wolniej", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCycle
                ) {
                    Text("Prędkość ${PlaybackRatePolicy.format(playbackRate)}x")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNext
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Text("Szybciej", modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPrevious) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Text("Wolniej", modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = onCycle) {
                    Text("Prędkość ${PlaybackRatePolicy.format(playbackRate)}x")
                }
                Button(onClick = onNext) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Text("Szybciej", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private suspend fun loadShowNotesData(
    repository: TyfloRepository,
    postId: Int
): Pair<List<Comment>, ShowNotesData> {
    val comments = repository.fetchComments(postId)
    val cachedShowNotes = repository.peekShowNotes(postId)
    val showNotes = cachedShowNotes ?: withContext(Dispatchers.Default) {
        val (markers, links) = ShowNotesParser.parse(comments)
        ShowNotesData(markers = markers, links = links)
    }.also {
        repository.storeShowNotes(postId, it)
    }
    return comments to showNotes
}

private fun linkHostLabel(value: String): String? {
    val uri = value.toUri()
    return when {
        uri.scheme.equals("mailto", ignoreCase = true) -> "E-mail"
        !uri.host.isNullOrBlank() -> uri.host
        else -> null
    }
}

private fun copyableLinkValue(value: String): String {
    return if (value.startsWith("mailto:", ignoreCase = true)) {
        value.removePrefix("mailto:")
    } else {
        value
    }
}

@Composable
fun MagazineIssueScreen(
    navController: NavHostController,
    issueId: Int
) {
    val appContainer = LocalAppContainer.current
    val cachedState = remember(issueId) { appContainer.repository.peekMagazineIssueScreenCache(issueId) }
    var issue by remember(issueId) { mutableStateOf(cachedState?.issue) }
    var tocItems by remember(issueId) { mutableStateOf(cachedState?.tocItems ?: emptyList()) }
    var pdfUrl by remember(issueId) { mutableStateOf(cachedState?.pdfUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember(issueId) { mutableStateOf(cachedState == null) }
    val context = LocalContext.current

    LaunchedEffect(issueId) {
        if (issue == null) {
            isLoading = true
        }
        runCatching {
            val page = appContainer.repository.fetchTyfloswiatPage(issueId)
            val children = appContainer.repository.fetchTyfloswiatPageSummaries(issueId)
            Triple(page, children, MagazineParser.extractFirstPdfUrl(page.content.rendered))
        }.onSuccess { (page, children, pdf) ->
            val orderedToc = MagazineParser.orderedTableOfContents(children, page.content.rendered)
            issue = page
            tocItems = orderedToc
            pdfUrl = pdf
            appContainer.repository.storeMagazineIssueScreenCache(
                issueId = issueId,
                cache = org.tyflocentrum.android.core.network.MagazineIssueScreenCache(
                    issue = page,
                    tocItems = orderedToc,
                    pdfUrl = pdf
                )
            )
            error = null
        }.onFailure {
            error = "Nie udało się pobrać numeru czasopisma."
        }
        isLoading = false
    }

    AppScreenScaffold(
        navController = navController,
        title = issue?.title?.plainText ?: "Numer czasopisma"
    ) { padding ->
        if (isLoading && issue == null) {
            DetailStatePane(padding, "Ładowanie numeru czasopisma…", true)
        } else if (error != null && issue == null) {
            DetailStatePane(padding, error.orEmpty(), false)
        } else if (issue != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = detailPadding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pdfUrl?.let { pdf ->
                    item {
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { openUri(context, pdf) }) {
                            Text("Pobierz PDF")
                        }
                    }
                }
                if (tocItems.isNotEmpty()) {
                    item {
                        Text("Spis treści", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    items(tocItems, key = { it.id }) { item ->
                        ContentListItem(
                            title = item.title.plainText,
                            date = item.formattedDate,
                            onOpen = { navController.navigate(AppRoutes.articleDetail(item.id, FavoriteArticleOrigin.PAGE)) }
                        )
                    }
                } else {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(issue!!.title.plainText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                AccessibleHtmlText(html = issue!!.content.rendered)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactMenuScreen(
    navController: NavHostController
) {
    AppScreenScaffold(
        navController = navController,
        title = "Kontakt"
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(detailPadding(padding)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { navController.navigate(AppRoutes.CONTACT_TEXT) }) {
                Text("Napisz wiadomość tekstową")
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { navController.navigate(AppRoutes.CONTACT_VOICE) }) {
                Text("Nagraj wiadomość głosową")
            }
        }
    }
}

@Composable
fun ContactTextMessageScreen(
    navController: NavHostController
) {
    val appContainer = LocalAppContainer.current
    val draft by appContainer.preferencesRepository.contactDraftFlow.collectAsStateWithLifecycle(ContactDraft())
    val scope = rememberCoroutineScope()
    val nameFocusRequester = remember { FocusRequester() }
    val messageFocusRequester = remember { FocusRequester() }
    var name by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var didEditName by rememberSaveable { mutableStateOf(false) }
    var didEditMessage by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(draft.name) {
        if (!didEditName) {
            name = draft.name
        }
    }

    LaunchedEffect(draft.message) {
        if (!didEditMessage) {
            message = draft.message
        }
    }

    LaunchedEffect(nameError, messageError) {
        when {
            nameError != null -> nameFocusRequester.requestFocus()
            messageError != null -> messageFocusRequester.requestFocus()
        }
    }

    AppScreenScaffold(
        navController = navController,
        title = "Wiadomość"
    ) { padding ->
        FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
            error?.let { StatePane(message = it) }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    didEditName = true
                    name = it
                    nameError = null
                    error = null
                    scope.launch { appContainer.preferencesRepository.updateContactDraft(name = it) }
                },
                label = { Text("Nick lub imię") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                singleLine = true,
                isError = nameError != null,
                supportingText = {
                    nameError?.let { Text(it) }
                }
            )
            OutlinedTextField(
                value = message,
                onValueChange = {
                    didEditMessage = true
                    message = it
                    messageError = null
                    error = null
                    scope.launch { appContainer.preferencesRepository.updateContactDraft(message = it) }
                },
                label = { Text("Wiadomość") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .focusRequester(messageFocusRequester),
                isError = messageError != null,
                supportingText = {
                    messageError?.let { Text(it) }
                }
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val trimmedName = name.trim()
                    val trimmedMessage = message.trim()
                    nameError = if (trimmedName.isBlank()) "Wpisz nick lub imię." else null
                    messageError = if (trimmedMessage.isBlank()) "Wpisz treść wiadomości." else null
                    if (nameError != null || messageError != null) {
                        error = "Popraw pola oznaczone jako błędne."
                        return@Button
                    }
                    scope.launch {
                        isSending = true
                        error = null
                        val result = appContainer.repository.sendContactMessage(trimmedName, trimmedMessage)
                        result.onSuccess {
                            appContainer.preferencesRepository.updateContactDraft(name = trimmedName)
                            appContainer.preferencesRepository.resetContactMessage()
                            navController.navigateUp()
                        }.onFailure {
                            error = it.message ?: "Nie udało się wysłać wiadomości."
                        }
                        isSending = false
                    }
                },
                enabled = !isSending
            ) {
                Text(if (isSending) "Wysyłanie…" else "Wyślij wiadomość")
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContactVoiceMessageScreen(
    navController: NavHostController
) {
    val appContainer = LocalAppContainer.current
    val draft by appContainer.preferencesRepository.contactDraftFlow.collectAsStateWithLifecycle(ContactDraft())
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorderController(context, appContainer.playerController) }
    val recorderState by recorder.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val nameFocusRequester = remember { FocusRequester() }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }
    val voicePromptSpeaker = remember { VoicePromptSpeaker(context) }
    var name by rememberSaveable { mutableStateOf("") }
    var didEditName by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var holdLocked by remember { mutableStateOf(false) }
    var holdStartJob by remember { mutableStateOf<Job?>(null) }
    var assistedStartJob by remember { mutableStateOf<Job?>(null) }
    var holdStartY by remember { mutableFloatStateOf(0f) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var formMessage by remember { mutableStateOf<String?>(null) }
    var isAwaitingCue by remember { mutableStateOf(false) }

    fun performStartHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun performStopHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun cancelPendingAssistedStart() {
        assistedStartJob?.cancel()
        assistedStartJob = null
        isAwaitingCue = false
        voicePromptSpeaker.stop()
    }

    fun beginRecordingNow(withHaptic: Boolean = false): Boolean {
        formMessage = null
        val started = recorder.startRecording()
        if (started && withHaptic) {
            performStartHaptic()
        }
        return started
    }

    fun stopCurrentRecording(withHaptic: Boolean = false): Boolean {
        cancelPendingAssistedStart()
        val stopped = recorder.stopRecording()
        if (stopped && withHaptic) {
            performStopHaptic()
        }
        return stopped
    }

    fun launchPromptedRecording(cueMode: VoiceCueMode = VoiceCueMode.SPOKEN_PROMPT) {
        if (isAwaitingCue || recorderState.isProcessing || isSending) return
        cancelPendingAssistedStart()
        holdStartJob?.cancel()
        holdStartJob = null
        formMessage = null
        isAwaitingCue = true
        appContainer.playerController.pause()
        assistedStartJob = scope.launch {
            try {
                if (cueMode == VoiceCueMode.SPOKEN_PROMPT) {
                    val promptPlayed = voicePromptSpeaker.speak("Mów po sygnale")
                    if (promptPlayed) {
                        delay(120)
                    } else {
                        delay(250)
                    }
                } else {
                    delay(450)
                }
                toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 320)
                delay(380)
                beginRecordingNow()
            } finally {
                assistedStartJob = null
                isAwaitingCue = false
            }
        }
    }

    LaunchedEffect(draft.name) {
        if (!didEditName) {
            name = draft.name
        }
    }

    LaunchedEffect(nameError) {
        if (nameError != null) {
            nameFocusRequester.requestFocus()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchPromptedRecording(VoiceCueMode.SPOKEN_PROMPT)
        } else {
            scope.launch { snackbarHostState.showSnackbar("Bez dostępu do mikrofonu nie można nagrać głosówki.") }
        }
    }

    val onMediaButtonToggle by rememberUpdatedState(
        newValue = {
            formMessage = null
            if (recorderState.state == RecorderState.RECORDING) {
                stopCurrentRecording()
            } else if (hasRecordPermission(context)) {
                launchPromptedRecording(VoiceCueMode.TONE_ONLY)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            holdStartJob?.cancel()
            cancelPendingAssistedStart()
            runCatching { toneGenerator.release() }
            voicePromptSpeaker.shutdown()
            recorder.reset()
        }
    }

    DisposableEffect(appContainer.playerController) {
        val handler: (Intent) -> Boolean = handler@{ intent ->
            val keyEvent = intent.mediaButtonKeyEvent() ?: return@handler false
            if (!keyEvent.isVoiceRecorderControlKey()) {
                return@handler false
            }
            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyEvent.repeatCount == 0) {
                        onMediaButtonToggle()
                    }
                    true
                }
                KeyEvent.ACTION_UP -> true
                else -> false
            }
        }
        appContainer.playerController.setMediaButtonOverride(handler)
        onDispose {
            appContainer.playerController.setMediaButtonOverride(null)
        }
    }

    LaunchedEffect(recorderState.errorMessage) {
        if (!recorderState.errorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(recorderState.errorMessage!!)
            recorder.clearError()
        }
    }

    AppScreenScaffold(
        navController = navController,
        title = "Głosówka",
        snackbarHostState = snackbarHostState
    ) { padding ->
        FullScreenScrollable(modifier = Modifier.padding(detailPadding(padding))) {
            formMessage?.let { StatePane(message = it) }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    didEditName = true
                    name = it
                    nameError = null
                    formMessage = null
                    scope.launch { appContainer.preferencesRepository.updateContactDraft(name = it) }
                },
                label = { Text("Nick lub imię") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                singleLine = true,
                isError = nameError != null,
                supportingText = {
                    nameError?.let { Text(it) }
                }
            )

            Text(
                text = "TalkBack: użyj przycisku Rozpocznij/Zatrzymaj nagrywanie albo gestu 2 palce 2 razy w ekran. Najpierw usłyszysz komunikat, potem osobny sygnał i dopiero wtedy zacznie się nagrywanie. Dla wygody dotykowej możesz też przytrzymać pole poniżej i przeciągnąć w górę, aby zablokować nagrywanie.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    formMessage = null
                    if (recorderState.state == RecorderState.RECORDING) {
                        stopCurrentRecording()
                    } else if (hasRecordPermission(context)) {
                        launchPromptedRecording(VoiceCueMode.SPOKEN_PROMPT)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !recorderState.isProcessing && !isSending && !isAwaitingCue
            ) {
                Icon(
                    imageVector = if (recorderState.state == RecorderState.RECORDING) Icons.Filled.StopCircle else Icons.Filled.Mic,
                    contentDescription = null
                )
                Text(
                    text = when {
                        recorderState.state == RecorderState.RECORDING -> "Zatrzymaj nagrywanie"
                        recorderState.recordedDurationMs > 0 -> "Dograj kolejny fragment"
                        else -> "Rozpocznij nagrywanie"
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                    )
                    .clearAndSetSemantics {
                        contentDescription =
                            "Obszar przytrzymaj i mów. Przytrzymaj, aby nagrywać. Przeciągnij w górę, aby zablokować. Dla TalkBack wygodniejszy jest przycisk rozpoczęcia i zatrzymania nagrywania."
                    }
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                if (isAwaitingCue || recorderState.isProcessing || isSending) {
                                    return@pointerInteropFilter true
                                }
                                holdStartY = event.y
                                holdLocked = false
                                holdStartJob?.cancel()
                                holdStartJob = scope.launch {
                                    delay(200)
                                    if (hasRecordPermission(context)) {
                                        beginRecordingNow(withHaptic = true)
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (recorderState.state == RecorderState.RECORDING && !holdLocked && holdStartY - event.y > 120f) {
                                    holdLocked = true
                                    scope.launch { snackbarHostState.showSnackbar("Nagrywanie zablokowane") }
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                holdStartJob?.cancel()
                                holdStartJob = null
                                if (recorderState.state == RecorderState.RECORDING && !holdLocked) {
                                    stopCurrentRecording(withHaptic = true)
                                }
                                true
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        recorderState.state == RecorderState.RECORDING && holdLocked -> "Nagrywanie zablokowane"
                        recorderState.state == RecorderState.RECORDING -> "Nagrywanie…"
                        recorderState.recordedDurationMs > 0 -> "Przytrzymaj i dograj"
                        else -> "Przytrzymaj i mów"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!isAwaitingCue) {
                RecorderStatusCard(
                    message = when {
                        recorderState.isProcessing -> "Przygotowywanie nagrania…"
                        recorderState.state == RecorderState.RECORDING -> "Nagrywanie… ${formatPlaybackTime(recorderState.elapsedMs)}"
                        recorderState.recordedDurationMs > 0 -> "Nagranie gotowe. Możesz odsłuchać, usunąć albo dograć kolejny fragment."
                        else -> "Gotowe do nagrywania"
                    },
                    showLoading = recorderState.isProcessing
                )
            }

            if (recorderState.recordedDurationMs > 0) {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { recorder.togglePreview() }, enabled = !isSending) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null)
                    Text(
                        text = if (recorderState.state == RecorderState.PLAYING_PREVIEW) "Zatrzymaj odsłuch" else "Odsłuchaj",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { recorder.reset() },
                    enabled = !isSending
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Usuń nagranie", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val trimmedName = name.trim()
                        if (trimmedName.isBlank()) {
                            nameError = "Wpisz nick lub imię przed wysłaniem głosówki."
                            formMessage = "Nick lub imię są wymagane do wysłania nagrania."
                            return@Button
                        }
                        val file = recorder.recordedFileOrNull ?: return@Button
                        scope.launch {
                            isSending = true
                            formMessage = null
                            val result = appContainer.repository.sendVoiceMessage(trimmedName, file, recorderState.recordedDurationMs)
                            result.onSuccess {
                                appContainer.preferencesRepository.updateContactDraft(name = trimmedName)
                                recorder.reset()
                                navController.navigateUp()
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Nie udało się wysłać głosówki.")
                            }
                            isSending = false
                        }
                    },
                    enabled = recorderState.canSend && !recorderState.isProcessing && !isSending
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Text(if (isSending) "Wysyłanie…" else "Wyślij głosówkę", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun RecorderStatusCard(
    message: String,
    showLoading: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showLoading) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

private class VoicePromptSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val initResult = CompletableDeferred<Boolean>()
    private val pendingUtterances = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Boolean>>()
    private var textToSpeech: TextToSpeech? = null

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            val tts = textToSpeech
            if (status != TextToSpeech.SUCCESS || tts == null) {
                initResult.complete(false)
                return@TextToSpeech
            }
            runCatching {
                tts.language = Locale("pl", "PL")
                tts.setSpeechRate(1f)
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { completeUtterance(it, true) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { completeUtterance(it, false) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { completeUtterance(it, false) }
                        }
                    }
                )
            }
            initResult.complete(true)
        }
    }

    suspend fun speak(text: String): Boolean {
        if (!initResult.await()) return false
        val tts = textToSpeech ?: return false
        return suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            pendingUtterances[utteranceId] = continuation
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
            if (result == TextToSpeech.ERROR) {
                pendingUtterances.remove(utteranceId)
                continuation.resume(false)
            } else {
                continuation.invokeOnCancellation {
                    pendingUtterances.remove(utteranceId)
                    runCatching { tts.stop() }
                }
            }
        }
    }

    fun stop() {
        pendingUtterances.keys.toList().forEach { completeUtterance(it, false) }
        runCatching { textToSpeech?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
    }

    private fun completeUtterance(utteranceId: String, result: Boolean) {
        pendingUtterances.remove(utteranceId)?.resume(result)
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

private fun formatPlaybackTime(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tyflocentrum", value))
}

private fun openUri(context: Context, value: String) {
    val intent = Intent(Intent.ACTION_VIEW, value.toUri())
    ContextCompat.startActivity(context, intent, null)
}

private fun Intent.mediaButtonKeyEvent(): KeyEvent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
}

private fun KeyEvent.isVoiceRecorderControlKey(): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }
}

private fun hasRecordPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
