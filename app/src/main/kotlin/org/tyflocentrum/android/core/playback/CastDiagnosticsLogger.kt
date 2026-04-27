package net.tyflopodcast.tyflocentrum.core.playback

import android.util.Log
import net.tyflopodcast.tyflocentrum.BuildConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

class CastDiagnosticsLogger {
    fun log(event: String, details: String) {
        if (!BuildConfig.DEBUG) return
        val line = "[${timestamp()}] $event: $details"
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
        Log.d(TAG, line)
    }

    fun snapshot(): String {
        return synchronized(entries) {
            if (entries.isEmpty()) {
                "Brak zebranych logow Cast."
            } else {
                entries.joinToString(separator = "\n")
            }
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    private fun timestamp(): String {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER)
    }

    private companion object {
        private const val TAG = "TyfloCast"
        private const val MAX_ENTRIES = 500
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    }

    private val entries = ArrayDeque<String>()
}
