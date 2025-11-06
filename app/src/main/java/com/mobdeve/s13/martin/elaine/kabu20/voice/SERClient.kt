package com.mobdeve.s13.martin.elaine.kabu20.voice
import android.app.Activity
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class SERClient (
    private val activity: Activity,
    private val baseUrl: String = "http://10.0.0.108:6006/analyze_emotion" //IP
    ) {

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun analyze(file: File, callback: (String, Double) -> Unit){

            //no audio file -> unknown
            if(!file.exists()){
                callback("Unknown", 0.0)
                return
            }

            //request body
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", file.name, file.asRequestBody("audio/wav".toMediaTypeOrNull()))
                .build()

            //request
            val req = Request.Builder()
                .url(baseUrl)
                .post(body)
                .build()

            client.newCall(req).enqueue(object : Callback{

                override fun onFailure(call: Call, e: IOException) {
                    Log.e("SER", "SER request failed: ${e.message}")
                    callback("Unknown", 0.0)
                }

                override fun onResponse(call: Call, response: Response) {

                    val res = response.body?.string().orEmpty()

                    try{
                        val json = JSONObject(res)
                        val emotion = json.optString("emotion", "Unknown")
                        val confidence = json.optDouble("confidence", 0.0)
                        callback(emotion, confidence)
                    } catch (e: Exception){
                        Log.e("SER", "Parse error: $res")
                        callback("Unknown", 0.0)
                    }
                }
            })
        }
}