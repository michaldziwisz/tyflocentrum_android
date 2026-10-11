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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RefreshAccessibilityControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeControlProvesFocusCallbackAndExportedText() {
        val harness = RefreshAccessibilityHarness(compose, "apparatus-native")
        harness.withTouchExploration {
            var count by mutableIntStateOf(0)
            lateinit var nativeButton: NativeButton
            compose.setContent {
                MaterialTheme {
                    AndroidView(factory = { context ->
                        NativeButton(context).apply {
                            nativeButton = this
                            contentDescription = "Natywna kontrolka"
                            setOnClickListener { count++ }
                        }
                    }, update = { it.text = "Natywny licznik: $count" })
                }
            }
            compose.waitForIdle()
            val name = "Natywna kontrolka"
            fun sample(label: String): String {
                val state = compose.runOnIdle {
                    org.json.JSONObject().apply {
                        put("counter", count)
                        put("rawText", nativeButton.text.toString())
                        put("transformation", nativeButton.transformationMethod?.javaClass?.name)
                        put("displayedText", nativeButton.transformationMethod
                            ?.getTransformation(nativeButton.text, nativeButton)?.toString()
                            ?: nativeButton.text.toString())
                    }
                }
                harness.write(label, state.toString(2))
                harness.dump(label)
                return state.getString("displayedText")
            }
            compose.waitUntil(5_000) { harness.named(name).size == 1 }
            sample("native-before")
            compose.runOnIdle { assertEquals(0, count) }
            harness.focus(name)
            harness.focus(name)
            val before = harness.named(name).single()
            harness.click(name)
            try {
                compose.waitUntil(5_000) { count == 1 }
            } finally { sample("native-after-callback") }
            compose.runOnIdle {
                assertEquals(1, count)
                assertEquals("Natywny licznik: 1", nativeButton.text.toString())
            }
            // Tekst AX odpowiada zmierzonej transformacji, nie założeniu o motywie.
            val expected = sample("native-expected-text")
            try {
                compose.waitUntil(5_000) { harness.named(name).single().text?.toString() == expected }
            } finally { sample("native-after-text") }
            assertEquals(before, harness.named(name).single())
            assertTrue(harness.named(name).single().isAccessibilityFocused)
        }
    }

    @Test fun composeControlProvesFocusAndRealClick() {
        val harness = RefreshAccessibilityHarness(compose, "apparatus-compose")
        harness.withTouchExploration {
            var count by mutableIntStateOf(0)
            compose.setContent {
                MaterialTheme {
                    Column {
                        val increment: () -> Unit = { count++ }
                        Button(onClick = increment,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "Kontrolka Compose"
                                role = Role.Button
                                // Ta sama akcja dla dotyku i prawdziwego ACTION_CLICK przez AX.
                                onClick { increment(); true }
                            }) {
                            Text("Kontrolka Compose")
                        }
                        // Licznik jest niezależnym skutkiem kliknięcia, a nie dzieckiem przycisku.
                        Text("Compose licznik: $count")
                    }
                }
            }
            compose.waitForIdle()
            val name = "Kontrolka Compose"
            compose.waitUntil(5_000) { harness.named(name).size == 1 }
            compose.onNodeWithContentDescription(name).assertHasClickAction().assertIsEnabled()
            compose.onAllNodes(hasContentDescription(name), useUnmergedTree = true).assertCountEquals(1)
            assertEquals(0, harness.named(name).single().childCount)
            compose.onNodeWithText("Compose licznik: 0").assertIsDisplayed()
            harness.focus(name)
            harness.focus(name)
            val before = harness.named(name).single()
            harness.click(name)
            try {
                compose.onNodeWithText("Compose licznik: 1", useUnmergedTree = true).assertIsDisplayed()
                compose.runOnIdle { assertEquals(1, count) }
            } finally { harness.dump("compose-after-click") }
            assertEquals(before, harness.named(name).single())
            assertTrue(harness.named(name).single().isAccessibilityFocused)
        }
    }

    @Test fun evidenceRoundTripPreservesLargeUtf8() {
        val harness = RefreshAccessibilityHarness(compose, "transport-control")
        harness.write("large-utf8", "Odśwież: zażółć gęślą jaźń.\n".repeat(12000))
        harness.write("empty", "")
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
