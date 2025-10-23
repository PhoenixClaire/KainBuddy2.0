package com.mobdeve.s13.martin.elaine.kabu20

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.mobdeve.s13.martin.elaine.kabu20.databinding.ActivityChatbotBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ChatbotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatbotBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Increase connection timeout
        .readTimeout(120, TimeUnit.SECONDS)   // Increase read timeout
        .writeTimeout(120, TimeUnit.SECONDS)  // Increase write timeout
        .build()
    private val messageHistory = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatbotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val KabuGreetings = intent.getStringExtra("Greetings")
        binding.textView.text = "KaBu: ${KabuGreetings}"
        KabuGreetings?.let { speakWithTTS(it) }

//        val historyString = intent.getStringExtra("MessageHistory")
//        if (historyString != null) {
//            messageHistory.put(JSONArray(historyString))
//        }

        val systemPrompt = JSONObject().apply{
            put("role", "system")
            put("content", "IDENTITY: You are KaBu — a warm, food-loving eating companion. Your goal is to keep the user company before, during, and after meals and help them enjoy their food." +
                    "ASSUMPTION: You don't know who are you talking to so always greet them and ask for their name first." +
                    "Never show internal thoughts, reasoning steps, emojis, or markdown. " +
                    "TOPIC PRIORITY 1: Talk about food, cravings, and comfort." +
                    "TOPIC SELECTION: Pre-meal: If they haven't eaten yet, help them decide. Suggest ideas, ask what they are craving, or talk about go-to meals. " +
                    "TOPIC SELECTION: During meal: if they are eating, ask what it is and how it tastes. Ask questions about the food, or talk about something casual. Respond enthusiastically and ask casual follow-ups (e.g., their day, funny thoughts, simple check-ins). " +
                    "TOPIC SELECTION: Post-meal: If they have finished, ask if it was satisfying. Ask if they'll have dessert or something else. If yes, return to Pre-meal." +
                    "Loop: keep talking unless the user clearly says they're done. Responses should feel natural, warm, and human - no assistant-like phrasing." +
                    "TRAIT #1: You speak naturally and directly, like a caring friend. Avoid overly formal or assistant-like language." +
                    "TRAIT #2: Your maximum dialogue or reply is 1-2 sentences to feel conversational and if you're gonna ask, limit each reply to 1 question only." +
                    "TRAIT #3: Observe the user's emotional state and respond empathetically. If they seem down, offer comforting food suggestions or uplifting comments. If they seem excited, match their energy and enthusiasm." +
                    "TRAIT #4: Always check on user's eating status and steer the conversation back to food and meals." +
                    "REMEMBER: If the topic is getting inappropriate (e.g. violence, harassment, and the like), steer it back smoothly to food and meals." +
                    "REMEMBER: You don't always need to ask questions or suggest. Sometimes just make friendly comments or supportive statements is enough." +
                    "REMEMBER: After having maybe 2-3 exchange or conversation not related to food, always check the eating status of the user and steer the conversation back to food and meals." +
                    "REMEMBER: Be cohesive. Try to refer to previous parts of the conversation naturally."
            )
        }
        messageHistory.put(systemPrompt)
        sendToQwen()
        binding.sendbutton.setOnClickListener {
            val userText = binding.editTextText.text.toString().trim()
            
            if(userText.isNotEmpty()){
                addMessage("You: $userText")
                
                //add user message to history
                messageHistory.put(JSONObject().apply { 
                    put("role", "user")
                    put("content", userText)
                })
                
                binding.editTextText.text.clear()
                sendToQwen()
            }
            
        }

        binding.BackBtn.setOnClickListener {
            startActivity(Intent(this, MenuActivity::class.java))
            finish()
        }
        
        //initial greeting
//        addMessage("KaBu: Hi there! What is your name?")
    }

    fun Buffer.readStringLine(): String? {
        val index = indexOf('\n'.toByte())
        return if (index == -1L) null else readString(index + 1, Charsets.UTF_8)
    }

    private fun addText(){
        val messageLayout = findViewById<LinearLayout>(R.id.linearLayout_Messages)
        val inputField = findViewById<EditText>(R.id.editTextText)

        val userInput = inputField.text.toString().trim()

        if (userInput.isNotEmpty()) {
            val newTextView = TextView(this)
            newTextView.text = "User: ${userInput}"
            newTextView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            newTextView.setPadding(10, 10, 10, 10)
            newTextView.textSize = 16f
            newTextView.setTextColor(Color.BLACK)
            messageLayout.addView(newTextView)
            inputField.text.clear()
        }
    }
    
    private fun addMessage(message: String){
        val messageLayout = binding.linearLayoutMessages
        val textView = TextView(this).apply { 
            text = message
            textSize = 16f
            setPadding(10,10,10,10)
            setTextColor(resources.getColor(android.R.color.black))
        }
        messageLayout.addView(textView)
    }
    
    private fun sendToQwen(){
        val jsonBody = JSONObject().apply { 
            put("model", "qwen2.5")
            put("messages", messageHistory)
            put("temperature", 0.7)
            put("repeat_penalty", 1.2)
            put("num_predict", 100)
        }
        
        val request = Request.Builder()
            .url("http://172.20.10.3:11434/api/chat") //replace with server IP
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString()))
            .build()

        client.newCall(request).enqueue(object:Callback{
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread{
                    addMessage("KaBu: Oops! Something went wrong.")
                    addMessage("Debug: ${e.message}" )
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val source = response.body?.source()
                source?.request(Long.MAX_VALUE)
                val buffer = source?.buffer

                val fullReply = StringBuilder()

                while (true){
                    val line = buffer?.readStringLine() ?: break

                    if(line.isNotBlank()){
                        try{
                            val obj = JSONObject(line)
                            val chunk = obj.optJSONObject("message")?.optString("content", "")
                            if(!chunk.isNullOrEmpty()){
                                fullReply.append(chunk)
                                runOnUiThread{
                                    binding.textView.text = "KaBu: ${fullReply}"
                                }
                            }
                        } catch (_:  Exception){

                        }
                    }
                }

                val reply = fullReply.toString().trim()
                if (reply.isNotEmpty()){
                    runOnUiThread{
                        addMessage("KaBu: $reply")
                        messageHistory.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", reply)
                        })
//                        speakWithTTS(reply)
                    }
                    speakWithTTS(reply)

                }

//                runOnUiThread{
//                    val reply = fullReply.toString().trim()
//                    if(reply.isNotEmpty()){
//                        addMessage("KaBu: $reply")
//
//                        //add assistant reply to history
//                        messageHistory.put(JSONObject().apply {
//                            put("role", "assistant")
//                            put("content", reply)
//                        })
//                    }
//                }
            }
        })
        
    }

    private fun speakWithTTS(text: String) {
        val ttsUrl = "http://172.20.10.3:5001/tts"

        val appFilesDir = filesDir.absolutePath
        val filename = "tts_output.wav"

        val jsonBody = JSONObject().apply {
            put("text", text)
            put("voice", "en_US-kathleen-low")
            put("filename", filename)
//            put("DIR", appFilesDir)
        }

        val request = Request.Builder()
            .url(ttsUrl)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TTS", "Failed to fetch audio", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful && response.body != null) {
                    // Save response to file
                    val audioFile = File(filesDir, filename)
                    val inputStream = response.body?.byteStream()
                    val outputStream = FileOutputStream(audioFile)

                    inputStream?.copyTo(outputStream)

                    runOnUiThread {
                        val mediaPlayer = MediaPlayer()
                        try {
                            mediaPlayer.setDataSource(audioFile.path)
                            mediaPlayer.prepare()
                            mediaPlayer.start()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        })
    }
}