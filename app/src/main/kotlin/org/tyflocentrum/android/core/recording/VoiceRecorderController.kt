package net.tyflopodcast.tyflocentrum.core.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.tyflopodcast.tyflocentrum.core.playback.PlayerController

enum class RecorderState {
    IDLE,
    RECORDING,
    RECORDED,
    PLAYING_PREVIEW
}

data class VoiceRecorderUiState(
    val state: RecorderState = RecorderState.IDLE,
    val isProcessing: Boolean = false,
    val elapsedMs: Long = 0,
    val recordedDurationMs: Int = 0,
    val canSend: Boolean = false,
    val errorMessage: String? = null
)

class VoiceRecorderController(
    private val context: Context,
    private val playerController: PlayerController
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(VoiceRecorderUiState())
    val uiState: StateFlow<VoiceRecorderUiState> = _uiState.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var previewPlayer: MediaPlayer? = null
    private var timerJob: Job? = null
    private var activeSegmentFile: File? = null
    private var recordedFile: File? = null
    private var appendBaseFile: File? = null
    private var recordingOffsetMs: Long = 0
    private var recordingStartedAtElapsedRealtime: Long = 0
    private var maxDurationMs: Long = 20 * 60 * 1000L

    val recordedFileOrNull: File?
        get() = recordedFile

    fun startRecording(maxDurationMs: Long = 20 * 60 * 1000L): Boolean {
        if (_uiState.value.state == RecorderState.RECORDING || _uiState.value.isProcessing) return false
        if (!hasMicrophonePermission()) {
            showError("Brak dostępu do mikrofonu. Włącz uprawnienia w ustawieniach systemu.")
            return false
        }

        this.maxDurationMs = maxDurationMs
        stopPreviewIfNeeded()
        playerController.pause()

        val shouldAppend = recordedFile != null && _uiState.value.state in setOf(RecorderState.RECORDED, RecorderState.PLAYING_PREVIEW)
        val baseDurationMs = if (shouldAppend) _uiState.value.recordedDurationMs.toLong() else 0L
        val remainingMs = maxDurationMs - baseDurationMs
        if (remainingMs <= 250L) {
            showError("Osiągnięto limit długości nagrania.")
            return false
        }

        if (!shouldAppend) {
            cleanupFile(recordedFile)
            recordedFile = null
            updateState(
                _uiState.value.copy(
                    state = RecorderState.IDLE,
                    elapsedMs = 0,
                    recordedDurationMs = 0,
                    canSend = false
                )
            )
        }

        appendBaseFile = if (shouldAppend) recordedFile else null
        activeSegmentFile = File(appContext.cacheDir, "voice-segment-${System.currentTimeMillis()}.m4a")
        recordingOffsetMs = baseDurationMs
        recordingStartedAtElapsedRealtime = SystemClock.elapsedRealtime()
        var didStartRecording = false

        runCatching {
            val currentRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(160_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(activeSegmentFile!!.absolutePath)
                setMaxDuration(remainingMs.toInt())
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording()
                    }
                }
                prepare()
                start()
            }
            recorder = currentRecorder
            timerJob?.cancel()
            timerJob = scope.launch {
                while (true) {
                    delay(250)
                    val elapsed = recordingOffsetMs + (SystemClock.elapsedRealtime() - recordingStartedAtElapsedRealtime)
                    updateState(
                        _uiState.value.copy(
                            state = RecorderState.RECORDING,
                            elapsedMs = elapsed
                        )
                    )
                }
            }
            updateState(
                _uiState.value.copy(
                    state = RecorderState.RECORDING,
                    elapsedMs = baseDurationMs,
                    errorMessage = null
                )
            )
            didStartRecording = true
        }.onFailure {
            cleanupFile(activeSegmentFile)
            activeSegmentFile = null
            appendBaseFile = null
            recordingOffsetMs = 0
            showError("Nie udało się rozpocząć nagrywania.")
        }
        return didStartRecording
    }

    fun stopRecording(): Boolean {
        if (_uiState.value.state != RecorderState.RECORDING) return false

        timerJob?.cancel()
        timerJob = null

        val segmentFile = activeSegmentFile
        val baseFile = appendBaseFile
        activeSegmentFile = null
        appendBaseFile = null

        val finalElapsed = recordingOffsetMs + (SystemClock.elapsedRealtime() - recordingStartedAtElapsedRealtime)
        recordingOffsetMs = 0

        runCatching {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null

        val usableSegment = segmentFile?.takeIf { it.exists() && it.length() > 0 }
        if (usableSegment == null) {
            updateState(
                _uiState.value.copy(
                    state = if (recordedFile != null) RecorderState.RECORDED else RecorderState.IDLE,
                    elapsedMs = _uiState.value.recordedDurationMs.toLong()
                )
            )
            return true
        }

        if (baseFile != null && baseFile.exists()) {
            mergeSegments(baseFile, usableSegment)
            return true
        }

        recordedFile = usableSegment
        val durationMs = extractDurationMs(usableSegment).takeIf { it > 0 } ?: finalElapsed.toInt()
        updateState(
            _uiState.value.copy(
                state = RecorderState.RECORDED,
                elapsedMs = durationMs.toLong(),
                recordedDurationMs = durationMs,
                canSend = true,
                errorMessage = null
            )
        )
        return true
    }

    fun togglePreview() {
        when (_uiState.value.state) {
            RecorderState.PLAYING_PREVIEW -> stopPreviewIfNeeded()
            RecorderState.RECORDED -> startPreview()
            else -> Unit
        }
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        stopPreviewIfNeeded()
        cleanupFile(activeSegmentFile)
        cleanupFile(recordedFile)
        cleanupFile(appendBaseFile)
        activeSegmentFile = null
        recordedFile = null
        appendBaseFile = null
        recordingOffsetMs = 0
        updateState(VoiceRecorderUiState())
    }

    fun clearError() {
        updateState(_uiState.value.copy(errorMessage = null))
    }

    private fun startPreview() {
        val file = recordedFile ?: return
        stopPreviewIfNeeded()
        runCatching {
            previewPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPreviewIfNeeded()
                }
                start()
            }
            updateState(_uiState.value.copy(state = RecorderState.PLAYING_PREVIEW))
        }.onFailure {
            showError("Nie udało się odtworzyć nagrania.")
        }
    }

    private fun stopPreviewIfNeeded() {
        previewPlayer?.runCatching {
            stop()
            release()
        }
        previewPlayer = null
        if (recordedFile != null && _uiState.value.state == RecorderState.PLAYING_PREVIEW) {
            updateState(_uiState.value.copy(state = RecorderState.RECORDED))
        }
    }

    @UnstableApi
    private fun mergeSegments(baseFile: File, segmentFile: File) {
        updateState(_uiState.value.copy(isProcessing = true, state = RecorderState.RECORDED))
        val output = File(appContext.cacheDir, "voice-merged-${System.currentTimeMillis()}.m4a")
        val transformer = Transformer.Builder(appContext)
            .setAudioMimeType("audio/mp4a-latm")
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        recordedFile = output
                        cleanupFile(baseFile)
                        cleanupFile(segmentFile)
                        val durationMs = extractDurationMs(output)
                        updateState(
                            _uiState.value.copy(
                                state = RecorderState.RECORDED,
                                isProcessing = false,
                                elapsedMs = durationMs.toLong(),
                                recordedDurationMs = durationMs,
                                canSend = output.exists() && output.length() > 0,
                                errorMessage = null
                            )
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        cleanupFile(segmentFile)
                        cleanupFile(output)
                        updateState(
                            _uiState.value.copy(
                                isProcessing = false,
                                state = if (recordedFile != null) RecorderState.RECORDED else RecorderState.IDLE
                            )
                        )
                        showError("Nie udało się dograć nagrania.")
                    }
                }
            )
            .build()

        val items = listOf(baseFile, segmentFile).map { file ->
            EditedMediaItem.Builder(MediaItem.fromUri(file.toUri()))
                .setRemoveVideo(true)
                .build()
        }
        val sequence = EditedMediaItemSequence.withAudioFrom(items)
        val composition = Composition.Builder(sequence).build()
        transformer.start(composition, output.absolutePath)
    }

    private fun extractDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        }.getOrDefault(0).also {
            runCatching { retriever.release() }
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun showError(message: String) {
        updateState(_uiState.value.copy(errorMessage = message))
    }

    private fun cleanupFile(file: File?) {
        if (file != null && file.exists()) {
            runCatching { file.delete() }
        }
    }

    private fun updateState(newState: VoiceRecorderUiState) {
        _uiState.value = newState.copy(
            canSend = newState.recordedDurationMs > 0 && recordedFile?.exists() == true && !newState.isProcessing
        )
    }
}
