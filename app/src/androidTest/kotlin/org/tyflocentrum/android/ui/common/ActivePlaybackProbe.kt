package net.tyflopodcast.tyflocentrum.ui.common

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.platform.app.InstrumentationRegistry
import net.tyflopodcast.tyflocentrum.core.playback.PlayerController
import net.tyflopodcast.tyflocentrum.core.model.PlayerRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wyłącznie obserwacja prawdziwego Media3. Nie podmienia odtwarzacza ani audio sink. */
internal class ActivePlaybackProbe(private val controller: PlayerController) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = instrumentation.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val local = PlayerController::class.java.getDeclaredField("localPlayer").apply { isAccessible=true }.get(controller) as ExoPlayer
    private val player = controller.mediaSession.player
    private val samples = mutableListOf<JSONObject>()
    private val events = mutableListOf<JSONObject>()
    private var started = false
    private var advancing = 0
    private var trackReady = 0
    private var released = 0
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) = event("playing",value.toString())
        override fun onPlaybackStateChanged(state: Int) = event("state",state.toString())
        override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) = event("intent","$value:$reason")
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = event("suppression",reason.toString())
        override fun onPlayerError(error: PlaybackException) = event("error",error.toString())
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = event("item","${item?.mediaId}:$reason")
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) = event("discontinuity",reason.toString())
    }
    private val analytics = object : AnalyticsListener {
        override fun onAudioPositionAdvancing(time: AnalyticsListener.EventTime, start: Long) { advancing++; event("audioAdvancing",start.toString()) }
        override fun onAudioTrackInitialized(time: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) { trackReady++; event("trackInitialized",config.toString()) }
        override fun onAudioTrackReleased(time: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) { event("trackReleased",config.toString()) }
        override fun onPlayerReleased(time: AnalyticsListener.EventTime) { released++; event("released", "true") }
        override fun onAudioSinkError(time: AnalyticsListener.EventTime, error: Exception) = event("audioError",error.toString())
        override fun onAudioUnderrun(time: AnalyticsListener.EventTime, size: Int, sizeMs: Long, since: Long) = event("underrun","$size:$sizeMs:$since")
    }
    private fun event(kind: String, value: String) { events += JSONObject().put("ms",SystemClock.elapsedRealtime()).put("kind",kind).put("value",value) }
    private fun sample() {
        val observed=(instrumentation.targetContext.applicationContext as net.tyflopodcast.tyflocentrum.TyflocentrumApplication).appContainer.playerController
        val observedLocal=PlayerController::class.java.getDeclaredField("localPlayer").apply { isAccessible=true }.get(observed)
        val counters = local.audioDecoderCounters
        counters?.ensureUpdated()
        samples += JSONObject().put("ms",SystemClock.elapsedRealtime()).put("position",local.currentPosition)
            .put("playing",local.isPlaying).put("state",local.playbackState).put("intent",local.playWhenReady)
            .put("suppressionReason",local.playbackSuppressionReason)
            .put("a11yAudioActive",audioManager.activePlaybackConfigurations.any {
                it.audioAttributes.usage == android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            })
            .put("bufferedMs",local.totalBufferedDuration).put("duration",local.duration).put("loading",local.isLoading)
            .put("controller",System.identityHashCode(observed)).put("player",System.identityHashCode(observed.mediaSession.player))
            .put("local",System.identityHashCode(observedLocal)).put("item",local.currentMediaItem?.mediaId)
            .put("mediaItem",System.identityHashCode(local.currentMediaItem))
            .put("rendered",counters?.renderedOutputBufferCount ?: -1).put("skipped",counters?.skippedOutputBufferCount ?: -1)
            .put("advancing",advancing).put("trackReady",trackReady).put("released",released)
    }
    private val tick = object : Runnable { override fun run() { if(started) { sample(); main.postDelayed(this,50) } } }
    fun start() {
        val file = File(instrumentation.targetContext.filesDir,"refresh-test-tone.wav")
        // 180 sekund PCM 16 kHz/mono, sinus 440 Hz; żaden publiczny serwer audio.
        if (!file.exists()) {
            val size=180*16000*2
            val bytes=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(size+36).put("WAVEfmt ".toByteArray()).putInt(16)
            bytes.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(size)
            repeat(size/2) { bytes.putShort((kotlin.math.sin(it*2.0*Math.PI*440/16000)*6000).toInt().toShort()) }
            file.writeBytes(bytes.array())
        }
        instrumentation.runOnMainSync {
            local.addListener(listener); local.addAnalyticsListener(analytics)
            started=true; tick.run()
            controller.play(PlayerRequest(url=file.toURI().toString()+"#"+SystemClock.elapsedRealtime(),title="Lokalny sygnał testowy",isLive=false,initialSeekMs=0L))
        }
    }
    fun ready(): Boolean { var ok=false; instrumentation.runOnMainSync { ok=local.isPlaying && local.currentPosition>750 && local.totalBufferedDuration>=20_000 && advancing>0 && trackReady>0 }; return ok }
    fun settled(): Boolean {
        var clean = false
        instrumentation.runOnMainSync {
            sample()
            val stable = samples.asReversed().takeWhile {
                it.getBoolean("playing") && it.getInt("state") == Player.STATE_READY &&
                    it.getInt("suppressionReason") == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !it.getBoolean("a11yAudioActive")
            }
            clean = stable.size > 1 && stable.first().getLong("ms") - stable.last().getLong("ms") >= 500
        }
        return clean
    }
    data class Window(val sample: Int, val event: Int)
    private fun isActive(): Boolean {
        return local.isPlaying && local.playbackState == Player.STATE_READY &&
            local.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }
    private fun openWindow(): Window {
        sample()
        assertTrue("Początek okna wymaga aktywnego odtwarzania bez supresji", isActive())
        event("window", "sample=${samples.lastIndex}")
        return Window(samples.lastIndex,events.size)
    }
    fun begin(): Window { var w:Window?=null; instrumentation.runOnMainSync {
        w=openWindow()
    }; return w!! }
    private fun beginWhenActive(timeoutMs: Long = 30_000): Window {
        require(timeoutMs > 0)
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            var window: Window? = null
            instrumentation.runOnMainSync {
                // Sprawdzenie i otwarcie w jednym przebiegu kolejki głównej.
                if (SystemClock.elapsedRealtime() - startedAt < timeoutMs && isActive()) {
                    window = openWindow()
                }
            }
            window?.let { return it }
            assertTrue("Początek okna wymaga aktywnego odtwarzania bez supresji",
                SystemClock.elapsedRealtime() - startedAt < timeoutMs)
            Thread.sleep(100)
        }
    }
    fun failures(w: Window): List<String> {
        val bad=mutableListOf<String>()
        instrumentation.runOnMainSync {
            sample()
            val s=samples.drop(w.sample); val e=events.drop(w.event)
            if(s.size<3 || s.last().getLong("position")-s.first().getLong("position")<250) bad+="Pozycja audio nie postępuje"
            val externalPause = InstrumentationRegistry.getArguments().getString("physicalReader") == "true" &&
                AccessibilityAudioPause.isExpected(s.map {
                    AccessibilityAudioPause.Sample(it.getBoolean("playing"), it.getInt("state") == Player.STATE_READY,
                        it.getBoolean("intent"), it.getInt("suppressionReason") == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
                        it.getBoolean("a11yAudioActive"))
                })
            event("windowClassification", if (externalPause) "a11y-pause-resumed" else "strict")
            if(s.any { !it.getBoolean("playing") || it.getInt("state")!=Player.STATE_READY } && !externalPause) bad+="Przerwane odtwarzanie"
            for(key in listOf("controller","player","local","item","mediaItem","released","trackReady")) if(s.map { it.get(key) }.distinct().size!=1) bad+="Zmiana $key"
            if(s.zipWithNext().any { (a,b) -> b.getLong("position")<a.getLong("position") }) bad+="Cofnięcie pozycji"
            if(s.last().getInt("rendered")<=s.first().getInt("rendered")) bad+="Brak nowych buforów audio"
            if(e.any {
                val kind = it.getString("kind")
                val expectedFocusEvent = externalPause && (kind == "playing" || (kind == "suppression" && it.getString("value") in listOf("0", "1")))
                !expectedFocusEvent && kind in listOf("playing","suppression","state","intent","error","item","discontinuity","released","trackReleased","trackInitialized","audioError","underrun")
            }) bad+="Nowe zdarzenia zakłócające: $e"
        }
        return bad
    }
    fun negativeStop(): List<String> {
        val w=beginWhenActive()
        instrumentation.runOnMainSync { player.stop() }
        Thread.sleep(350)
        return failures(w)
    }
    fun dump(): String { var result=""; instrumentation.runOnMainSync { result=JSONObject().put("samples",JSONArray(samples)).put("events",JSONArray(events)).toString(2) }; return result }
    fun close() { instrumentation.runOnMainSync { started=false; main.removeCallbacks(tick); local.removeListener(listener); local.removeAnalyticsListener(analytics); controller.pause() } }
}
