package net.tyflopodcast.tyflocentrum.ui.common

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Kontrolka różnicowa, nie zmiana kontraktu ani kodu produktu. */
class ReaderDynamicLabelControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun measure(useState: Boolean) {
        val harness = RefreshAccessibilityHarness(compose, if(useState) "reader-state" else "reader-changing-name")
        val time = mutableStateOf("Czas niedostępny")
        val initial = "Czas niedostępny"
        // Wspólny dla obu gałęzi moment i całkowity budżet obserwacji stanu końcowego.
        val observationBudgetMs = 8_000L
        val updated = "Czas trwania: 1 minuta 1 sekunda"
        harness.withTouchExploration {
            compose.setContent {
                MaterialTheme {
                    LazyColumn(Modifier.testTag("fixture-list")) {
                        items((0 until 40).toList(), key = { it }) { id ->
                            val name = "Wiersz $id"
                            Box(Modifier.fillMaxWidth().height(72.dp).clearAndSetSemantics {
                                contentDescription = if(useState) name else "$name, ${time.value}"
                                if(useState) stateDescription = time.value
                                onClick { true }
                            }.clickable {}) { Text("$name, ${time.value}") }
                        }
                    }
                }
            }
            compose.onNodeWithTag("fixture-list").performScrollToIndex(12)
            val beforeName = if(useState) "Wiersz 17" else "Wiersz 17, $initial"
            compose.waitUntil(5_000) { harness.named(beforeName).size == 1 }
            harness.focusByReader(beforeName)
            harness.dump("before-home")
            val activity = compose.activity
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assertTrue(harness.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            Thread.sleep(1_500)
            instrumentation.runOnMainSync { time.value = updated }
            val context = instrumentation.targetContext
            context.startActivity(Intent(context, activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            compose.waitUntil(10_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
            val afterName = if(useState) "Wiersz 17" else "Wiersz 17, $updated"
            compose.waitUntil(5_000) { harness.named(afterName).size == 1 }
            // GRANICA: bez śladu zdarzeń dostępności jest to kontrola STANU KOŃCOWEGO po
            // identycznym budżecie obserwacji, nie kontrola całego przebiegu fokusu — nie
            // wiemy, czy fokus w międzyczasie zniknął i wrócił. Oba warianty czekają pełny
            // budżet i są mierzone w tym samym momencie; gałąź dodatnia nie kończy pomiaru
            // wcześniej po pierwszym sukcesie.
            Thread.sleep(observationBudgetMs)
            try {
                val node = harness.named(afterName).single()
                if(useState) {
                    assertTrue("Gałąź dodatnia: fokus utrzymany w stanie końcowym",node.isAccessibilityFocused)
                    assertEquals(updated,node.stateDescription?.toString())
                } else {
                    assertFalse("Kontrolka ujemna musi odtworzyć utratę fokusu",node.isAccessibilityFocused)
                }
            } finally { harness.dump("after-home") }
        }
    }
    @Test fun changingNameReproducesFocusLoss() = measure(false)
    @Test fun stableNameChangingStateRestoresFocus() = measure(true)
}
