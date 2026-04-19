package org.tyflocentrum.android.ui.common

import android.content.Intent
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import org.tyflocentrum.android.core.model.ContentKind
import org.tyflocentrum.android.core.model.ContentKindLabelPosition
import org.tyflocentrum.android.core.model.accessibilityTitle

enum class RootDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    NEWS("news", "Nowości", Icons.Outlined.Article),
    PODCASTS("podcasts", "Podcasty", Icons.Outlined.LibraryMusic),
    ARTICLES("articles", "Artykuły", Icons.Outlined.Article),
    SEARCH("search", "Szukaj", Icons.Outlined.CalendarMonth),
    RADIO("radio", "Tyfloradio", Icons.Outlined.LibraryMusic);

    companion object {
        fun fromDestination(destination: NavDestination?): RootDestination? {
            return entries.firstOrNull { root ->
                destination?.hierarchy?.any { it.route == root.route } == true
            }
        }
    }
}

@Composable
fun Announcement(message: String?) {
    val view = LocalView.current
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            view.announceForAccessibility(message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreenScaffold(
    navController: NavHostController,
    title: String,
    rootDestination: RootDestination? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 2,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (rootDestination == null) {
                        IconButton(
                            onClick = { navController.navigateUp() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Wróć"
                            )
                        }
                    }
                },
                actions = {
                    actions()
                    if (rootDestination != null) {
                        IconButton(onClick = { navController.navigate("favorites") }) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Ulubione"
                            )
                        }
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Ustawienia"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (rootDestination != null) {
                NavigationBar {
                    RootDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = rootDestination == destination,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        content(padding)
    }
}

@Composable
fun StatePane(
    message: String,
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showLoading) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            contentDescription = message
                            liveRegion = LiveRegionMode.Polite
                        }
                )
            }
            if (retryLabel != null && onRetry != null) {
                Button(onClick = onRetry) {
                    Text(retryLabel)
                }
            }
        }
    }
}

@Composable
fun AccessibleHtmlText(
    html: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = {
            TextView(context).apply {
                textSize = 18f
                setTextColor(textColor)
                setLineSpacing(0f, 1.3f)
                movementMethod = LinkMovementMethod.getInstance()
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
                linksClickable = true
                setPadding(0, 0, 0, 0)
            }
        },
        update = { textView ->
            textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    )
}

@Composable
fun PlainTextScreen(
    text: String,
    modifier: Modifier = Modifier
) {
    SelectionContainer {
        Text(
            text = text,
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp)
        )
    }
}

@Composable
fun ContentListItem(
    title: String,
    date: String,
    modifier: Modifier = Modifier,
    kind: ContentKind? = null,
    contentKindLabelPosition: ContentKindLabelPosition = ContentKindLabelPosition.BEFORE,
    leadingContent: @Composable (() -> Unit)? = null,
    supportingText: String? = null,
    onOpen: () -> Unit,
    onListen: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    favoriteLabel: String? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    val accessibilityTitle = remember(title, kind, contentKindLabelPosition) {
        kind?.accessibilityTitle(title, contentKindLabelPosition) ?: title
    }
    val customActions = remember(onListen, onCopyLink, favoriteLabel, onToggleFavorite) {
        buildList {
            if (onListen != null) {
                add(CustomAccessibilityAction("Słuchaj") {
                    onListen()
                    true
                })
            }
            if (onCopyLink != null) {
                add(CustomAccessibilityAction("Skopiuj link") {
                    onCopyLink()
                    true
                })
            }
            if (favoriteLabel != null && onToggleFavorite != null) {
                add(CustomAccessibilityAction(favoriteLabel) {
                    onToggleFavorite()
                    true
                })
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = accessibilityTitle
                stateDescription = listOfNotNull(date, supportingText).joinToString(", ")
                onClick(label = "Otwórz szczegóły") {
                    onOpen()
                    true
                }
                if (customActions.isNotEmpty()) {
                    this.customActions = customActions
                }
            }
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (leadingContent != null) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .padding(top = 2.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    leadingContent()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
            .semantics(mergeDescendants = true) {}
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!supportingText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
fun OpenExternalButton(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Button(
        modifier = modifier.fillMaxWidth(),
        onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            context.startActivity(Intent.createChooser(intent, label))
        }
    ) {
        Text(label)
    }
}

@Composable
fun FullScreenScrollable(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
fun SimpleListScreen(
    contentPadding: PaddingValues,
    items: List<@Composable () -> Unit>,
    footer: @Composable (() -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp + contentPadding.calculateTopPadding(),
            end = 16.dp,
            bottom = 16.dp + contentPadding.calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { item ->
            item()
        }
        if (footer != null) {
            item { footer() }
        }
    }
}
