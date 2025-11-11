package com.mobdeve.s13.martin.elaine.kabu20.voice

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobdeve.s13.martin.elaine.kabu20.UnityHolder
import com.unity3d.player.UnityPlayer // Keep the import, but we'll use UnityHolder
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.MutableLiveData


class VoiceChatManager (
    private val activity: Activity
){
    private val TEST_MODE = false

    // State flags from V2 to prevent overlapping audio
    private var isSpeaking = false
    private var listening = false

    private var greeted = false
    val status = MutableLiveData("Idle")

    // Using the cleaner, more natural prompt from V1
    private val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put(
                "content",
                """You are KaBu, a warm, empathetic food companion. Your personality is a natural, caring, and curious friend, NOT a virtual assistant.

                * ENDING KEYWORDS *
                - If the user says "Goodbye", "bye bye", "I have to go", "see you later", or the likes, end the conversation immediately.
                - DO NOT say anything else or ask questions. Just say "goodbye" or the like.

                * CRITICAL RULES *
                - DO NOT use the literal word "name" as a placeholder. (e.g., NEVER say "Hello, name").
                - DO NOT ask for their name in the very first greeting.
                - If the emotion is "Unknown" or "neutral", just reply to their words normally.
                - Only ask 1 question per reply
                - If user says "Goodbye", "bye", or the like, end the conversation immediately. Do not say anything else or ask questions
                - As much as possible, DO NOT repeat the same questions and sentences.
                - DO NOT assume too much on the perceived emotion
                
                TONE & PERSONALITY:
                - Short & Casual: always reply with 1-2 short sentences. Act like you're chatting over a meal.
                - No assistant-speak: Never say "How can I help you?" or "Is there anything else?"
                - Use natural expressions
                - You will get hints about the user's feelings.
                - USE THESE HINTS to guide your empathy.
                - NO EMOJIS, NO MARKDOWN
                - SPEAK IN ENGLISH ONLY
                - If hints match (happy voice, happy face): Share their joy! "You sound so happy about that, I love it!".
                - If hints conflict (happy voice, sad face): Be gentle and curious.
                - If they seem sad or angry: Be extra comforting."
                
                 CONVERSATION FLOW:
                 1. Keep it flowing: Always end your reply with ONE simple, casual question. But ask only 1 question at a time.
                 2. Use Name: ONLY after the user tells you their name, you can use it sparingly. If you don't know their name, ask them what you can call them. Allow the user to start the conversation topic.
                 3. The below phases should only serve as guideline not a strict rule:
                     - Pre-meal (haven't eaten): Help them decide. Ask about cravings, their day, suggest meal ideas, or if they are hungry. Make them feel safe to tell you about their day. 
                     - During meal (eating now): ONLY ask how it tastes at the start of this phase. You should engage in casual chat about the food, make small talk, or give out fun trivia about what they are eating. Give the user some time to eat.
                     - Post-meal (finished): You can ask if they're full or if it was satisfying. If they want to end the conversation, end the conversation.
                 4. If user says "Goodbye", "bye", or the like, end the conversation immediately. Do not say anything else or ask questions 
                 5. Topic: After 2-3 non-food replies, gently steer the conversation back to food or feelings.
                """
            )
        })
    }

    private var stt: STTClient? = null
    private val llm = LLMClient(onToken = {/*if we had a text UI we'd put it here*/})
    private val tts = TTSClient(activity)

    // For FER
    private var facialEmotion = "Unknown"
    private var facialConfidence = 0.0

    // Simple state setter
    fun setFacialEmotion(ferEmotion: String, confidence: Double) {
        this.facialEmotion = ferEmotion
        this.facialConfidence = confidence
//        Log.d("VoiceChat",
//            "Facial emotion updated: $ferEmotion (${(confidence * 100).toInt()}%)")
    }

    private fun getFacialEmotion(): Pair<String, Double> {
        return Pair(facialEmotion, facialConfidence)
    }

    fun generateGreeting() {
        if (TEST_MODE) {
            Log.d("VoiceChat", "TEST MODE - Getting skipped")
            return
        }

        if (greeted) return
        greeted = true

        llm.chatStream(
            messages,
            onSentence = { sentence ->
                activity.runOnUiThread {
                    val isLast = sentence.endsWith(".") || sentence.endsWith("?") || sentence.endsWith("!")
                    tts.speak(
                        text = sentence,
                        isLastSentence = isLast,
                        onStart = {
                            status.postValue("KaBu is speaking...")
                            isSpeaking = true // V2 logic
                            Log.d("VoiceChat", "KaBu speaking: $sentence")
                            triggerTalking() // Animation fix
                        },
                        onDone = {
                            isSpeaking = false // V2 logic
                            if (isLast) {
                                // Use the safe, guarded function
                                safeStartListening()

                                //trigger sad animation
//                                activity.runOnUiThread {
//                                    activity.window.decorView.postDelayed({
//                                        Log.d("VoiceChat", "Manual sad animation trigger (test)")
//                                        triggerReaction("sad")
//                                    }, 2000) // delay in ms
//                                }

                                //trigger surprised animation
//                                activity.runOnUiThread {
//                                    activity.window.decorView.postDelayed({
//                                        Log.d("VoiceChat", "Manual surprised animation trigger (test)")
//                                        triggerReaction("surprised")
//                                    }, 1000) // delay in ms
//                                }

                                //trigger happy animation
//                                activity.runOnUiThread {
//                                    activity.window.decorView.postDelayed({
//                                        Log.d("VoiceChat", "Manual happy animation trigger (test)")
//                                        triggerReaction("happy")
//                                    }, 2000) // delay in ms
//                                }

                            }
                        },
                        onError = { err ->
                            isSpeaking = false // V2 logic
                            Log.e("VoiceChat", "TTS error: $err")
                            if (isLast) {
                                // Use the safe, guarded function (FIXED)
                                safeStartListening()
                            }
                        }
                    )
                }
            },
            onDone = { reply ->
                // * BUG FIX 1 (Greets Twice) *
                // Copied from V1's fix
                if (reply.isNotBlank()) {
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", reply)
                    })
                    Log.d("VoiceChat", "Greeting generation complete: $reply")
                } else {
                    triggerIdle()
                }
            },
            onError = { err ->
                Log.e("VoiceChat", "Greeting generation error: $err")
                activity.runOnUiThread {
                    tts.speak(
                        text = "",
                        isLastSentence = true,
                        onDone = { safeStartListening() } // Use safe logic
                    )
                }
            }
        )
    }



    //make kabu prompt the user
    fun playGreeting(greeting: String){
        if (TEST_MODE) {
            Log.d("VoiceChat", "TEST MODE - Would play: $greeting")
            return
        }

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", greeting)
        })

        activity.runOnUiThread{
            tts.speak(
                text = greeting,
                onStart = {
                    isSpeaking = true // V2 logic
                    Log.d("VoiceChat", "Kabu greets user...")
                    triggerTalking() // Animation fix
                },
                onDone = {
                    status.postValue("KaBu is Listening...")
                    isSpeaking = false // V2 logic
                    Log.d("VoiceChat: ", "Listening now...")
                    triggerIdle() // Animation fix

                    safeStartListening()
                },
                onError = { err ->
                    isSpeaking = false // V2 logic
                    Log.e("VoiceChat ", "TTS error: $err")
                    triggerIdle() // Animation fix
                    safeStartListening()
                }
            )
        }
    }

    fun startListening() {
        if (TEST_MODE) {
            Log.d("VoiceChat", "TEST MODE - Mock listening")
            return
        }

        // V2 Guard: Don't listen if we are already listening or speaking
        if (listening || isSpeaking) {
            Log.w("VoiceChat", "startListening() called but listening=$listening, isSpeaking=$isSpeaking. Skipping.")
            return
        }

        listening = true
        Log.d("VoiceChat", ">>> STARTING TO LISTEN... <<<")
        status.postValue("KaBu is Listening...")

        activity.runOnUiThread {
            stt = STTClient(
                activity,
                onPartial = { /* optional */ },
//                onFinal = { finalText, audioFile ->
//                    listening = false
//                    if (finalText.isBlank() || audioFile == null) return@STTClient
//
//                    Log.d("VoiceChat", "User said: $finalText")
//
//                    var voiceEmotion = "Unknown"
//                    var confidence = 0.0
//                    var continued = false
//                    val testEmotion = "happy"
//
//                    SERClient(activity).analyze(audioFile) { emo, conf ->
//                        if (!continued) {
//                            continued = true
//                            voiceEmotion = emo
//                            confidence = conf
//                            Log.d("VoiceChat", "Detected emotion: $emo ($conf)")
////                            triggerReaction(voiceEmotion) // Animation fix
//                            Log.d("VoiceChat", ">>> Forcing happy animation test <<<")
//                            triggerReaction(testEmotion)
//                            continueConversation(finalText, voiceEmotion, confidence)
//                            try { audioFile.delete() } catch (_: Exception) {}
//                        }
//                    }
//
//                    // fallback if SER is slow (>1.5s)
//                    activity.window.decorView.postDelayed({
//                        if (!continued) {
//                            Log.w("VoiceChat", "SER timeout → continuing without emotion")
//                            continued = true
//                            continueConversation(finalText, voiceEmotion, confidence)
//                        }
//                    }, 1500)
//                },

                onFinal = { finalText, audioFile ->
                    listening = false
                    if (finalText.isBlank() || audioFile == null) return@STTClient

                    Log.d("VoiceChat", "User said: $finalText")

                    // Default values
                    var voiceEmotion = "Unknown"
                    var confidence = 0.0
                    var continued = false

                    // Call SER
                    SERClient(activity).analyze(audioFile) { emo, conf ->
                        if (!continued) {
                            continued = true

                            // Get only the part after "/" if it exists
                            val cleanEmotion = emo.substringAfter("/", emo).trim()

                            voiceEmotion = cleanEmotion
                            confidence = conf

                            Log.d("VoiceChat", "Detected emotion (cleaned): $cleanEmotion ($conf)")

                            // Trigger animation first
                            triggerReaction(cleanEmotion)

                            // Small delay so animation shows before TTS
                            activity.window.decorView.postDelayed({
                                continueConversation(finalText, cleanEmotion, conf)
                            }, 1000)


//                            continueConversation(finalText, cleanEmotion, conf)

                            try { audioFile.delete() } catch (_: Exception) {}
                        }
                    }

                    // Optional: timeout if SER is slow
                    activity.window.decorView.postDelayed({
                        if (!continued) {
                            Log.w("VoiceChat", "SER timeout → continuing without emotion")
                            continued = true
                            continueConversation(finalText, "Unknown", 0.0)
                        }
                    }, 5000) // 5s timeout instead of 1.5s
                },

                onError = { err ->
                    listening = false
                    Log.e("VoiceChat", "STT error: $err")
                },
                fallbackTTS = { line, after ->
                    activity.runOnUiThread {
                        tts.speak(
                            text = line,
                            isLastSentence = true,
                            onDone = {
                                Log.d("VoiceChat", "Fallback spoken: $line")
                                triggerIdle() // Animation fix
                                after()
                            },
                            onStart = {
                                isSpeaking = true // V2 logic
                                triggerTalking() // Animation fix
                            }
                        )
                    }
                }
            ).also { it.start() }
        }
    }


    fun stoplistening(){
        listening = false
        stt?.stop()
        Log.d("VoiceChat", ">>> STOPPING LISTENING (manual). <<<")
    }

    fun stopAllAudio(){
        tts.stop()
        isSpeaking = false // V2 logic
        triggerIdle() // Animation fix
    }

    // This is the new, safe, guarded function to transition from TTS to STT
    private fun safeStartListening() {
        triggerIdle() // Animation fix

        // Use the same 400ms delay from V1/V2
        activity.window.decorView.postDelayed({

            // V2 Guard: Check flags before trying to listen
            if (isSpeaking || listening) {
                Log.d("VoiceChat", "[safeStart] Still speaking or already listening, skipping.")
                return@postDelayed
            }

            // We double-check tts.isPlaying just in case, but isSpeaking is the real lock
            if (!tts.isPlaying) {
                Log.d("VoiceChat", "[safeStart] Now safe to listen.")
                startListening()
            } else {
                Log.w("VoiceChat", "[safeStart] tts.isPlaying was true, retrying in 200ms.")
                activity.window.decorView.postDelayed({
                    if (isSpeaking || listening) {
                        Log.d("VoiceChat", "[safeStart] Retry cancelled, already speaking/listening.")
                        return@postDelayed
                    }
                    Log.d("VoiceChat", "[safeStart] Retrying to listen.")
                    startListening()
                }, 200) // 200ms retry
            }
        }, 400) // 400ms initial delay
    }

    private fun continueConversation(finalText: String, voiceEmotion: String, confidence: Double) {
        val (facialEmotion, facialConfidence) = getFacialEmotion()

        Log.d("VoiceChat", "[User sounded $voiceEmotion] [User looked $facialEmotion]")

        val userContent = StringBuilder(finalText)

        if(voiceEmotion != "Unknown" && voiceEmotion != "neutral"){
            userContent.append(" [User sounded $voiceEmotion]")
            Log.d("VoiceChat", "Triggering reaction ($voiceEmotion) before TTS...")

            // Trigger Unity animation first
            triggerReaction(voiceEmotion)

            // Use Handler to delay TTS start slightly for smoother animation
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("VoiceChat", "Reaction delay finished, continuing to TTS...")
                // Continue to whatever should happen next (like triggering TTS or greeting)
                triggerTalking()
            }, 2000) // 0.8 second delay before talking starts
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent.toString())
        })

        llm.chatStream(
            messages,
            onSentence = { sentence ->
                activity.runOnUiThread {
                    val isLast = sentence.endsWith(".") || sentence.endsWith("?") || sentence.endsWith("!")

//                    triggerReaction(voiceEmotion)
//                    Log.d("VoiceChat", "Triggered initial reaction before greeting TTS.")

                    tts.speak(
                        text = sentence,
                        isLastSentence = isLast,
                        onStart = {
                            isSpeaking = true // V2 logic
                            status.postValue("KaBu is Speaking...")
                            Log.d("VoiceChat", "KaBu starts speaking: $sentence")
                            triggerTalking() // Animation fix
                        },
                        onDone = {
                            isSpeaking = false // V2 logic
                            status.postValue("KaBu is Listening...")
                            if (isLast) {
                                // Use the safe, guarded function
                                safeStartListening()
                            }
                        },
                        onError = { err ->
                            isSpeaking = false // V2 logic
                            Log.e("VoiceChat", "TTS error: $err")
                            if (isLast) {
                                // Use the safe, guarded function (FIXED)
                                safeStartListening()
                            }
                        }
                    )
                }
            },
            onDone = { reply ->
                if (reply.isNotBlank()) {
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", reply)
                    })
                    Log.d("VoiceChat", "KaBu full reply: $reply")
                } else {
                    triggerIdle() // Animation fix
                }
            },
            onError = { err ->
                Log.e("VoiceChat", "LLM error: $err")
                activity.runOnUiThread { triggerIdle() } // Animation fix
            }
        )
    }

    // * UNITY ANIMATION TRIGGERS (FIXED) *
    // All triggers now use the UnityHolder.unityPlayer instance
//    fun triggerTalking(){
//        try{
//            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlayTalking", "")
//        } catch (e: Exception){
//            Log.e("VoiceChat", "Unity talking failed: ${e.message}")
//        }
//    }
//
//    fun triggerIdle(){
//        try{
//            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlayIdle", "")
//        } catch (e: Exception){
//            Log.e("VoiceChat", "Unity idle failed: ${e.message}")
//        }
//    }
//
//    fun triggerHappy(){
//        try{
//            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlayHappy", "")
//        } catch (e: Exception){
//            Log.e("VoiceChat", "Unity happy failed: ${e.message}")
//        }
//    }
//
//    fun triggerSad(){
//        try{
//            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlaySad", "")
//            Log.d("VoiceChat", "inside triggerSad")
//        } catch (e: Exception){
//            Log.e("VoiceChat", "Unity sad failed: ${e.message}")
//        }
//    }
//
//    fun triggerSurprise(){
//        try{
//            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlaySurprise", "")
//        } catch (e: Exception){
//            Log.e("VoiceChat", "Unity surprise failed: ${e.message}")
//        }
//    }


    // Helper function for safe Unity calls
    private fun safeUnitySendMessage(objectName: String, methodName: String, message: String = "") {
        activity.runOnUiThread {
            try {
                if (UnityPlayer.currentActivity != null) {
                    UnityPlayer.UnitySendMessage(objectName, methodName, message)
                    Log.d("VoiceChat", "UnitySendMessage sent: $objectName->$methodName")
                } else {
                    // Retry after a short delay
                    Log.w("VoiceChat", "UnityPlayer not ready. Retrying in 200ms...")
                    activity.window.decorView.postDelayed({
                        safeUnitySendMessage(objectName, methodName, message)
                    }, 200)
                }
            } catch (e: Exception) {
                Log.e("VoiceChat", "UnitySendMessage failed: ${e.message}")
            }
        }
    }

    // Animation triggers
    fun triggerTalking(){
        safeUnitySendMessage("kabu_happy_neutral", "PlayTalking")
    }

    fun triggerIdle(){
        safeUnitySendMessage("kabu_happy_neutral", "PlayIdle")
    }

    fun triggerHappy(){
        safeUnitySendMessage("kabu_happy_neutral", "PlayHappy")
        Log.d("VoiceChat", "inside triggerHappy")
    }

    fun triggerSad(){
        safeUnitySendMessage("kabu_happy_neutral", "PlaySad")
        Log.d("VoiceChat", "inside triggerSad")
    }

    fun triggerSurprise(){
        safeUnitySendMessage("kabu_happy_neutral", "PlayShocked")
        Log.d("VoiceChat", "inside triggerSurprise")
    }

    private fun triggerReaction(emotion: String){
        Log.d("VoiceChat", "Triggering reaction for emotion: $emotion")

        triggerIdle()

        when (emotion.lowercase()) {
            "happy" -> triggerHappy()
            "sad" -> triggerSad()
            "surprised" -> triggerSurprise()
            "angry", "fear", "Unk", "Unknown" -> {
                Log.w("VoiceChat", "No animation available.")
            }
            else -> {
                Log.d("VoiceChat", "Unhandled emotion for reaction: $emotion")
            }
        }
    }
}