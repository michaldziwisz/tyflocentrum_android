package net.tyflopodcast.tyflocentrum.ui.common

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.tyflopodcast.tyflocentrum.core.model.TimeLabel
import net.tyflopodcast.tyflocentrum.core.model.timeLabel
import net.tyflopodcast.tyflocentrum.core.network.ContentTimeStore
import net.tyflopodcast.tyflocentrum.core.network.TimeKey
import net.tyflopodcast.tyflocentrum.core.network.TimeRequest

class ContentTimeLabels(private val labels: Map<TimeKey, TimeLabel>) {
    fun label(request: TimeRequest): TimeLabel = labels[request.key] ?: TimeLabel("Czas niedostępny", "Czas niedostępny")
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    LaunchedEffect(requests, store, revision, resumed) {
        store.load(requests)
    }
    // TTL działa także przy nieruchomej, otwartej liście. Ten zegar nie ponawia
    // żądań. Powrót z uśpienia przelicza czas od razu, bez czekania na timer.
    LaunchedEffect(snapshot, requests, resumed) {
        while (true) {
            tick = clock()
            val next = requests.mapNotNull { snapshot[it.key]?.expiresAt }.filter { it > tick }.minOrNull() ?: break
            delay((next - tick).coerceAtLeast(1))
        }
    }
    return remember(requests, snapshot, revision, tick, resumed) {
        val now = clock()
        ContentTimeLabels(requests.associate { it.key to store.value(it, now).timeLabel() })
    }
}
