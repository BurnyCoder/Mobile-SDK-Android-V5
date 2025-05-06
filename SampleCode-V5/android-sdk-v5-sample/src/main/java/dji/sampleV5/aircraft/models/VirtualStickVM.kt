package dji.sampleV5.aircraft.models

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/6/18
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickVM : DJIViewModel() {
    companion object {
        private const val TAG = "VirtualStickVM"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    val currentSpeedLevel = MutableLiveData(0.0)
    var useRcStick = MutableLiveData(false)
    val currentVirtualStickStateInfo = MutableLiveData(VirtualStickStateInfo())
    
    // OpenAI API key - This should be stored securely, preferably not hardcoded
    private var openaiApiKey: String = ""
    private var context: Context? = null
    private var textToSpeech: TextToSpeech? = null

    val virtualStickAdvancedParam = MutableLiveData(VirtualStickFlightControlParam()).apply {
        value?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        value?.verticalControlMode = VerticalControlMode.VELOCITY
        value?.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        value?.rollPitchControlMode = RollPitchControlMode.ANGLE
    }

    // RC Stick Value
    var stickValue = MutableLiveData(RCStickValue(0, 0, 0, 0))

    init {
        currentSpeedLevel.value = VirtualStickManager.getInstance().speedLevel
        VirtualStickManager.getInstance().setVirtualStickStateListener(object :
            VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.state = stickState
                })
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.reason = reason
                })
            }
        })
    }

    fun enableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().enableVirtualStick(callback)
    }

    fun disableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().disableVirtualStick(callback)
    }

    fun setSpeedLevel(speedLevel: Double) {
        VirtualStickManager.getInstance().speedLevel = speedLevel
        currentSpeedLevel.value = speedLevel
    }

    fun setLeftPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().leftStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().leftStick.verticalPosition = vertical
    }

    fun setRightPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().rightStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().rightStick.verticalPosition = vertical
    }

    fun sendVirtualStickAdvancedParam(param: VirtualStickFlightControlParam) {
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    fun disableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
    }

    fun enableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
    }

    fun listenRCStick() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.leftHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickLeftVertical.create().listen(this) {
            it?.let {
                stickValue.value?.leftVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.rightHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightVertical.create().listen(this) {
            it?.let {
                stickValue.value?.rightVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
    }

    private fun tryUpdateVirtualStickByRc() {
        stickValue.postValue(stickValue.value)
        if (useRcStick.value == true) {
            stickValue.value?.apply {
                setLeftPosition(leftHorizontal, leftVertical)
                setRightPosition(rightHorizontal, rightVertical)
            }
        }
    }

    fun initialize(context: Context, apiKey: String) {
        this.context = context
        this.openaiApiKey = apiKey
        
        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                textToSpeech?.language = Locale.US
            }
        }
    }
    
    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
        
        // Clean up TTS resources
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
    
    private fun captureAndAnalyzeImage(stepDescription: String) {
        if (context == null) {
            Log.e(TAG, "Context is null, can't capture image")
            return
        }
        
        // Log start of image capture process
        Log.d(TAG, "Starting image capture process")
        
        // First, set camera to photo mode
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN),
            CameraMode.PHOTO_NORMAL,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully set camera to photo mode")
                    captureFrame(stepDescription)
                }
                
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Failed to set camera mode: ${error.description()}")
                    // Try to capture frame anyway
                    captureFrame(stepDescription)
                }
            }
        )
    }
    
    private fun captureFrame(stepDescription: String) {
        Log.d(TAG, "Adding camera frame listener to capture image")
        
        // Add frame listener to get a preview frame
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.RGBA_8888,
            object : ICameraStreamManager.CameraFrameListener {
                override fun onFrame(
                    frameData: ByteArray,
                    offset: Int,
                    length: Int,
                    width: Int,
                    height: Int,
                    format: ICameraStreamManager.FrameFormat
                ) {
                    try {
                        Log.d(TAG, "Frame received: $width x $height, format: $format, data length: ${frameData.size}")
                        
                        // For RGBA_8888 format, convert to JPEG
                        Log.d(TAG, "Converting camera frame to JPEG")
                        val yuvImage = YuvImage(
                            frameData,
                            ImageFormat.NV21,
                            width,
                            height,
                            null
                        )
                        
                        val out = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(
                            Rect(0, 0, width, height),
                            90, // JPEG quality
                            out
                        )
                        
                        val jpegData = out.toByteArray()
                        Log.d(TAG, "JPEG data size: ${jpegData.size} bytes")
                        
                        val base64Image = Base64.encodeToString(jpegData, Base64.NO_WRAP)
                        Log.d(TAG, "Base64 encoded image size: ${base64Image.length} characters")
                        
                        // Send to OpenAI with step description as context
                        Log.d(TAG, "Sending image to OpenAI for analysis")
                        analyzeImageWithOpenAI(base64Image, "Current drone camera view: $stepDescription. Describe what can be seen in this image in detail.")
                        
                        // Remove listener after getting the frame
                        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
                        Log.d(TAG, "Removed camera frame listener")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing frame: ${e.message}", e)
                    }
                }
            })
    }
    
    private fun analyzeImageWithOpenAI(base64Image: String, prompt: String) {
        if (openaiApiKey.isEmpty()) {
            Log.e(TAG, "OpenAI API key is not set")
            speakText("Error: OpenAI API key is not set")
            return
        }
        
        // Log that we're starting the OpenAI API call
        Log.d(TAG, "Setting up OpenAI API call with prompt: $prompt")
        
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)  // Increased timeout for better reliability
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
            
        try {
            // Create JSON payload for OpenAI API
            val contentArray = JSONArray()
            
            // Add text prompt
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            
            // Add image
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                })
            })
            
            // Build full request body
            val requestBodyJson = JSONObject().apply {
                put("model", "gpt-4o") // Using GPT-4o for vision capabilities
                put("max_tokens", 500) // Increased for more detailed descriptions
                
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
                
                put("messages", messagesArray)
            }
            
            val requestJson = requestBodyJson.toString()
            Log.d(TAG, "OpenAI API request prepared (payload size: ${requestJson.length})")
            
            val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)
            
            val request = Request.Builder()
                .url(OPENAI_API_URL)
                .addHeader("Authorization", "Bearer $openaiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending API request to OpenAI...")
            speakText("Analyzing drone camera image...")
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "OpenAI API call failed: ${e.message}", e)
                    speakText("Error connecting to OpenAI API. Please check your internet connection and API key.")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Received response from OpenAI, status code: ${response.code}")
                    
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            Log.d(TAG, "Response body: $responseBody")
                            val jsonResponse = JSONObject(responseBody)
                            val choices = jsonResponse.getJSONArray("choices")
                            
                            if (choices.length() > 0) {
                                val firstChoice = choices.getJSONObject(0)
                                val message = firstChoice.getJSONObject("message")
                                val content = message.getString("content")
                                
                                // Log the full content
                                Log.d(TAG, "OpenAI response content: $content")
                                
                                // Speak the result using TTS
                                speakText(content)
                            } else {
                                Log.e(TAG, "OpenAI response has no choices")
                                speakText("Error: No response content received from OpenAI")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing OpenAI response: ${e.message}", e)
                            speakText("Error processing the AI response")
                        }
                    } else {
                        val errorMsg = "OpenAI API error: ${response.code}. Response: $responseBody"
                        Log.e(TAG, errorMsg)
                        
                        // Give a more user-friendly error message based on status code
                        val errorSpeech = when(response.code) {
                            401 -> "Authentication error. Please check your API key."
                            429 -> "Rate limit exceeded. Please try again later."
                            500, 502, 503, 504 -> "OpenAI service error. Please try again later."
                            else -> "Error from OpenAI API. Please check logs for details."
                        }
                        
                        speakText(errorSpeech)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing OpenAI request: ${e.message}", e)
            speakText("Error preparing request to OpenAI")
        }
    }
    
    private fun speakText(text: String) {
        Log.d(TAG, "Speaking text: $text")
        
        if (textToSpeech == null) {
            Log.e(TAG, "TextToSpeech is not initialized")
            return
        }
        
        // Check if TTS is ready
        if (textToSpeech?.engines?.isEmpty() == true) {
            Log.e(TAG, "No TTS engines available")
            return
        }
        
        // Add a small delay to ensure TTS is ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val utteranceId = "drone_analysis_${System.currentTimeMillis()}"
            
            // Set up utterance progress listener for better feedback
            textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking")
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error occurred")
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error occurred with code: $errorCode")
                }
            })
            
            // Check if text is too long and trim if needed
            val maxLength = 4000 // TTS has character limits
            val textToSpeak = if (text.length > maxLength) {
                Log.w(TAG, "Text too long for TTS, trimming to $maxLength characters")
                text.substring(0, maxLength) + "... (text trimmed)"
            } else {
                text
            }
            
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            
            val result = textToSpeech?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "Error speaking text")
            }
        }, 500) // Small delay to ensure TTS is ready
    }
    
    fun performAutoFlight(startTakeOff: () -> Unit, startLanding: () -> Unit) {
        val flightHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        try {
            // Check if we have camera access before proceeding
            val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
            if (cameraStreamManager == null) {
                Log.e(TAG, "Failed to get camera stream manager")
                speakText("Error: Cannot access camera stream manager")
                return
            }
            
            Log.d(TAG, "Starting image capture test sequence")
            speakText("Starting camera capture test. Please wait...")
            
            // For testing purposes, just capture and analyze a single image
            flightHandler.postDelayed({
                Log.d(TAG, "Capturing a single image and sending to OpenAI for analysis")
                captureAndAnalyzeImage("Test image capture from drone camera")
            }, 2000) // Small delay to allow TTS to complete initial announcement
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto flight sequence: ${e.message}", e)
            speakText("Error starting camera test: ${e.message}")
        }
    }

    data class VirtualStickStateInfo(
        var state: VirtualStickState = VirtualStickState(false, FlightControlAuthority.UNKNOWN, false),
        var reason: FlightControlAuthorityChangeReason = FlightControlAuthorityChangeReason.UNKNOWN
    )

    data class RCStickValue(
        var leftHorizontal: Int, var leftVertical:
        Int, var rightHorizontal: Int, var rightVertical: Int
    ) {
        override fun toString(): String {
            return "leftHorizontal=$leftHorizontal,leftVertical=$leftVertical,\n" +
                    "rightHorizontal=$rightHorizontal,rightVertical=$rightVertical"
        }
    }
}