package com.mobdeve.s13.martin.elaine.kabu20.voice

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.Error
import java.util.concurrent.TimeUnit

//Our LLM client
class LLMClient (
    baseUrl: String = "http://172.20.10.2:11434", //IP
    private val onToken: (String) -> Unit // streaming callback - like QWEN typing out the response
){
    //adjust the time out as necessary but this should be enough...I think
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) //no time out for stream
        .writeTimeout(120, TimeUnit.SECONDS) //timeout for sending the prompt
        .build()

    private val chatUrl = "$baseUrl/api/chat" //ollama qwen2.5 end point

    //gets the conversation history [JSON] then sends it to Ollama server
    fun chatStream(
        messages: JSONArray, //full conversation history
        onSentence: (String) -> Unit, //fire as soon as sentence is ready
        onDone: (String) -> Unit, //when full response is finished
        onError: (String) -> Unit
    ){
        val body = JSONObject().apply {
            //our LLM
            put("model", "qwen2.5")
            put("messages", messages)
            put("temperature", 0.7)
            put("repeat_penalty", 1.2)
            put("num_predict", 120)
            put("stream", true)
        }

        //send the prompt for response
        val req = Request.Builder()
            .url(chatUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            val sb = StringBuilder()

            override fun onFailure(call: Call, e: IOException){
                onError("LLM network error: ${e.message}")
            }

            //check if response code is 200 -> grab stream source from the body
            override fun onResponse(call: Call, response: Response) {
                if(!response.isSuccessful) {
                    onError("LLM HTTP error ${response.code}")
                    return
                }

                val source: BufferedSource = response.body?.source()?: run {
                    onError("LLM empty body")
                    return
                }

                try{
                    //read the response
                    while(!source.exhausted()){
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue

                        try{
                            val obj = JSONObject(line)
                            val chunk = obj
                                .optJSONObject("message")
                                ?.optString("content")
                                .orEmpty()
                            if(chunk.isNotEmpty()) {
                                sb.append(chunk)
                                onToken(chunk)

                                if (chunk.contains(".") || chunk.contains("?") || chunk.contains("!")){
                                    val sentence = sb.toString().trim()
                                    onSentence(sentence)
                                    sb.clear()
                                }
                            }
                        } catch(_: Throwable){

                        }
                    }
                } catch (e: Exception){
                    onError("LLM stream error: ${e.message}")
                }

                //sned the final message to history and to TTS
                onDone(sb.toString())
            }
        })
    }

    //to preload the model
    fun preloadModel(
        messages: JSONArray,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ){
        val body = JSONObject().apply {
            put("model", "qwen2.5")
            put("messages", messages)
            put("temperature", 0.0)
            put("num_predict", 5)
        }

        val req = Request.Builder()
            .url(chatUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()

        client.newCall(req).enqueue(object : Callback{
            override fun onFailure(call: Call, e: IOException) {
                onError("Preload failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                onDone()
            }
        })
    }

}