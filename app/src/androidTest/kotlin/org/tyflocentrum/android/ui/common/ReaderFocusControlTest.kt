package net.tyflopodcast.tyflocentrum.ui.common

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Kontrolka prawdziwego czytnika. Nie ustawia fokusu przez ACTION_ACCESSIBILITY_FOCUS. */
class ReaderFocusControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun measure(native: Boolean) {
        val harness = RefreshAccessibilityHarness(compose, if(native) "reader-native" else "reader-compose")
        harness.withTouchExploration {
            val clicked = AtomicInteger(0)
            val names = listOf("Początek kontrolki", "Cel przywrócenia fokusu", "Koniec kontrolki")
            compose.setContent {
                MaterialTheme {
                    Column {
                        for (name in names) {
                            if (native) AndroidView(factory = { context ->
                                android.widget.Button(context).apply {
                                    text = name
                                    contentDescription = name
                                    setOnClickListener { clicked.incrementAndGet() }
                                }
                            }, modifier = Modifier.fillMaxWidth())
                            else Button(onClick = { clicked.incrementAndGet() }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                                contentDescription = name
                                role = Role.Button
                                onClick { clicked.incrementAndGet(); true }
                            }) { Text(name) }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val name = names[1]
            compose.waitUntil(5_000) { harness.named(name).size == 1 }
            harness.automation.waitForIdle(700, 8_000)
            assertFalse("Cel nie może mieć fokusu przed próbą", harness.named(name).single().isAccessibilityFocused)
            harness.focusByReader(name)
            assertEquals("Eksploracja nie aktywuje przycisku", 0, clicked.get())
            val activity = compose.activity
            val before = System.identityHashCode(activity)
            assertTrue(harness.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            Thread.sleep(1_500)
            harness.dump("background", includeCompose = false)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(Intent(context, activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            compose.waitUntil(10_000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
            try {
                compose.waitUntil(8_000) { harness.named(name).singleOrNull()?.isAccessibilityFocused == true }
                assertEquals(before, System.identityHashCode(compose.activity))
                assertEquals(0, clicked.get())
            } finally { harness.dump("returned-without-forcing-focus") }
        }
    }

    @Test fun nativeReaderRestoresFocusAfterHome() = measure(true)
    @Test fun composeReaderRestoresFocusAfterHome() = measure(false)
}
