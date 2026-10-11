package net.tyflopodcast.tyflocentrum.ui.common

import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.tyflopodcast.tyflocentrum.core.model.TimeLabel
import net.tyflopodcast.tyflocentrum.core.model.timeLabel
import net.tyflopodcast.tyflocentrum.core.network.ContentTimeStore
import net.tyflopodcast.tyflocentrum.core.network.TimeKey
import net.tyflopodcast.tyflocentrum.core.network.TimeRecord
import net.tyflopodcast.tyflocentrum.core.network.TimeRequest

class ContentTimeLabels(
    private val labels: Map<TimeKey, TimeLabel>,
    val refreshing: Boolean = false,
    val refresh: () -> Unit = {}
) {
    fun label(request: TimeRequest): TimeLabel = labels[request.key] ?: TimeLabel("Czas niedostępny", "Czas niedostępny")
}

/** Wspólna dostępna akcja na listach bez odświeżania całej zawartości. */
@Composable
fun ContentTimeRefreshButton(times: ContentTimeLabels) {
    ContentRefreshButton(enabled = !times.refreshing, onRefresh = times.refresh)
}

/** Nazwa na węźle przycisku, bez semantycznego dziecka ikony.
 * clearAndSetSemantics przed clickable Material3 usuwa również jego OnClick.
 * Nie kasujemy semantyki przycisku ani nie dublujemy jego akcji dla disabled. */
@Composable
fun ContentRefreshButton(enabled: Boolean, onRefresh: () -> Unit) {
    IconButton(onClick = onRefresh, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Odśwież" }) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
    }
}

/** Jeden efekt na listę, nie na wiersz. Nie zmienia kolejności ani fokusu. */
@Composable
fun rememberContentTimeLabels(
    requests: List<TimeRequest>,
    store: ContentTimeStore,
    clock: () -> Long = System::currentTimeMillis
): ContentTimeLabels {
    val snapshot by store.state.collectAsStateWithLifecycle()
    val revision by store.revision.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(clock()) }
    var refreshing by remember { mutableStateOf(false) }
    // Dane widoku są ograniczone jego listą, nie globalnym cache 512 rekordów.
    // Wyrzucenie wpisu z cache nie usuwa czasu z nadal widocznego wiersza.
    val visible = remember(store) { mutableStateMapOf<TimeKey, TimeRecord>() }
    val keys = requests.map { it.key }.toSet()
    fun merge(records: Map<TimeKey, TimeRecord>) {
        records.forEach { (key, record) ->
            if (key in keys && record.sequence >= (visible[key]?.sequence ?: 0)) visible[key] = record
        }
    }
    LaunchedEffect(snapshot, requests) {
        visible.keys.retainAll(keys)
        merge(snapshot)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    LaunchedEffect(requests, store, revision, resumed) {
        refreshing = true
        try {
            store.load(requests, revalidate = true, visibleRecords = visible.toMap()) { records -> merge(records) }
        } finally {
            refreshing = false
        }
    }
    // Zegar wyłącznie wygasza etykiety, nigdy sam nie ponawia żądań.
    val records = visible.toMap()
    LaunchedEffect(records, requests, resumed) {
        while (true) {
            tick = clock()
            val next = requests.mapNotNull { records[it.key]?.expiresAt }.filter { it > tick }.minOrNull() ?: break
            delay((next - tick).coerceAtLeast(1))
        }
    }
    return remember(requests, records, revision, tick, resumed, refreshing) {
        val now = clock()
        ContentTimeLabels(requests.associate { it.key to store.value(it, records[it.key], now).timeLabel() }, refreshing) {
            store.refresh(requests)
        }
    }
}
