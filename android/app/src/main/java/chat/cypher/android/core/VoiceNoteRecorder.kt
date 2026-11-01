package chat.cypher.android.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVoiceNote(
    val file: File,
    val durationMs: Long
)

/**
 * Compact 16 kHz mono AAC (.m4a) voice note recorder for mesh transmission.
 */
class VoiceNoteRecorder(private val context: Context) {
    companion object {
        private const val TAG = "VoiceNoteRecorder"
    }

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAtMs: Long = 0L

    val isRecording: Boolean
        get() = recorder != null

    fun start(): File? {
        stop(canceled = true)
        return try {
            val dir = File(context.filesDir, "voicenotes").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "voice_$stamp.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(20_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outFile = file
            startedAtMs = System.currentTimeMillis()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice note recorder", e)
            stop(canceled = true)
            null
        }
    }

    fun pollAmplitude(): Int {
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun elapsedMs(): Long {
        if (startedAtMs <= 0L) return 0L
        return (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
    }

    fun cancel() {
        stop(canceled = true)
    }

    fun stop(canceled: Boolean = false): RecordedVoiceNote? {
        val duration = elapsedMs()
        val file = outFile
        try {
            recorder?.apply {
                runCatching { stop() }
                runCatching { reset() }
                runCatching { release() }
            }
        } catch (_: Exception) {
        }
        recorder = null
        outFile = null
        startedAtMs = 0L

        if (canceled || file == null || !file.exists() || file.length() < 128L) {
            runCatching { file?.delete() }
            return null
        }
        val probedDuration = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
            retriever.release()
            d
        }.getOrDefault(duration)
        return RecordedVoiceNote(file = file, durationMs = probedDuration.coerceAtLeast(duration))
    }
}
