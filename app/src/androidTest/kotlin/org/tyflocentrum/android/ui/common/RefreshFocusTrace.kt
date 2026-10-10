package net.tyflopodcast.tyflocentrum.ui.common

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Ślad zdarzeń, nie zapis mowy. Nie ustawia ani nie przywraca fokusu. */
internal class RefreshFocusTrace(
    private val target: AccessibilityNodeInfo,
    private val resume: Boolean,
) {
    private val startedAt = SystemClock.uptimeMillis()
    private val policy = FocusTracePolicy(resume, startedAt)
    private val events = mutableListOf<JSONObject>()
    private var returningAt: Long? = null

    @Synchronized
    fun markReturn() {
        returningAt = SystemClock.uptimeMillis()
        policy.markReturn(returningAt!!)
    }

    @Synchronized
    fun record(event: AccessibilityEvent) {
        val type = event.eventType
        if (type !in listOf(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_ANNOUNCEMENT,
            )) return
        val source = event.source
        val sameNode: Boolean? = source?.let { it == target }
        val inApp = event.windowId == target.windowId ||
            event.packageName?.toString() == target.packageName?.toString()
        events += JSONObject().put("time", event.eventTime).put("receivedAt", SystemClock.uptimeMillis())
            .put("type", type).put("contentChangeTypes", event.contentChangeTypes)
            .put("windowId", event.windowId).put("package", event.packageName?.toString())
            .put("inApp", inApp).put("target", sameNode ?: JSONObject.NULL)
            .put("name", source?.contentDescription?.toString()).put("text", event.text.toString())
        if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) {
            policy.record(event.eventTime, type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, inApp, sameNode)
        }
    }

    @Synchronized
    fun result(finalNode: AccessibilityNodeInfo?, completed: Boolean): JSONObject {
        val finalFocus = finalNode == target && finalNode?.isAccessibilityFocused == true
        val errors = policy.failures(finalFocus).toMutableList()
        if (!completed) errors += "Przerwana obserwacja"
        return JSONObject().put("startedAt", startedAt).put("returningAt", returningAt ?: JSONObject.NULL)
            .put("endedAt", SystemClock.uptimeMillis()).put("resume", resume)
            .put("name", target.contentDescription?.toString()).put("windowId", target.windowId)
            .put("finalFocus", finalFocus).put("completed", completed)
            .put("failures", JSONArray(errors)).put("events", JSONArray(events))
    }
}
