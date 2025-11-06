package com.mobdeve.s13.martin.elaine.kabu20.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * STTClient (SenseVoice edition)
 * - start(lang): records mic until silence, writes a WAV, sends to SenseVoice server, then calls onFinal(transcript, wavFile)
 * - stop(): aborts capture and any in-flight work
 *
 * Callbacks:
 *  onPartial(text)   -> optional (we don't stream partials from SenseVoice HTTP; you can plug WS later)
 *  onFinal(text, file) -> final transcript + SAME wav file for SER
 *  onError(msg)
 *  fallbackTTS(text, after) -> (unchanged from your interface)
 */
class STTClient(
    private val activity: Activity,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String, File?) -> Unit,
    private val onError: (String) -> Unit,
    private val fallbackTTS: ((String, () -> Unit) -> Unit)? = null,
    private val senseVoiceUrl: String = "http://10.0.0.106:8008/asr",   // <-- adjust to your server

    private val shouldListen: () -> Boolean = { true },  // e.g., mic toggle, call state, etc.
    private val shouldSpeak: () -> Boolean = { true },
) {

    // ---- Mic / WAV config ----
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val source = MediaRecorder.AudioSource.VOICE_RECOGNITION // reduces echo/AGC conflicts

    private val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val bufferSize = maxOf(minBuffer, 2048)

    @Volatile private var listening = false
    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var audioFile: File? = null

    // VAD params (tune to your room/mic)
    private val silenceThreshold = 500        // average absolute PCM sample (raise for noisy rooms)
    private val minSpeechMs = 600L            // require at least this much voiced speech
    private val tailSilenceMs = 1200L         // stop after this long silence following speech
    private val ignoreStartupNoiseMs = 300L   // grace at start before VAD kicks

    // HTTP
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(lang: String = "en-US") {
        if (!shouldListen()) {
            Log.d("STT(SV)", "start() ignored: shouldListen=false")
            return
        }
        if (listening) return

        // Permission guard
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            Log.w("STT(SV)", "No RECORD_AUDIO permission yet.")
            return
        }


        // Prepare file (we’ll finalize WAV header on stop)
        audioFile = File(activity.cacheDir, "utterance_${System.currentTimeMillis()}.wav")

        // Init recorder
        val ar = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            onError("Failed to initialize microphone.")
            return
        }
        audioRecord = ar

        listening = true
        isRecording = true
        ar.startRecording()
        Log.d("STT(SV)", "Recording started…")

        // Record until silence in a worker thread
        recordingThread = Thread {
            var totalBytes = 0L
            val startMs = System.currentTimeMillis()
            var firstVoiceMs: Long? = null
            var lastVoiceMs = startMs

            try {
                RandomAccessFile(audioFile, "rw").use { raf ->
                    // Write placeholder WAV header; we’ll patch lengths on finish
                    writeWavHeader(raf, sampleRate, channels = 1, bits = 16, dataLen = 0)

                    val buf = ByteArray(bufferSize)
                    while (isRecording) {
                        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                        if (read > 0) {
                            // Append PCM data
                            raf.write(buf, 0, read)
                            totalBytes += read

                            // VAD (avg absolute sample)
                            var sum = 0L
                            var i = 0
                            while (i + 1 < read) {
                                val lo = buf[i].toInt() and 0xFF
                                val hi = buf[i + 1].toInt()
                                val s = (hi shl 8) or lo
                                sum += abs(s)
                                i += 2
                            }
                            val avg = if (read > 0) sum / (read / 2) else 0

                            val now = System.currentTimeMillis()
                            if (now - startMs > ignoreStartupNoiseMs) {
                                if (avg > silenceThreshold) {
                                    if (firstVoiceMs == null) firstVoiceMs = now
                                    lastVoiceMs = now
                                }
                            }

                            // Stop rule: had speech, then tail silence exceeded, and min speech met
                            val hasSpeech = firstVoiceMs != null
                            val speechDur = if (hasSpeech) (lastVoiceMs - (firstVoiceMs ?: now)) else 0L
                            val tailSilence = now - lastVoiceMs

                            if (hasSpeech && speechDur >= minSpeechMs && tailSilence >= tailSilenceMs) {
                                break
                            }
                        }
                    }

                    // finalize
                    isRecording = false
                    try {
                        audioRecord?.stop()
                    } catch (_: Exception) {}
                    try {
                        audioRecord?.release()
                    } catch (_: Exception) {}
                    audioRecord = null

                    // Patch WAV header sizes
                    finalizeWavHeader(raf, totalBytes, sampleRate, 1, 16)
                }
            } catch (e: Exception) {
                Log.e("STT(SV)", "Recording error: ${e.message}", e)
                mainHandler.post { onError("Recording error: ${e.message}") }
                cleanup()
                return@Thread
            }

            // If we got here, we have a WAV file. Send to SenseVoice.
            val wav = audioFile
            if (!listening || wav == null || !wav.exists()) {
                cleanup()
                return@Thread
            }
            sendToSenseVoice(wav, lang)
        }.also { it.start() }
    }

    fun stop() {
        listening = false
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            recordingThread?.join(400)
        } catch (_: Exception) {}
        recordingThread = null
    }

    // ---- SenseVoice HTTP ----
    private fun sendToSenseVoice(wavFile: File, lang: String) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            // If your server supports language hint, include it:
            // .addFormDataPart("language", lang)
            .build()

        val req = Request.Builder()
            .url(senseVoiceUrl)
            .post(body)
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("STT(SV)", "SenseVoice request failed: ${e.message}")
                mainHandler.post {
                    onError("STT network error: ${e.message}")
//                    fallbackTTS?.invoke("Sorry, I didn’t catch that. Can you repeat that for me?") {
//                        start(lang)
//                    }
                    if (shouldSpeak() && shouldListen()) {
                        fallbackTTS?.invoke("Sorry, I didn’t catch that. Can you repeat that for me?") {
                            // Only restart listening if still allowed:
                            if (shouldListen()) start(lang)
                        }
                    } else {
                        Log.d("STT(SV)", "Suppressing fallback TTS (shouldSpeak/shouldListen=false)")
                    }
                }
                cleanup()
            }

            override fun onResponse(call: Call, response: Response) {
                val txt = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    ""
                }

                if (!response.isSuccessful || txt.isBlank()) {
                    Log.e("STT(SV)", "Bad response: code=${response.code}, body=$txt")
                    mainHandler.post {
                        onError("STT server error (${response.code})")
//                        fallbackTTS?.invoke("Sorry, I didn’t catch that. Can you repeat that for me?") {
//                            start(lang)
//                        }
                        if (shouldSpeak() && shouldListen()) {
                            fallbackTTS?.invoke("Sorry, I didn’t catch that. Can you repeat that for me?") {
                                if (shouldListen()) start(lang)
                            }
                        } else {
                            Log.d("STT(SV)", "Suppressing fallback TTS (shouldSpeak/shouldListen=false)")
                        }
                    }
                    cleanup()
                    return
                }

                try {
                    val json = JSONObject(txt)
                    val text = json.optString("text", "").trim()
                    Log.d("STT(SV)", "ASR: $text")
                    mainHandler.post {
                        // deliver SAME wav file for SER alignment
                        onFinal(text, audioFile)
                    }
                } catch (e: Exception) {
                    Log.e("STT(SV)", "Parse error: ${e.message} body=$txt")
                    mainHandler.post { onError("STT parse error") }
                } finally {
                    cleanup(keepFile = true) // keep WAV for SER
                }
            }
        })
    }

    private fun cleanup(keepFile: Boolean = true) {
        listening = false
        // only delete if you don’t want SER; default keep
        if (!keepFile) {
            try { audioFile?.delete() } catch (_: Exception) {}
        }
    }

    // ---- WAV header helpers ----
    private fun writeWavHeader(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        bits: Int,
        dataLen: Int
    ) {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE(36 + dataLen)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)               // PCM
        raf.writeShortLE(1)              // audio format = PCM
        raf.writeShortLE(channels)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(bits)
        raf.writeBytes("data")
        raf.writeIntLE(dataLen)
    }

    private fun finalizeWavHeader(
        raf: RandomAccessFile,
        dataLen: Long,
        sampleRate: Int,
        channels: Int,
        bits: Int
    ) {
        val len = dataLen.toInt()
        writeWavHeader(raf, sampleRate, channels, bits, len)
        raf.seek(raf.length())
    }

    // little-endian writers
    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        ))
    }
    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte()
        ))
    }
}
