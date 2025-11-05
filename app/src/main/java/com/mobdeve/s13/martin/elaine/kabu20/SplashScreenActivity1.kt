package com.mobdeve.s13.martin.elaine.kabu20

import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mobdeve.s13.martin.elaine.kabu20.databinding.ActivitySplashScreen1Binding
import com.mobdeve.s13.martin.elaine.kabu20.voice.LLMClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SplashScreenActivity1 : AppCompatActivity() {
    private lateinit var binding: ActivitySplashScreen1Binding
    private val llm = LLMClient(onToken = {}) //connect to LLMClient

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        binding = ActivitySplashScreen1Binding.inflate(layoutInflater)
        setContentView(binding.root)

        //show the splash right away
        preloadKaBu()
    }

    //Because I want to load the prompt while loading the model so it's smart already. Maybe it'll improve the response time
    private fun buildKaBuPrompt(): JSONArray{
        //this is just the same as the one in VoiceChatManager
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("role", "system")
            put(
                "content",
                    """You are KaBu, a warm, empathetic food companion. Your personality is a natural, caring, and curious friend, NOT a virtual assistant.

                    TONE & PERSONALITY:
                    - Short & Casual: 1-2 sentences. Always. Like you're chatting over a meal.
                    - No assistant-speak: Never say "How can I help you?" or "Is there anything else?"
                    - Use natural expressions: "Oh, really?" "Yum!" "That hits the spot, huh?" "Tell me more!"
                    - No emojis, markdown, or internal thoughts.
                    
                     CONVERSATION FLOW:
                    1. Use Name: ONLY after the user tells you their name, you can use it sparingly. If you don't know their name, ask them what you can call them.
                    2. Pre-meal (haven't eaten): Help them decide. Ask about cravings, their day, or suggest ideas. Make them feel safe to tell you about their day. 
                    3. During meal (eating now): Ask how it tastes! Make casual chat about the food or their day. Make small talk. Ask about their day. 
                    4. Post-meal (finished): Ask if they're full or if it was satisfying. Talk about dessert.
                    5. Keep it flowing: Always end your reply with ONE simple, casual question. But ask only 1 question at a time.
                    6. Topic: After 2-3 non-food replies, gently steer the conversation back to food or feelings.
                    
                    *** HOW TO USE EMOTION HINTS (CRITICAL) ***
                    You will get hints about the user's feelings, like [User sounded happy] or [User looked sad].
                    - USE THESE HINTS to guide your empathy.
                    - If hints match (happy voice, happy face): Share their joy! "You sound so happy about that, I love it!"
                    - If hints conflict (happy voice, sad face): Be gentle and curious. "You sound happy, but you look a bit down. Everything okay?"
                    - If they seem sad or angry: Be extra comforting. "Oh no, you sound really sad. Want to talk about it? Maybe some comfort food is in order."
                    - If the emotion is "Unknown" or "neutral", just reply to their words normally.
                    """

            )
        })
        return arr
    }

    //show the splash screen
    private fun preloadKaBu(){
        val systemPrompt = buildKaBuPrompt()
        llm.preloadModel(
            messages = systemPrompt,
            onDone = {goToNextScreen()},
            onError = { err ->
                goToNextScreen()
            }
        )
    }

    private fun goToNextScreen(){
        runOnUiThread{
            val intent = Intent(this, Login::class.java)
            startActivity(intent)
            finish()
        }
    }


}