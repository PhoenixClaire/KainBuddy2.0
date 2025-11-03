package com.mobdeve.s13.martin.elaine.kabu20

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mobdeve.s13.martin.elaine.kabu20.databinding.ActivitySplashScreen1Binding
import com.mobdeve.s13.martin.elaine.kabu20.databinding.ActivitySplashScreen2Binding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SplashScreenActivity2 : AppCompatActivity() {
    private lateinit var viewBinding: ActivitySplashScreen2Binding

    private val client = OkHttpClient.Builder()
        . connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewBinding = ActivitySplashScreen2Binding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.splashScreen2.alpha = 0f
        viewBinding.splashScreen2.animate().setDuration(3000).alpha(1f)
//        viewBinding.splashScreen2.animate().setDuration(3000).alpha(1f).withEndAction {
//            startActivity(Intent(this, VideoCallActivity::class.java))
//            finish()
//        }

        preloadModel()
    }

    //this laods the model during splash screen before directing the user to the home page
    private fun preloadModel(){
        val jsonBody = JSONObject().apply {
            put("model", "qwen2.5")
            put("messages", JSONArray().put(JSONObject().apply {
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
            }))
            put("temperature", 0.0)
            put("num_predict", 10)
        }
        val request = Request.Builder()
            .url("http://172.20.10.2:11434/api/chat") //TODO: change this to your machine IP address
            .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            var reply = ""
            override fun onFailure(call: Call, e: IOException) {
                goToNextScreen(reply)
            }

            override fun onResponse(call: Call, response: Response)
            {
                val source = response.body?.source()
                source?.request(Long.MAX_VALUE)
                val buffer = source?.buffer

                val fullReply = StringBuilder()
                while(true){
                    val line = buffer?.readStringLine() ?: break
                    if(line.isNotBlank()){
                        try{
                            val obj = JSONObject(line)
                            val chunk = obj.optJSONObject("message")?.optString("content", "")
                            if(!chunk.isNullOrEmpty()){
                                fullReply.append(chunk)

                            }
                        } catch (_:  Exception){

                        }
                    }
                    reply = fullReply.toString().trim()
                    goToNextScreen(reply)
                }
            }
        })
    }

    private fun goToNextScreen(reply: String){
        runOnUiThread{
            val intent = Intent(this, VideoCallActivity::class.java)
            intent.putExtra("Greetings", reply)
            startActivity(intent)
            finish()
        }
    }
    fun Buffer.readStringLine(): String? {
        val index = indexOf('\n'.toByte())
        return if (index == -1L) null else readString(index + 1, Charsets.UTF_8)
    }
}