package com.mobdeve.s13.martin.elaine.kabu20.emotion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.ImageFormat
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedList

class FaceEmotionAnalyzer(
    private val context: Context,
    private val onEmotionDetected: (String, Float) -> Unit
) : ImageAnalysis.Analyzer {

    // Properties to store model requirements
    private var inputHeight: Int = 48
    private var inputWidth: Int = 48
    private var inputChannels: Int = 1
    private var requiresRGB: Boolean = false
    private var isChannelFirst: Boolean = false // Add this for models with [1, channels, height, width]

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    )

    private var tflite: Interpreter? = null
    private val emotionLabels = listOf(
        "Angry", "Disgust", "Fear", "Happy", "Sad", "Surprise", "Neutral", "Contempt"
    )

    // Sliding window smoothing for more stable predictions
    private val emotionHistory = LinkedList<Pair<String, Float>>()
    private val historySize = 5

    init {
        try {
//            val modelBuffer = loadModelFileFromAssets(context, "ferplus_model_pd_best.tflite")
            val modelBuffer = loadModelFileFromAssets(context, "fer2013_mini_XCEPTION.tflite")
//            val modelBuffer = loadModelFileFromAssets(context, "justinshenk_emotion_model_quantized.tflite")
//            val modelBuffer = loadModelFileFromAssets(context, "Shubham-Zone_model.tflite")
            if (modelBuffer != null) {
                tflite = Interpreter(modelBuffer)
                analyzeModelInputRequirements() // Call this to set up the properties
            }
        } catch (e: Exception) {
            Log.e("FER", "Load failed: ${e.message}")
        }
    }

    private fun analyzeModelInputRequirements() {
        val inputTensor = tflite?.getInputTensor(0)
        val inputShape = inputTensor?.shape()

        // Check if it's channel-first format [1, channels, height, width]
        isChannelFirst = when {
            inputShape?.size == 4 && inputShape[1] == 3 -> true // [1, 3, height, width]
            else -> false
        }

        if (isChannelFirst) {
            // Channel-first format: [batch_size, channels, height, width]
            inputHeight = inputShape?.get(2) ?: 48      // Change height based on model
            inputWidth = inputShape?.get(3) ?: 48          // Change width based on model
            inputChannels = inputShape?.get(1) ?: 1         // Change channels based on model
        } else {
            // Channel-last format: [batch_size, height, width, channels]
            inputHeight = inputShape?.get(1) ?: 48      // Change height based on model
            inputWidth = inputShape?.get(2) ?: 48       // Change width based on model
            inputChannels = inputShape?.get(3) ?: 1     // Change channels based on model
        }

        requiresRGB = inputChannels == 3

        Log.d("FER", "Model configured for: ${inputHeight}x${inputWidth}x${inputChannels}, channelFirst: $isChannelFirst")
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces -> processFaces(faces, imageProxy) }
                .addOnFailureListener { e -> Log.e("FaceEmotionAnalyzer", "Detection failed: ${e.message}") }
                .addOnCompleteListener { imageProxy.close() }
        } else imageProxy.close()
    }

    private fun processFaces(faces: List<Face>, imageProxy: ImageProxy) {
        if (faces.isEmpty()) return

        val rgbBitmap = imageProxyToBitmap(imageProxy)

        for (face in faces) {
            if (tflite != null) {
                try {
                    val faceBitmap = cropFace(rgbBitmap, face)
                    val (label, conf) = runTFLiteModel(faceBitmap)

                    // Blend with ML Kit probabilities if available
                    val smileProb = face.smilingProbability ?: 0f
                    val adjustedLabel = if (smileProb > 0.7 && label != "Happy") "Happy" else label
                    val adjustedConf = if (adjustedLabel == "Happy") (conf + smileProb) / 2 else conf

                    // Smoothing results to reduce flicker
                    emotionHistory.add(adjustedLabel to adjustedConf)
                    if (emotionHistory.size > historySize) emotionHistory.removeFirst()

                    val smoothed = emotionHistory.groupBy { it.first }
                        .mapValues { entry -> entry.value.map { it.second }.average().toFloat() }
                        .maxByOrNull { it.value }

                    val finalLabel = smoothed?.key ?: adjustedLabel
                    val finalConf = smoothed?.value ?: adjustedConf

                    Log.d("FaceEmotionAnalyzer", "🎯 Emotion: $finalLabel (${"%.2f".format(finalConf)})")
                    onEmotionDetected(finalLabel, finalConf)

                } catch (e: Exception) {
                    Log.e("FaceEmotionAnalyzer", "❌ Inference failed: ${e.message}")
                }
            } else {
                val smileProb = face.smilingProbability ?: -1f
                val leftEye = face.leftEyeOpenProbability ?: -1f
                val rightEye = face.rightEyeOpenProbability ?: -1f

                val emotion = when {
                    smileProb > 0.7 -> "Happy"
                    smileProb < 0.3 && leftEye < 0.5 && rightEye < 0.5 -> "Tired"
                    smileProb < 0.3 -> "Neutral"
                    else -> "Unknown"
                }
                val confidence = if (smileProb >= 0) smileProb else 0.5f
                onEmotionDetected(emotion, confidence)
            }
        }
    }

    private fun loadModelFileFromAssets(context: Context, filename: String): ByteBuffer? {
        return try {
            val afd = context.assets.openFd(filename)
            val inputStream = FileInputStream(afd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.e("FaceEmotionAnalyzer", "Model load error: ${e.message}")
            null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap {
        val bounds = face.boundingBox
        val x = bounds.left.coerceAtLeast(0)
        val y = bounds.top.coerceAtLeast(0)
        val width = bounds.width().coerceAtMost(bitmap.width - x)
        val height = bounds.height().coerceAtMost(bitmap.height - y)
        return if (width <= 0 || height <= 0) bitmap else Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    private fun runTFLiteModel(faceBitmap: Bitmap): Pair<String, Float> {
        // Use the stored model requirements instead of querying every time
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputWidth, inputHeight, true)

        // Change preprocessing based on channel requirements
        val processedBitmap = when (inputChannels) {
            1 -> toGrayscale(resized)  // Single channel (grayscale)
            3 -> toRGB(resized)        // Three channels (RGB)
            else -> toGrayscale(resized)
        }

        // Change buffer size based on input dimensions
        val inputBuffer = ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * inputChannels)
            .order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        if (isChannelFirst) {
            // Channel-first processing: [channels, height, width]
            processChannelFirst(processedBitmap, inputBuffer)
        } else {
            // Channel-last processing: [height, width, channels]
            processChannelLast(processedBitmap, inputBuffer)
        }

        val output = Array(1) { FloatArray(emotionLabels.size) }
        tflite?.run(inputBuffer, output)

        val idx = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        val conf = output[0][idx]
        val label = emotionLabels.getOrNull(idx) ?: "Unknown"

        return label to conf
    }

    private fun processChannelLast(bitmap: Bitmap, buffer: ByteBuffer) {
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = bitmap.getPixel(x, y)
                when (inputChannels) {
                    1 -> {
                        // Grayscale processing - use red channel for simplicity
                        val r = (pixel shr 16 and 0xFF)
                        buffer.putFloat((r / 255f).coerceIn(0f, 1f))
                    }
                    3 -> {
                        // RGB processing - change order if needed (RGB vs BGR)
                        val r = ((pixel shr 16) and 0xFF) / 255f
                        val g = ((pixel shr 8) and 0xFF) / 255f
                        val b = (pixel and 0xFF) / 255f
                        buffer.putFloat(r)  // Change channel order based on model requirements
                        buffer.putFloat(g)
                        buffer.putFloat(b)
                    }
                }
            }
        }
    }

    private fun processChannelFirst(bitmap: Bitmap, buffer: ByteBuffer) {
        // Process all red channels first, then green, then blue
        for (channel in 0 until inputChannels) {
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = bitmap.getPixel(x, y)
                    val value = when (channel) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f  // Red channel
                        1 -> ((pixel shr 8) and 0xFF) / 255f   // Green channel
                        2 -> (pixel and 0xFF) / 255f        // Blue channel
                        else -> 0f
                    }
                    buffer.putFloat(value)
                }
            }
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return gray
    }

    private fun toRGB(src: Bitmap): Bitmap {
        // Ensure bitmap is in RGB format
        val rgb = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rgb)
        canvas.drawBitmap(src, 0f, 0f, null)
        return rgb
    }
}