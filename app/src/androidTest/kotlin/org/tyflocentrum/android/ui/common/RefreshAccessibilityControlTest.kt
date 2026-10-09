package net.tyflopodcast.tyflocentrum.ui.common

import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button as NativeButton
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RefreshAccessibilityControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeAndComposeControlsProveFocusAndRealClick() {
        val harness = RefreshAccessibilityHarness(compose, "apparatus")
        harness.withTouchExploration {
            var nativeCount by mutableIntStateOf(0)
            var composeCount by mutableIntStateOf(0)
            compose.setContent {
                MaterialTheme {
                    Column {
                        AndroidView(factory = { context ->
                            NativeButton(context).apply {
                                contentDescription = "Natywna kontrolka"
                                setOnClickListener { nativeCount++ }
                            }
                        }, update = { it.text = "Natywny licznik: $nativeCount" })
                        Button(onClick = { composeCount++ },
                            modifier = Modifier.semantics { contentDescription = "Kontrolka Compose" }) {
                            Text("Compose licznik: $composeCount")
                        }
                    }
                }
            }
            compose.waitForIdle()
            harness.dump("controls-ready")
            for (name in listOf("Natywna kontrolka", "Kontrolka Compose")) {
                compose.waitUntil(5_000) { harness.named(name).size == 1 }
                harness.focus(name)
                harness.focus(name) // Już zafokusowany węzeł: false nie oznacza utraty fokusu.
                val before = harness.named(name).single()
                harness.click(name)
                if (name == "Natywna kontrolka") {
                    compose.waitUntil(5_000) { harness.named(name).single().text?.toString() == "Natywny licznik: 1" }
                    compose.runOnIdle { assertEquals(1, nativeCount) }
                } else {
                    compose.onNodeWithText("Compose licznik: 1", useUnmergedTree = true).assertIsDisplayed()
                    compose.runOnIdle { assertEquals(1, composeCount) }
                }
                harness.dump("control-clicked")
                assertEquals(before, harness.named(name).single())
                assertTrue(harness.named(name).single().isAccessibilityFocused)
            }
        }
    }

    @Test fun legacyLosesActionAndRefreshKeepsNameActionAndDisabledState() {
        val harness = RefreshAccessibilityHarness(compose, "button-regression")
        harness.withTouchExploration {
            var oldCount by mutableIntStateOf(0)
            var newCount by mutableIntStateOf(0)
            var enabled by mutableStateOf(true)
            compose.setContent {
                MaterialTheme {
                    Column {
                        // Dokładny stary wzorzec produkcyjny, helper pozostaje niezmieniony.
                        IconButton(onClick = { oldCount++ }, modifier = Modifier.semanticButton("Dawny Odśwież")) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Dawny Odśwież")
                        }
                        Text("Dawny licznik: $oldCount")
                        ContentRefreshButton(enabled = enabled) { newCount++ }
                        Text("Nowy licznik: $newCount")
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { harness.named("Dawny Odśwież").size == 1 && harness.named("Odśwież").size == 1 }
            harness.dump("legacy-and-candidate")
            compose.onNodeWithContentDescription("Dawny Odśwież").assertHasNoClickAction()
            val old = harness.named("Dawny Odśwież").single()
            assertFalse(old.isClickable)
            assertFalse(old.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertFalse(old.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.onNodeWithText("Dawny licznik: 0").assertIsDisplayed()
            compose.onNodeWithContentDescription("Odśwież").assertHasClickAction().assertIsEnabled()
            compose.onAllNodes(hasContentDescription("Odśwież"), useUnmergedTree = true).assertCountEquals(1)
            assertEquals(0, harness.named("Odśwież").single().childCount)
            harness.focus("Odśwież")
            harness.click("Odśwież")
            compose.onNodeWithText("Nowy licznik: 1").assertIsDisplayed()
            assertTrue(harness.named("Odśwież").single().isAccessibilityFocused)
            compose.runOnIdle { enabled = false }
            compose.onNodeWithContentDescription("Odśwież").assertIsNotEnabled()
            compose.waitUntil(5_000) { harness.named("Odśwież").singleOrNull()?.isEnabled == false }
            harness.dump("candidate-disabled")
            val disabled = harness.named("Odśwież").single()
            assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertFalse(disabled.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.onNodeWithText("Nowy licznik: 1").assertIsDisplayed()
            compose.runOnIdle { enabled = true }
            compose.waitUntil(5_000) { harness.named("Odśwież").singleOrNull()?.isEnabled == true }
            harness.click("Odśwież")
            compose.onNodeWithText("Nowy licznik: 2").assertIsDisplayed()
            harness.dump("candidate-reenabled")
        }
    }
}
