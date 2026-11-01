package chat.cypher.android.core

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Real MediaPlayer-backed voice note player with live progress tracking and
 * deterministic file-byte waveform extraction.
 */
object VoiceNotePlayer {
    data class PlaybackState(
        val activePath: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    ) {
        val progressFraction: Float
            get() = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun togglePlayback(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        if (!file.exists()) return

        val current = _state.value
        if (current.activePath == filePath && mediaPlayer != null) {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying) {
                mp.pause()
                progressJob?.cancel()
                _state.value = current.copy(isPlaying = false, positionMs = mp.currentPosition)
            } else {
                mp.start()
                _state.value = current.copy(isPlaying = true)
                startProgressLoop()
            }
            return
        }

        stop()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    progressJob?.cancel()
                    _state.value = PlaybackState(
                        activePath = filePath,
                        isPlaying = false,
                        positionMs = 0,
                        durationMs = duration.coerceAtLeast(0),
                    )
                }
                start()
            }
            mediaPlayer = mp
            _state.value = PlaybackState(
                activePath = filePath,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(0),
            )
            startProgressLoop()
        }.onFailure {
            stop()
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _state.value = PlaybackState()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                val dur = runCatching { mp.duration }.getOrDefault(_state.value.durationMs)
                _state.value = _state.value.copy(
                    isPlaying = playing,
                    positionMs = pos.coerceAtLeast(0),
                    durationMs = dur.coerceAtLeast(0),
                )
                if (!playing) break
                delay(100L)
            }
        }
    }

    /**
     * Computes 24 normalized waveform bar heights (0.18f..1.0f) from the actual audio file bytes,
     * or from the message ID/meta hash when the file has been pruned.
     */
    fun extractWaveformBars(filePath: String?, seedText: String, barCount: Int = 24): List<Float> {
        val file = filePath?.let { File(it) }
        if (file != null && file.exists() && file.length() > 64L) {
            return runCatching {
                val bytes = file.readBytes()
                val headerSkip = (bytes.size / 6).coerceAtLeast(32)
                val usable = (bytes.size - headerSkip).coerceAtLeast(barCount)
                val step = (usable / barCount).coerceAtLeast(1)
                List(barCount) { idx ->
                    val offset = (headerSkip + idx * step).coerceIn(0, bytes.lastIndex)
                    var sum = 0
                    var count = 0
                    val window = step.coerceAtMost(32)
                    for (j in 0 until window) {
                        val bIdx = (offset + j).coerceAtMost(bytes.lastIndex)
                        sum += abs(bytes[bIdx].toInt())
                        count++
                    }
                    val avg = if (count > 0) sum.toFloat() / (count * 128f) else 0.3f
                    avg.coerceIn(0.20f, 1.0f)
                }
            }.getOrElse { fallbackBars(seedText, barCount) }
        }
        return fallbackBars(seedText, barCount)
    }

    private fun fallbackBars(seedText: String, barCount: Int): List<Float> {
        val seed = seedText.ifBlank { "voice" }
        return List(barCount) { idx ->
            val h = abs((seed.hashCode() * 31 + idx * 97) % 100) / 100f
            (0.22f + h * 0.75f).coerceIn(0.22f, 0.97f)
        }
    }
}
