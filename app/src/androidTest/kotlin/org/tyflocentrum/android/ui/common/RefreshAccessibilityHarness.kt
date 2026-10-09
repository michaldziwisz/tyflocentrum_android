package net.tyflopodcast.tyflocentrum.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Aparatura emulatora, nie pomiar fizycznego czytnika. Dowody przetrwają odinstalowanie APK. */
internal class RefreshAccessibilityHarness(
    private val compose: ComposeContentTestRule,
    private val prefix: String
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    private val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var sequence = 0

    private fun nodeJson(node: AccessibilityNodeInfo?): Any {
        if (node == null) return JSONObject.NULL
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().apply {
            put("id", node.toString())
            put("class", node.className?.toString())
            put("text", node.text?.toString())
            put("contentDescription", node.contentDescription?.toString())
            put("clickable", node.isClickable)
            put("enabled", node.isEnabled)
            put("accessibilityFocused", node.isAccessibilityFocused)
            put("inputFocused", node.isFocused)
            put("bounds", rect.toShortString())
            put("childCount", node.childCount)
            put("actions", JSONArray().apply {
                node.actionList.forEach { action -> put(JSONObject().put("id", action.id).put("label", action.label?.toString())) }
            })
            put("children", JSONArray().apply {
                for (i in 0 until node.childCount) put(nodeJson(node.getChild(i)))
            })
        }
    }

    private val evidenceWriter = RefreshEvidenceWriter(
        "/data/local/tmp/tyflo-refresh",
        readCommand = { command ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        },
        writeCommand = { command, bytes ->
            val descriptors = automation.executeShellCommandRw(command)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(bytes) }
                ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
            } finally {
                descriptors.forEach { runCatching { it.close() } }
            }
        },
    )

    fun write(label: String, text: String) {
        require(prefix.matches(Regex("[a-zA-Z0-9_-]+")))
        require(label.matches(Regex("[a-zA-Z0-9_-]+")))
        println(evidenceWriter.write("$prefix-${sequence++}-$label", text))
    }

    fun dump(label: String, includeCompose: Boolean = true) {
        // Zapis stanu systemu zawsze pierwszy, także kiedy Compose nie jest gotowy.
        val state = JSONObject().apply {
            put("stage", label)
            put("pid", Process.myPid())
            put("isEnabled", manager.isEnabled)
            put("isTouchExplorationEnabled", manager.isTouchExplorationEnabled)
            put("serviceFlags", automation.serviceInfo.flags)
            put("focus", nodeJson(automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)))
            put("root", nodeJson(automation.rootInActiveWindow))
        }
        write("$label-ax", state.toString(2))
        if (includeCompose) {
            for (unmerged in listOf(false, true)) {
                val text = runCatching {
                    compose.onAllNodes(isRoot(), useUnmergedTree = unmerged).printToString(maxDepth = 100)
                }.fold({ it }, { "DUMP_ERROR: $it" })
                write("$label-${if (unmerged) "unmerged" else "merged"}", text)
            }
        }
    }

    fun <T> withTouchExploration(block: () -> T): T {
        val original = automation.serviceInfo
        val originalFlags = original.flags
        val originalTouchExploration = manager.isTouchExplorationEnabled
        dump("before-configuration", includeCompose = false)
        try {
            val requested = automation.serviceInfo
            requested.flags = originalFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = requested
            try {
                compose.waitUntil(10_000) { manager.isEnabled && manager.isTouchExplorationEnabled }
            } finally {
                dump("after-configuration", includeCompose = false)
            }
            assertTrue(manager.isEnabled)
            assertTrue(manager.isTouchExplorationEnabled)
            return block()
        } catch (failure: Throwable) {
            dump("failure")
            throw failure
        } finally {
            try {
                automation.setOnAccessibilityEventListener(null)
                original.flags = originalFlags
                automation.serviceInfo = original
                compose.waitUntil(10_000) {
                    manager.isTouchExplorationEnabled == originalTouchExploration
                }
            } finally {
                dump("restored-configuration", includeCompose = false)
            }
        }
    }

    fun named(name: String, substring: Boolean = false): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val description = node.contentDescription?.toString().orEmpty()
            if (if (substring) description.contains(name) else description == name) result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
        return result
    }

    fun focus(name: String, substring: Boolean = false) {
        val target = named(name, substring).single()
        dump("before-focus")
        val alreadyFocused = target.isAccessibilityFocused
        val result = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        write("focus-action", JSONObject().put("name", name).put("alreadyFocused", alreadyFocused).put("result", result).toString())
        try {
            assertTrue("Nowy fokus musi zostać przyjęty", alreadyFocused || result)
            compose.waitUntil(5_000) { named(name, substring).singleOrNull()?.isAccessibilityFocused == true }
            assertEquals("Fokus na właściwym węźle", named(name, substring).single(), automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        } finally {
            dump("after-focus")
        }
    }

    fun click(name: String) {
        val node = named(name).single()
        dump("before-click")
        assertTrue(node.isEnabled)
        assertTrue(node.isClickable)
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        write("click-action", JSONObject().put("name", name).put("accepted", accepted).toString())
        assertTrue("Rzeczywiste ACTION_CLICK", accepted)
    }
}
