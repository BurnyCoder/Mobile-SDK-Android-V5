package dji.sampleV5.aircraft.util

import android.graphics.Bitmap
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(ContextUtil.getContext()))
                    }
                    initialized = true
                    Log.i(TAG, "Python environment initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Python environment: ${e.message}")
                }
            }
        }

        /**
         * Analyze an image using OpenAI through the Python script
         * @param bitmap The image to analyze
         * @return The analysis result as a string
         */
        fun analyzeImageWithOpenAI(bitmap: Bitmap): String {
            if (!initialized) {
                initialize()
            }

            try {
                // Get the Python instance
                val py = Python.getInstance()
                
                // Import our ai_processing module
                val aiProcessingModule = py.getModule("ai_processing")
                
                // Convert Bitmap to a format Python can use (NumPy array)
                val bitmapModule = py.getModule("bitmaputils")
                val imageArray: PyObject = bitmapModule.callAttr("bitmap_to_cv2", bitmap)
                
                // Call the Python function for image analysis
                val result: PyObject = aiProcessingModule.callAttr("process_image_for_analysis", imageArray)
                
                // Return the result as a string
                return result.toString()
            } catch (e: PyException) {
                Log.e(TAG, "Python error: ${e.message}")
                return "Error analyzing image: ${e.message}"
            } catch (e: Exception) {
                Log.e(TAG, "Java error: ${e.message}")
                return "Error: ${e.message}"
            }
        }
    }
}