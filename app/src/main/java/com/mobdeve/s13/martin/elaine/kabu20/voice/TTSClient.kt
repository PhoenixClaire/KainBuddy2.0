package com.mobdeve.s13.martin.elaine.kabu20.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class TTSClient(
    private val context: Context,
    baseUrl: String = "http://192.168.63.85:5001/tts" //IP
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val url = baseUrl
    private var mediaPlayer: MediaPlayer? = null
    private val queue: ArrayDeque<Pair<String, Boolean>> = ArrayDeque()
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Speak a sentence (enqueue).
     * @param text The text to speak.
     * @param isLastSentence True if this is the last sentence of KaBu's reply,
     *                       so after this we return control to STT.
     */
    fun speak(
        text: String,
        isLastSentence: Boolean = false,
        voice: String = "en_US-kathleen-low",
        onError: (String) -> Unit = {},
        onDone: () -> Unit = {},
        onStart: () -> Unit = {}
    ) {
        queue.add(text to isLastSentence)

        if (!isPlaying) {
            playNext(onError, onDone, onStart)
        }
    }

    private fun playNext(
        onError: (String) -> Unit,
        onDone: () -> Unit,
        onStart: () -> Unit
    ) {
        if (queue.isEmpty()) {
            isPlaying = false
            return
        }

        isPlaying = true
        val (text, isLastSentence) = queue.removeFirst()

        fetchAndPlay(text, "en_US-kathleen-low", onError, {
            if (queue.isNotEmpty()) {
                playNext(onError, onDone, onStart)
            } else {
                isPlaying = false
                if (isLastSentence) {
                    onDone()
                }
            }
        }, onStart)

    }

    private fun fetchAndPlay(
        text: String,
        voice: String,
        onError: (String) -> Unit,
        onDone: () -> Unit,
        onStart: () -> Unit
    ) {
        val filename = "tts_${System.currentTimeMillis()}.wav"

        val prefs = context.getSharedPreferences("KaBuPrefs", Context.MODE_PRIVATE)
        val selectedVoice = prefs.getString("KaBu_Voice", "Male") ?: "Male"
        val kabuVoice = if (selectedVoice == "Male") {
            "en_US-ryan-medium"
        } else {
            "en_US-hfc_female-medium"
        }

        val json = JSONObject().apply {
            put("text", text)
            put("voice", kabuVoice)
            put("filename", filename)
        }

        val req = Request.Builder()
            .url(url)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), json.toString()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("TTS network error: ${e.message}")
                onDone()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful || response.body == null) {
                    onError("TTS HTTP error ${response.code}")
                    onDone()
                    return
                }

                val audio = File(context.filesDir, filename)
                response.body!!.byteStream().use { input ->
                    FileOutputStream(audio).use { out -> input.copyTo(out) }
                }

                play(audio, onError, onDone, onStart)
            }
        })
    }

    private fun play(
        file: File,
        onError: (String) -> Unit,
        onDone: () -> Unit,
        onStart: () -> Unit
    ) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.path)

                setOnPreparedListener { mp ->
                    mp.start()
                    onStart()
                }

                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    onDone()
                }

                setOnErrorListener { _, what, extra ->
                    val msg = "TTS mediaPlayer error: what=$what extra=$extra"
                    Log.e("TTS", msg)
                    mediaPlayer?.release()
                    mediaPlayer = null
                    onError(msg)
                    onDone()
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            onError("TTS play error: ${e.message}")
            onDone()
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        queue.clear()
        isPlaying = false
    }
}
