package dji.sampleV5.aircraft.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sampleV5.aircraft.util.PythonBridge
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.KeyTools.createKey
import dji.v5.utils.common.ContextUtil
import java.util.Locale

import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.error.RxError
import dji.v5.common.utils.CallbackUtils
import dji.v5.common.utils.RxUtil
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.*
import dji.v5.utils.common.LogUtils
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DiskUtil
import dji.v5.utils.common.StringUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import dji.v5.manager.datacenter.media.MediaFileListData
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.common.error.IDJIError.DJIError
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList

/**
 * @author feel.feng
 * @time 2022/04/20 2:19 下午
 * @description: 媒体回放下载数据
 */
class MediaVM : DJIViewModel() {
    var mediaFileListData = MutableLiveData<MediaFileListData>()
    var fileListState = MutableLiveData<MediaFileListState>()
    var isPlayBack = MutableLiveData<Boolean?>()
    var latestCapturedImageData = MutableLiveData<ByteArray?>()
    var aiAnalysisResult = MutableLiveData<String>()
    
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    fun init() {
        addMediaFileListStateListener()
        mediaFileListData.value = MediaDataCenter.getInstance().mediaManager.mediaFileListData
        MediaDataCenter.getInstance().mediaManager.addMediaFileListStateListener { mediaFileListState ->
            if (mediaFileListState == MediaFileListState.UP_TO_DATE) {
                val data = MediaDataCenter.getInstance().mediaManager.mediaFileListData;
                mediaFileListData.postValue(data)
            }
        }

    }

    fun destroy() {
        KeyManager.getInstance().cancelListen(this);
        removeAllFileListStateListener()

        MediaDataCenter.getInstance().mediaManager.release()
        
        // Release text-to-speech resources
        if (textToSpeech != null) {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsInitialized = false
        }
    }

    fun pullMediaFileListFromCamera(mediaFileIndex: Int, count: Int) {
        var currentTime = System.currentTimeMillis()
        MediaDataCenter.getInstance().mediaManager.pullMediaFileListFromCamera(
            PullMediaFileListParam.Builder().mediaFileIndex(mediaFileIndex).count(count).build(),
            object :
                CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("Spend time:${(System.currentTimeMillis() - currentTime) / 1000}s")
                    LogUtils.i(logTag, "fetch success")
                }

                override fun onFailure(error: IDJIError) {
                    LogUtils.e(logTag, "fetch failed$error")
                }
            })
    }

    private fun addMediaFileListStateListener() {
        MediaDataCenter.getInstance().mediaManager.addMediaFileListStateListener(object :
            MediaFileListStateListener {
            override fun onUpdate(mediaFileListState: MediaFileListState) {
                fileListState.postValue(mediaFileListState)
            }

        })
    }

    private fun removeAllFileListStateListener() {
        MediaDataCenter.getInstance().mediaManager.removeAllMediaFileListStateListener()
    }

    fun getMediaFileList(): List<MediaFile> {
        return mediaFileListData.value?.data!!
    }

    fun setMediaFileXMPCustomInfo(info: String) {
        MediaDataCenter.getInstance().mediaManager.setMediaFileXMPCustomInfo(info, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    fun getMediaFileXMPCustomInfo() {
        MediaDataCenter.getInstance().mediaManager.getMediaFileXMPCustomInfo(object :
            CommonCallbacks.CompletionCallbackWithParam<String> {
            override fun onSuccess(s: String) {
                toastResult?.postValue(DJIToastResult.success(s))
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    fun setComponentIndex(index: ComponentIndexType) {
        isPlayBack.postValue(false)
        KeyManager.getInstance().cancelListen(this)
        KeyManager.getInstance().listen(
            KeyTools.createKey(
                CameraKey.KeyIsPlayingBack, index
            ), this
        ) { _, newValue ->
            isPlayBack.postValue(newValue)
        }
        val mediaSource = MediaFileListDataSource.Builder().setIndexType(index).build()
        MediaDataCenter.getInstance().mediaManager.setMediaFileDataSource(mediaSource)
    }

    fun setStorage(location: CameraStorageLocation) {
        val mediaSource = MediaFileListDataSource.Builder().setLocation(location).build()
        MediaDataCenter.getInstance().mediaManager.setMediaFileDataSource(mediaSource)
    }

    fun enable() {
        MediaDataCenter.getInstance().mediaManager.enable(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.e(logTag, "enable playback success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(logTag, "error is ${error.description()}")
            }
        })
    }

    fun disable() {
        MediaDataCenter.getInstance().mediaManager.disable(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.e(logTag, "exit playback success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(logTag, "error is ${error.description()}")
            }
        })
    }

    fun takePhoto(callback: CommonCallbacks.CompletionCallback) {
        RxUtil.setValue(createKey<CameraMode>(
            CameraKey.KeyCameraMode), CameraMode.PHOTO_NORMAL)
            .andThen(RxUtil.performActionWithOutResult(createKey(CameraKey.KeyStartShootPhoto)))
            .subscribe({ CallbackUtils.onSuccess(callback) }
            ) { throwable: Throwable ->
                CallbackUtils.onFailure(
                    callback,
                    (throwable as RxError).djiError
                )
            }
    }

    fun formatSDCard(callback: CommonCallbacks.CompletionCallback) {
        KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyFormatStorage),CameraStorageLocation.SDCARD  , object :CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>{
            override fun onSuccess(t: EmptyMsg?) {
               callback.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback.onFailure(error)
            }

        })
    }
    
    /**
     * Initializes the text-to-speech engine
     */
    private fun initTTS() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(ContextUtil.getContext()) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        LogUtils.e(logTag, "Language not supported for TTS")
                    } else {
                        ttsInitialized = true
                        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                LogUtils.i(logTag, "TTS started")
                            }

                            override fun onDone(utteranceId: String?) {
                                LogUtils.i(logTag, "TTS completed")
                            }

                            override fun onError(utteranceId: String?) {
                                LogUtils.e(logTag, "TTS error")
                            }
                        })
                    }
                } else {
                    LogUtils.e(logTag, "TTS initialization failed with status: $status")
                }
            }
        }
    }
    
    /**
     * Speaks the given text using text-to-speech
     */
    fun speakText(text: String) {
        if (!ttsInitialized) {
            initTTS()
            // Wait a bit for TTS to initialize if needed
            android.os.Handler().postDelayed({
                if (ttsInitialized) {
                    textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "djiAiAnalysis")
                } else {
                    LogUtils.e(logTag, "TTS not initialized, can't speak text")
                }
            }, 1000)
        } else {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "djiAiAnalysis")
        }
    }
    
    /**
     * Takes a photo and analyzes it with AI
     */
    fun captureAndAnalyzeWithAI(callback: CommonCallbacks.CompletionCallbackWithParam<String>) {
        // Initialize Python bridge
        PythonBridge.initialize()
        
        // Initialize TTS
        if (!ttsInitialized) {
            initTTS()
        }
        
        // First take a photo
        takePhoto(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.i(logTag, "Photo taken successfully, now retrieving latest image")
                // Get the latest image
                MediaDataCenter.getInstance().mediaManager.refreshFileList(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        // Get the latest media file
                        val mediaFiles = MediaDataCenter.getInstance().mediaManager.mediaFileListData.data
                        if (mediaFiles.isNotEmpty()) {
                            val latestFile = mediaFiles[0]
                            
                            // Download the image file
                            val tempFile = File(DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), "/temp_ai_image.jpg"))
                            var outputStream: FileOutputStream? = null
                            var bos: BufferedOutputStream? = null
                            
                            try {
                                outputStream = FileOutputStream(tempFile)
                                bos = BufferedOutputStream(outputStream)
                                
                                latestFile.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                                    override fun onStart() {
                                        LogUtils.i(logTag, "Started downloading latest image")
                                    }
                                    
                                    override fun onProgress(total: Long, current: Long) {
                                        // Report progress if needed
                                    }
                                    
                                    override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                                        try {
                                            bos?.write(data)
                                        } catch (e: IOException) {
                                            LogUtils.e(logTag, "Error writing image data: ${e.message}")
                                        }
                                    }
                                    
                                    override fun onFinish() {
                                        try {
                                            bos?.flush()
                                            bos?.close()
                                            outputStream?.close()
                                            
                                            // Convert to bitmap for analysis
                                            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                                            if (bitmap != null) {
                                                // Analyze with OpenAI
                                                val result = PythonBridge.analyzeImageWithOpenAI(bitmap)
                                                aiAnalysisResult.postValue(result)
                                                
                                                // Speak the result
                                                speakText(result)
                                                
                                                // Call the callback with success
                                                callback.onSuccess(result)
                                            } else {
                                                val errorMsg = "Failed to decode image"
                                                LogUtils.e(logTag, errorMsg)
                                                callback.onFailure(DJIError("Failed to decode image"))
                                            }
                                        } catch (e: IOException) {
                                            LogUtils.e(logTag, "Error closing streams: ${e.message}")
                                            callback.onFailure(DJIError("Error processing image: ${e.message}"))
                                        }
                                    }
                                    
                                    override fun onFailure(error: IDJIError?) {
                                        LogUtils.e(logTag, "Failed to download image: ${error?.description()}")
                                        callback.onFailure(error ?: DJIError("Unknown error downloading image"))
                                    }
                                })
                            } catch (e: Exception) {
                                LogUtils.e(logTag, "Error setting up image download: ${e.message}")
                                callback.onFailure(DJIError("Error setting up image download: ${e.message}"))
                            }
                        } else {
                            LogUtils.e(logTag, "No media files found")
                            callback.onFailure(DJIError("No media files found"))
                        }
                    }
                    
                    override fun onFailure(error: IDJIError) {
                        LogUtils.e(logTag, "Failed to refresh file list: ${error.description()}")
                        callback.onFailure(error)
                    }
                })
            }
            
            override fun onFailure(error: IDJIError) {
                LogUtils.e(logTag, "Failed to take photo: ${error.description()}")
                callback.onFailure(error)
            }
        })
    }

    fun  downloadMediaFile(mediaList : ArrayList<MediaFile>){
        mediaList.forEach {
            downloadFile(it)
        }
    }

    private fun downloadFile(mediaFile :MediaFile ) {
        val dirs = File(DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(),  "/mediafile"))
        if (!dirs.exists()) {
            dirs.mkdirs()
        }
        val filepath = DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(),  "/mediafile/"  + mediaFile?.fileName)
        val file = File(filepath)
        var offset = 0L
        val outputStream = FileOutputStream(file, true)
        val bos = BufferedOutputStream(outputStream)
        mediaFile?.pullOriginalMediaFileFromCamera(offset, object : MediaFileDownloadListener {
            override fun onStart() {
                LogUtils.i("MediaFile" , "${mediaFile.fileIndex } start download"  )
            }

            override fun onProgress(total: Long, current: Long) {
                val fullSize = offset + total;
                val downloadedSize = offset + current
                val data: Double = StringUtils.formatDouble((downloadedSize.toDouble() / fullSize.toDouble()))
                val result: String = StringUtils.formatDouble(data * 100, "#0").toString() + "%"
                LogUtils.i("MediaFile"  , "${mediaFile.fileIndex}  progress $result")
            }

            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                try {
                    bos.write(data)
                    bos.flush()
                } catch (e: IOException) {
                    LogUtils.e("MediaFile", "write error" + e.message)
                }
            }

            override fun onFinish() {
                try {
                    outputStream.close()
                    bos.close()
                } catch (error: IOException) {
                    LogUtils.e("MediaFile", "close error$error")
                }
                LogUtils.i("MediaFile" , "${mediaFile.fileIndex }  download finish"  )
            }

            override fun onFailure(error: IDJIError?) {
                LogUtils.e("MediaFile", "download error$error")
            }

        })
    }
}