package dji.sampleV5.aircraft.util

import android.graphics.Bitmap
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.utils.common.ContextUtil

/**
 * A bridge class to interact with Python code from Kotlin using Chaquopy.
 * This enables calling Python AI processing functions from the Android app.
 */
class PythonBridge {
    companion object {
        private const val TAG = "PythonBridge"
        private var initialized = false

        /**
         * Initialize the Python environment.
         * Should be called once during app startup.
         */
        fun initialize() {
            if (!initialized) {
                try {
                    ToastUtils.showToast("Starting Python environment initialization...")
                    if (!Python.isStarted()) {
                        ToastUtils.showToast("Python not started, starting now...")
                        Python.start(AndroidPlatform(ContextUtil.getContext()))
                        ToastUtils.showToast("Python runtime started successfully")
                    } else {
                        ToastUtils.showToast("Python runtime already running")
                    }
                    initialized = true
                    Log.i(TAG, "Python environment initialized successfully")
                    ToastUtils.showToast("Python environment fully initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Python environment: ${e.message}")
                    ToastUtils.showToast("CRITICAL ERROR: Failed to initialize Python: ${e.message}")
                    ToastUtils.showToast("Error details: ${e.stackTraceToString().substring(0, 100)}...")
                }
            } else {
                ToastUtils.showToast("Python already initialized")
            }
        }

        /**
         * Analyze an image using OpenAI through the Python script
         * @param bitmap The image to analyze
         * @return The analysis result as a string
         */
        fun analyzeImageWithOpenAI(bitmap: Bitmap): String {
            if (!initialized) {
                ToastUtils.showToast("Python not initialized, initializing now...")
                initialize()
            }

            ToastUtils.showToast("Starting AI image analysis (${bitmap.width}x${bitmap.height})...")
            
            try {
                // Get the Python instance
                val startTime = System.currentTimeMillis()
                ToastUtils.showToast("Getting Python instance...")
                val py = Python.getInstance()
                ToastUtils.showToast("Python instance obtained successfully")
                
                // Import our ai_processing module
                ToastUtils.showToast("Loading AI processing module...")
                val aiProcessingModule = py.getModule("ai_processing")
                ToastUtils.showToast("AI processing module loaded successfully")
                
                // Import bitmap utils module
                ToastUtils.showToast("Loading bitmap conversion module...")
                val bitmapModule = py.getModule("bitmaputils")
                ToastUtils.showToast("Bitmap module loaded successfully")
                
                // Convert Bitmap to a format Python can use (NumPy array)
                ToastUtils.showToast("Converting image to format suitable for AI processing...")
                val imageArray: PyObject = bitmapModule.callAttr("bitmap_to_cv2", bitmap)
                ToastUtils.showToast("Image converted successfully")
                
                // Call the Python function for image analysis
                ToastUtils.showToast("Starting YOLO object detection...")
                val result: PyObject = aiProcessingModule.callAttr("process_image_for_analysis", imageArray)
                
                // Calculate processing time
                val processingTime = System.currentTimeMillis() - startTime
                
                // Return the result as a string
                ToastUtils.showToast("AI analysis complete (took ${processingTime/1000.0} seconds)")
                return result.toString()
            } catch (e: PyException) {
                Log.e(TAG, "Python error: ${e.message}")
                ToastUtils.showToast("Python error: ${e.message}")
                ToastUtils.showToast("Full Python error: ${e.stackTraceToString().substring(0, Math.min(e.stackTraceToString().length, 200))}...")
                return "Error analyzing image: ${e.message}"
            } catch (e: Exception) {
                Log.e(TAG, "Java error: ${e.message}")
                ToastUtils.showToast("Java error: ${e.message}")
                ToastUtils.showToast("Error details: ${e.javaClass.simpleName} - ${e.stackTraceToString().substring(0, Math.min(e.stackTraceToString().length, 200))}...")
                return "Error: ${e.message}"
            }
        }
    }
}