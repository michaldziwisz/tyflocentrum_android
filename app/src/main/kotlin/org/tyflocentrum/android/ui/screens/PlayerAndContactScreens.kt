package org.tyflocentrum.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tyflocentrum.android.core.model.AppSettings
import org.tyflocentrum.android.core.model.ChapterMarker
import org.tyflocentrum.android.core.model.ContactDraft
import org.tyflocentrum.android.core.model.FavoriteArticleOrigin
import org.tyflocentrum.android.core.model.FavoriteItem
import org.tyflocentrum.android.core.model.MagazineParser
import org.tyflocentrum.android.core.model.PlayerRequest
import org.tyflocentrum.android.core.model.PlaybackRatePolicy
import org.tyflocentrum.android.core.model.RelatedLink
import org.tyflocentrum.android.core.model.ShowNotesParser
import org.tyflocentrum.android.core.model.WpPostDetail
import org.tyflocentrum.android.core.recording.RecorderState
import org.tyflocentrum.android.core.recording.VoiceRecorderController
import org.tyflocentrum.android.ui.AppRoutes
import org.tyflocentrum.android.ui.LocalAppContainer
import org.tyflocentrum.android.ui.common.AccessibleHtmlText
import org.tyflocentrum.android.ui.common.AppScreenScaffold
import org.tyflocentrum.android.ui.common.ContentListItem
import org.tyflocentrum.android.ui.common.FullScreenScrollable
import org.tyflocentrum.android.ui.common.StatePane

private const val RADIO_STREAM_URL = "https://radio.tyflopodcast.net/hls/stream.m3u8"

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
        snackbarHostState = snackbarHostState
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(detailPadding(padding)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
            Button(modifier = Modifier.fillMaxWidth(), onClick = { loadSchedule() }) {
                Icon(Icons.Filled.Schedule, contentDescription = null)
                Text("Sprawdź ramówkę", modifier = Modifier.padding(start = 8.dp))
            }
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

            if (isLoadingSchedule) {
                StatePane(message = "Ładowanie ramówki…", showLoading = true)
            } else if (scheduleText != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Ramówka", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(scheduleText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else if (errorMessage != null) {
                StatePane(message = errorMessage.orEmpty())
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
    val favorites by appContainer.preferencesRepository.favoritesFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var chapterMarkers by remember { mutableStateOf(listOf<ChapterMarker>()) }
    var relatedLinks by remember { mutableStateOf(listOf<RelatedLink>()) }

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

    LaunchedEffect(postId) {
        if (postId != null && !isLive) {
            runCatching {
                val comments = appContainer.repository.fetchComments(postId)
                withContext(Dispatchers.Default) {
                    ShowNotesParser.parse(comments)
                }
            }.onSuccess { (markers, links) ->
                chapterMarkers = markers
                relatedLinks = links
            }
        }
    }

    AppScreenScaffold(
        navController = navController,
        title = "Odtwarzacz",
        snackbarHostState = snackbarHostState
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { appContainer.playerController.setPlaybackRate(PlaybackRatePolicy.previous(playerState.playbackRate)) }) {
                                    Icon(Icons.Filled.Speed, contentDescription = null)
                                    Text("Wolniej", modifier = Modifier.padding(start = 8.dp))
                                }
                                Button(onClick = { appContainer.playerController.cyclePlaybackRate() }) {
                                    Text("Prędkość ${PlaybackRatePolicy.format(playerState.playbackRate)}x")
                                }
                                Button(onClick = { appContainer.playerController.setPlaybackRate(PlaybackRatePolicy.next(playerState.playbackRate)) }) {
                                    Icon(Icons.Filled.Speed, contentDescription = null)
                                    Text("Szybciej", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
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

            if (!isLive && chapterMarkers.isNotEmpty()) {
                item {
                    Text("Znaczniki czasu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(chapterMarkers, key = { it.id }) { marker ->
                    val favoriteItem = FavoriteItem.TopicFavorite(
                        podcastId = postId ?: 0,
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
                        },
                        favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                        onToggleFavorite = {
                            scope.launch { appContainer.preferencesRepository.toggleFavorite(favoriteItem) }
                        }
                    )
                }
            }

            if (!isLive && relatedLinks.isNotEmpty()) {
                item {
                    Text("Odnośniki", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(relatedLinks, key = { it.id }) { link ->
                    val favoriteItem = FavoriteItem.LinkFavorite(
                        podcastId = postId ?: 0,
                        podcastTitle = title,
                        podcastSubtitle = subtitle,
                        linkTitle = link.title,
                        urlString = link.url
                    )
                    val isFavorite = favorites.any { it.id == favoriteItem.id }
                    ContentListItem(
                        title = link.title,
                        date = link.url,
                        leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        onOpen = { openUri(context, link.url) },
                        onCopyLink = { copyToClipboard(context, link.url) },
                        favoriteLabel = if (isFavorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                        onToggleFavorite = {
                            scope.launch { appContainer.preferencesRepository.toggleFavorite(favoriteItem) }
                        }
                    )
                }
            }
        }
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
    var name by remember { mutableStateOf(draft.name) }
    var message by remember { mutableStateOf(draft.message) }
    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf<String?>(null) }

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
                    name = it
                    nameError = null
                    error = null
                    scope.launch { appContainer.preferencesRepository.updateContactDraft(name = it) }
                },
                label = { Text("Imię") },
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
                    nameError = if (trimmedName.isBlank()) "Wpisz imię." else null
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
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorderController(context, appContainer.playerController) }
    val recorderState by recorder.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val nameFocusRequester = remember { FocusRequester() }
    var name by remember { mutableStateOf(draft.name) }
    var isSending by remember { mutableStateOf(false) }
    var earModeEnabled by remember { mutableStateOf(false) }
    var holdLocked by remember { mutableStateOf(false) }
    var holdStartJob by remember { mutableStateOf<Job?>(null) }
    var holdStartY by remember { mutableFloatStateOf(0f) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var formMessage by remember { mutableStateOf<String?>(null) }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val proximitySensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    val supportsEarMode = proximitySensor != null

    LaunchedEffect(nameError) {
        if (nameError != null) {
            nameFocusRequester.requestFocus()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recorder.startRecording()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Bez dostępu do mikrofonu nie można nagrać głosówki.") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holdStartJob?.cancel()
            recorder.reset()
        }
    }

    DisposableEffect(earModeEnabled, proximitySensor) {
        if (!earModeEnabled || proximitySensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val sensor = event?.values?.firstOrNull() ?: return
                    val isNear = sensor < (proximitySensor.maximumRange.coerceAtMost(5f))
                    if (isNear && recorderState.state != RecorderState.RECORDING) {
                        if (hasRecordPermission(context)) {
                            recorder.startRecording()
                        }
                    } else if (!isNear && recorderState.state == RecorderState.RECORDING) {
                        recorder.stopRecording()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose {
                sensorManager.unregisterListener(listener)
            }
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
                    name = it
                    nameError = null
                    formMessage = null
                    scope.launch { appContainer.preferencesRepository.updateContactDraft(name = it) }
                },
                label = { Text("Imię") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                singleLine = true,
                isError = nameError != null,
                supportingText = {
                    nameError?.let { Text(it) }
                }
            )

            if (supportsEarMode) {
                ToggleCard(
                    title = "Nagrywaj po przyłożeniu telefonu do ucha",
                    checked = earModeEnabled,
                    onCheckedChange = { earModeEnabled = it },
                    supportingText = "Przyłożenie telefonu rozpoczyna nagrywanie, oderwanie kończy."
                )
            }

            Text(
                text = "TalkBack: użyj przycisku Rozpocznij/Zatrzymaj nagrywanie. Dla wygody dotykowej możesz też przytrzymać pole poniżej i przeciągnąć w górę, aby zablokować nagrywanie.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    formMessage = null
                    if (recorderState.state == RecorderState.RECORDING) {
                        recorder.stopRecording()
                    } else if (hasRecordPermission(context)) {
                        recorder.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !recorderState.isProcessing && !isSending
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
                    .semantics {
                        contentDescription =
                            "Obszar przytrzymaj i mów. Dla TalkBack wygodniejszy jest przycisk rozpoczęcia i zatrzymania nagrywania."
                        stateDescription = when {
                            recorderState.state == RecorderState.RECORDING && holdLocked -> "Nagrywanie zablokowane"
                            recorderState.state == RecorderState.RECORDING -> "Nagrywanie trwa"
                            recorderState.recordedDurationMs > 0 -> "Gotowe do dogrania kolejnego fragmentu"
                            else -> "Gotowe do nagrywania"
                        }
                    }
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                holdStartY = event.y
                                holdLocked = false
                                holdStartJob?.cancel()
                                holdStartJob = scope.launch {
                                    delay(200)
                                    if (hasRecordPermission(context)) {
                                        recorder.startRecording()
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
                                    recorder.stopRecording()
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

            StatePane(
                message = when {
                    recorderState.isProcessing -> "Przygotowywanie nagrania…"
                    recorderState.state == RecorderState.RECORDING -> "Nagrywanie… ${formatPlaybackTime(recorderState.elapsedMs)}"
                    recorderState.recordedDurationMs > 0 -> "Nagranie gotowe. Możesz odsłuchać, usunąć albo dograć kolejny fragment."
                    else -> "Gotowe do nagrywania"
                },
                showLoading = recorderState.isProcessing
            )

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
                            nameError = "Wpisz imię przed wysłaniem głosówki."
                            formMessage = "Imię jest wymagane do wysłania nagrania."
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
private fun ToggleCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String
) {
    Card(
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.Switch(
                    checked = checked,
                    onCheckedChange = null
                )
            }
        }
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

private fun hasRecordPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
