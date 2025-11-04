package com.mobdeve.s13.martin.elaine.kabu20.voice

import android.app.Activity
import android.util.Log
import com.unity3d.player.UnityPlayer
import org.json.JSONArray
import org.json.JSONObject

class VoiceChatManager (
    private val activity: Activity
){
    private val TEST_MODE = false

    private var greeted = false
    private val messages = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put(
                "content", "IDENTITY: You are KaBu — a warm, food-loving eating companion. Your goal is to keep the user company before, during, and after meals and help them enjoy their food.\n" +
                        "FIRST INTERACTION: In your very first message of the conversation, greet the user, introduce yourself once as KaBu, and ask for their name. (e.g., 'Hey there! I'm KaBu. I'm here to keep you company while you eat. What's your name?').\n" +
                        "CONVERSATION RULES:\n " +
                        "- Never show internal thoughts, reasoning steps, emojis, or markdown. This is a strict rule.\n" +
                        "- After the first introduction, DO NOT introduce yourself again.\n" +
                        "- Once you learn the user's name, use it naturally and sparingly. DO NOT repeat \"Hi <username>\" or use their name in every single reply.\n" +
//                        "- If there are two emotions given (i.e. Emotion from Face and Voice), give higher priority to the voice emotion since it is more accurate than the face emotion." +
                        "TOPIC PRIORITY 1: Talk about food, cravings, and comfort.\n" +
                        "TOPIC SELECTION: Pre-meal: If they haven't eaten yet, help them decide. Suggest ideas, ask what they are craving, or talk about go-to meals. " +
                        "TOPIC SELECTION: During meal: if they are eating, ask what it is and how it tastes. Ask questions about the food, or talk about something casual. Respond enthusiastically and ask casual follow-ups (e.g., their day, funny thoughts, simple check-ins). " +
                        "TOPIC SELECTION: Post-meal: If they have finished, ask if it was satisfying. Ask if they'll have dessert or something else. If yes, return to Pre-meal.\n" +
                        "Loop: keep talking unless the user clearly says they're done. Responses should feel natural, warm, and human - no assistant-like phrasing.\n" +
                        "TRAIT #1: You speak naturally and directly, like a caring friend. Avoid overly formal or assistant-like language. " +
                        "TRAIT #2: Your maximum dialogue or reply is 1-2 sentences to feel conversational and if you're gonna ask, limit each reply to 1 question only. " +
                        "TRAIT #3: Observe the user's emotional state and respond empathetically. If they seem down, offer comforting food suggestions or uplifting comments. If they seem excited, match their energy and enthusiasm. " +
                        "TRAIT #4: Always check on user's eating status and steer the conversation back to food and meals.\n" +
                        "REMEMBER: If the topic is getting inappropriate (e.g. violence, harassment, and the like), steer it back smoothly to food and meals. " +
                        "REMEMBER: You don't always need to ask questions or suggest. Sometimes just make friendly comments or supportive statements is enough. " +
                        "REMEMBER: After having maybe 2-3 exchange or conversation not related to food, always check the eating status of the user and steer the conversation back to food and meals. " +
                        "REMEMBER: Be cohesive. Try to refer to previous parts of the conversation naturally.\n"

            )
        })
    }

    private var stt: STTClient? = null
    private val llm = LLMClient(onToken = {/*if we had a text UI we'd put it here*/})
    private val tts = TTSClient(activity)

    private var listening = false

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
                        isLastSentence = isLast, // let the last spoken chunk trigger STT
                        onStart = { Log.d("VoiceChat", "KaBu speaking: $sentence") },
                        onDone = {
                            if (isLast) {
                                Log.d("VoiceChat", "Greeting finished. Listening now...")
//                                startListening()
                                activity.window.decorView.postDelayed({
                                    if (!tts.isPlaying) {
                                        Log.d("VoiceChat", "Now safe to listen")
                                        startListening()
                                    } else {
                                        Log.d("VoiceChat", "TTS still speaking, retrying in 200ms...")
                                        activity.window.decorView.postDelayed({
                                            if (!tts.isPlaying) {
                                                Log.d("VoiceChat", "Now safe to listen")
                                                startListening()
                                            }
                                        }, 200)
                                    }
                                }, 400)
                            }
                        },
                        onError = { err -> Log.e("VoiceChat", "TTS error: $err") }
                    )
                }
            },
            onDone = { reply ->
                Log.d("VoiceChat", "Greeting generation complete: $reply")
                // Do nothing here except logging, sentences already spoken by onSentence
            },
            onError = { err ->
                Log.e("VoiceChat", "Greeting generation error: $err")
                activity.runOnUiThread {
                    tts.speak(
                        text = "Hi there! I'm KaBu. How have you been?",
                        isLastSentence = true,
                        onDone = { startListening() }
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
                    Log.d("VoiceChat", "Kabu greets user...")
                    triggerTalking()
                },
                onDone = {
                    Log.d("VoiceChat: ", "Listening now...")
                    triggerIdle()
                    startListening()
                },
                onError = { err ->
                    Log.e("VoiceChat ", "TTS error: $err")
                    triggerIdle()
                    startListening()
                }
            )
        }
    }

    fun startListening() {
        if (TEST_MODE) {
            Log.d("VoiceChat", "TEST MODE - Mock listening")
            listening = true
            // Simulate user input after delay
            activity.window.decorView.postDelayed({
                listening = false
                Log.d("VoiceChat", "TEST MODE - Mock user speech detected")
            }, 2000)
            return
        }

        if (listening) return
        listening = true

        activity.runOnUiThread {   // ✅ add this guard
            stt = STTClient(
                activity,
                onPartial = { /* optional */ },
                onFinal = { finalText, audioFile ->
                    listening = false
                    if (finalText.isBlank() || audioFile == null) return@STTClient

                    Log.d("VoiceChat", "User said: $finalText")

                    var emotion = "Unknown"
                    var confidence = 0.0
                    var continued = false

                    SERClient(activity).analyze(audioFile) { emo, conf ->
                        if (!continued) {
                            continued = true
                            emotion = emo
                            confidence = conf
                            Log.d("VoiceChat", "Detected emotion: $emo ($conf)")
                            continueConversation(finalText, emotion, confidence)

                            // (optional) tidy cache after use
                            try { audioFile.delete() } catch (_: Exception) {}
                        }
                    }

                    // fallback if SER is slow (>1.5s)
                    activity.window.decorView.postDelayed({
                        if (!continued) {
                            Log.w("VoiceChat", "SER timeout → continuing without emotion")
                            continued = true
                            continueConversation(finalText, emotion, confidence)
                            // don't delete file yet; SER may still be reading
                        }
                    }, 1500)
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
                                triggerIdle()
                                after()
                            },
                            onStart = { triggerTalking() }
                        )
                    }
                },
                // (optional) override SenseVoice endpoint here if not using the default:
                // senseVoiceUrl = "http://<your-ip>:6006/asr"
            ).also { it.start() }
        }
    }




    fun stoplistening(){
        listening = false
        stt?.stop()
    }

    fun stopAllAudio(){
        tts.stop()
        triggerIdle()
    }

    private fun continueConversation(finalText: String, emotion: String, confidence: Double) {
        // Save user message with emotion
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", "$finalText [User sounded $emotion]")
        })

        llm.chatStream(
            messages,
            onSentence = { sentence ->
                activity.runOnUiThread {
                    val isLast = sentence.endsWith(".") || sentence.endsWith("?") || sentence.endsWith("!")
                    tts.speak(
                        text = sentence,
                        isLastSentence = isLast,
                        onStart = {
                            Log.d("VoiceChat", "KaBu starts speaking: $sentence")
                            triggerTalking()
                        },
                        onDone = {
                            if (isLast) {
                                Log.d("VoiceChat", "Reply done → Listening again...")
                                triggerIdle()
//                                startListening()
                                activity.window.decorView.postDelayed({
                                    if (!tts.isPlaying) {
                                        startListening()
                                    } else {
                                        Log.d("VoiceChat", "TTS still speaking, retrying in 200ms...")
                                        activity.window.decorView.postDelayed({
                                            if (!tts.isPlaying) {
                                                Log.d("VoiceChat", "Now safe to listen")
                                                startListening()
                                            }
                                        }, 200)
                                    }
                                }, 400)
                            }
                        },
                        onError = { err ->
                            Log.e("VoiceChat", "TTS error: $err")
                            if (isLast) {
                                triggerIdle()
                                startListening()
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
                    triggerIdle()
                }
            },
            onError = { err ->
                Log.e("VoiceChat", "LLM error: $err")
                activity.runOnUiThread { triggerIdle() }
            }
        )
    }

    //UNITY ANIMATION TRIGGERS
    private fun triggerTalking(){
        try{
            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlayTalking", "")
        } catch (e: Exception){
            Log.e("VoiceChat", "Unity talking failed: ${e.message}")
        }
    }

    private fun triggerIdle(){
        try{
            UnityPlayer.UnitySendMessage("kabu_happy_neutral", "PlayIdle", "")
        } catch (e: Exception){
            Log.e("VoiceChat", "Unity idle failed: ${e.message}")
        }
    }
}