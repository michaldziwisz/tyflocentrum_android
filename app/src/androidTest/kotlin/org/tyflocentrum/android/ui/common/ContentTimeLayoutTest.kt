package net.tyflopodcast.tyflocentrum.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import net.tyflopodcast.tyflocentrum.core.model.ContentKind
import net.tyflopodcast.tyflocentrum.core.model.TimeLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Rzeczywisty pomiar układu wspólnego wiersza, bez imitowania drzewa AX. */
class ContentTimeLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyHeight(kind: ContentKind, ready: List<TimeLabel>) {
        val unknown = TimeLabel("Czas niedostępny", "Czas niedostępny")
        val time = mutableStateOf(unknown)
        compose.setContent {
            MaterialTheme {
                // Wiersz listy na emulatorze 320 dp, po odjęciu marginesów listy.
                Box(Modifier.width(288.dp)) {
                    ContentListItem(
                        title = "Pozycja 17",
                        date = "8 paź 2026",
                        kind = kind,
                        leadingContent = { Text("•") },
                        contentTime = time.value,
                        onOpen = {}
                    )
                }
            }
        }
        val row = compose.onNode(hasContentDescription("Pozycja 17", substring = true) and hasClickAction())
        val before = row.fetchSemanticsNode()
        val height = before.boundsInRoot.height
        for (label in ready + unknown) {
            compose.runOnIdle { time.value = label }
            compose.onNodeWithText("8 paź 2026 · ${label.visible}", useUnmergedTree = true).assertExists()
            val after = row.fetchSemanticsNode()
            println("ROW_LAYOUT kind=$kind label=${label.visible} before=$height after=${after.boundsInRoot.height}")
            assertEquals("Tożsamość wiersza po aktualizacji czasu", before.id, after.id)
            assertEquals("Aktualizacja czasu zmieniła wysokość wiersza", height, after.boundsInRoot.height, 0f)
        }
    }

    @Test fun audioTimeKeepsRowHeight() = verifyHeight(ContentKind.PODCAST, listOf(
        TimeLabel("Czas trwania: 1 min 1 s", "Czas trwania: 1 minuta 1 sekunda"),
        TimeLabel("Czas trwania: 2 min 2 s", "Czas trwania: 2 minuty 2 sekundy")
    ))

    @Test fun readingTimeKeepsRowHeight() = verifyHeight(ContentKind.ARTICLE, listOf(
        TimeLabel("Czytanie: około 2 min", "Czytanie: około 2 minut"),
        TimeLabel("Czytanie: około 3 min", "Czytanie: około 3 minut")
    ))
}
