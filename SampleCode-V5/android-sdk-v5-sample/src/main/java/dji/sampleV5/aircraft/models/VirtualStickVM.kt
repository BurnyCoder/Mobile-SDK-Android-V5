package dji.sampleV5.aircraft.models

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.MediaPlayer
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
        
        // First, set camera to photo mode
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN),
            CameraMode.PHOTO_NORMAL,
            null
        )
        
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
                        Log.d(TAG, "Frame received: $width x $height, format: $format")
                        
                        // For RGBA_8888 format, convert to JPEG
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
                        val base64Image = Base64.encodeToString(jpegData, Base64.NO_WRAP)
                        
                        // Send to OpenAI with step description as context
                        analyzeImageWithOpenAI(base64Image, "Current drone movement: $stepDescription. Describe what the drone camera sees.")
                        
                        // Remove listener after getting the frame
                        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing frame: ${e.message}")
                    }
                }
            })
    }
    
    private fun analyzeImageWithOpenAI(base64Image: String, prompt: String) {
        if (openaiApiKey.isEmpty()) {
            Log.e(TAG, "OpenAI API key is not set")
            return
        }
        
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
                put("model", "gpt-4o") // Or appropriate vision-capable model
                put("max_tokens", 300)
                
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
                
                put("messages", messagesArray)
            }
            
            val requestBody = requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)
            
            val request = Request.Builder()
                .url(OPENAI_API_URL)
                .addHeader("Authorization", "Bearer $openaiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
                
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "OpenAI API call failed: ${e.message}")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val choices = jsonResponse.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val firstChoice = choices.getJSONObject(0)
                                val message = firstChoice.getJSONObject("message")
                                val content = message.getString("content")
                                
                                // Speak the result using TTS
                                speakText(content)
                                
                                Log.d(TAG, "OpenAI response: $content")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing OpenAI response: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "OpenAI API error: ${response.code}. Response: $responseBody")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing OpenAI request: ${e.message}")
        }
    }
    
    private fun speakText(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drone_analysis_${System.currentTimeMillis()}")
    }
    
    fun performAutoFlight(startTakeOff: () -> Unit, startLanding: () -> Unit) {
        val flightHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Step 1: Take off
        startTakeOff()
        
        // Step 2: After take off, enable virtual stick to control flight
        flightHandler.postDelayed({
            // Capture image after takeoff
            captureAndAnalyzeImage("Just took off, hovering in place")
            
            enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    setSpeedLevel(0.3) // Set moderate speed
                    
                    // Step 3: Move forward
                    flightHandler.postDelayed({
                        // Set pitch to move forward
                        setRightPosition(0, 300)
                        
                        // Capture image during forward movement
                        flightHandler.postDelayed({
                            captureAndAnalyzeImage("Moving forward")
                        }, 1500) // Capture in middle of forward movement
                        
                        // Step 4: After moving forward, stop and prepare to spin
                        flightHandler.postDelayed({
                            setRightPosition(0, 0)
                            
                            // Capture image after stopping forward movement
                            captureAndAnalyzeImage("Stopped after moving forward")
                            
                            // Step 5: Spin (rotate)
                            flightHandler.postDelayed({
                                // Set yaw to rotate
                                setLeftPosition(300, 0)
                                
                                // Capture image during rotation
                                flightHandler.postDelayed({
                                    captureAndAnalyzeImage("Currently spinning/rotating")
                                }, 1500) // Capture in middle of rotation
                                
                                // Step 6: After complete rotation, stop spinning
                                flightHandler.postDelayed({
                                    setLeftPosition(0, 0)
                                    
                                    // Capture image after rotation is complete
                                    captureAndAnalyzeImage("Completed 360-degree rotation")
                                    
                                    // Step 7: Disable virtual stick before landing
                                    flightHandler.postDelayed({
                                        disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                                            override fun onSuccess() {
                                                // Step 8: Land the drone
                                                flightHandler.postDelayed({
                                                    startLanding()
                                                    
                                                    // Capture final image during landing
                                                    flightHandler.postDelayed({
                                                        captureAndAnalyzeImage("Currently landing")
                                                    }, 1500)
                                                }, 1000)
                                            }
                                            
                                            override fun onFailure(error: IDJIError) {
                                                // Still try to land even if disabling virtual stick fails
                                                flightHandler.postDelayed({
                                                    startLanding()
                                                    
                                                    // Capture final image during landing
                                                    flightHandler.postDelayed({
                                                        captureAndAnalyzeImage("Currently landing")
                                                    }, 1500)
                                                }, 1000)
                                            }
                                        })
                                    }, 2000) // Wait a bit longer after rotation to analyze the image
                                }, 3000) // Spin for 3 seconds
                            }, 2000) // Wait a bit longer after stopping to analyze the image
                        }, 3000) // Move forward for 3 seconds
                    }, 3000) // Wait for take off to complete
                }
                
                override fun onFailure(error: IDJIError) {
                    // If enabling virtual stick fails, just land
                    flightHandler.postDelayed({
                        startLanding()
                    }, 1000)
                }
            })
        }, 5000) // Wait for take off to complete before enabling virtual stick
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