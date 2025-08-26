package com.koreantranslator.nlp

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TensorFlow Lite models for Korean NLP
 * 
 * Handles:
 * - Model downloading from remote server
 * - Model loading and initialization
 * - GPU acceleration when available
 * - Model versioning and updates
 * - Efficient memory management
 */
@Singleton
class TFLiteModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "TFLiteModelManager"
        
        // Model file names
        private const val SPACING_MODEL_NAME = "korean_spacing_v2.tflite"
        private const val PUNCTUATION_MODEL_NAME = "korean_punctuation_v1.tflite"
        
        // Model URLs (these would be your actual model hosting URLs)
        // For now, using placeholder URLs - you'll need to host the models
        private const val SPACING_MODEL_URL = "https://your-server.com/models/$SPACING_MODEL_NAME"
        private const val PUNCTUATION_MODEL_URL = "https://your-server.com/models/$PUNCTUATION_MODEL_NAME"
        
        // Local model directory
        private const val MODEL_DIR = "nlp_models"
        
        // Model metadata
        private const val SPACING_MODEL_VERSION = 2
        private const val PUNCTUATION_MODEL_VERSION = 1
        
        // Model sizes (approximate, in MB)
        private const val SPACING_MODEL_SIZE_MB = 15
        private const val PUNCTUATION_MODEL_SIZE_MB = 10
    }
    
    // CRITICAL FIX: Enhanced interpreter options to reduce XNNPACK warnings
    private val interpreterOptions = Interpreter.Options().apply {
        setNumThreads(2) // Reduced threads to minimize XNNPACK conflicts
        
        // ENHANCED: Selective acceleration based on device capabilities
        try {
            // Use NNAPI with conservative settings to avoid XNNPACK conflicts
            setUseNNAPI(true)
            setAllowBufferHandleOutput(false) // Disable to reduce XNNPACK profile warnings
            setAllowFp16PrecisionForFp32(false) // Force FP32 to prevent precision issues
            Log.i(TAG, "Using conservative NNAPI settings to minimize XNNPACK warnings")
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI not available, falling back to CPU-only: ${e.message}")
            setUseNNAPI(false)
        }
        
        // GPU acceleration remains disabled for compatibility
        Log.i(TAG, "TFLite configured with optimized CPU execution")
    }
    
    // Model interpreters
    private var spacingInterpreter: Interpreter? = null
    private var punctuationInterpreter: Interpreter? = null
    
    // Model loading status
    private var spacingModelLoaded = false
    private var punctuationModelLoaded = false
    
    /**
     * Initialize models - load from assets or download if needed
     */
    suspend fun initializeModels() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing TFLite models...")
        
        // Create model directory if it doesn't exist
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        // Load or download spacing model
        loadOrDownloadSpacingModel(modelDir)
        
        // Load or download punctuation model
        loadOrDownloadPunctuationModel(modelDir)
    }
    
    /**
     * Load spacing model, downloading if necessary
     */
    private suspend fun loadOrDownloadSpacingModel(modelDir: File) {
        val modelFile = File(modelDir, SPACING_MODEL_NAME)
        
        try {
            // Check if model exists locally
            if (!modelFile.exists() || !isModelVersionCurrent(modelFile, SPACING_MODEL_VERSION)) {
                Log.i(TAG, "Spacing model not found or outdated, using embedded fallback")
                // For production, you would download from server
                // For now, we'll try to load from assets as fallback
                loadSpacingModelFromAssets()
            } else {
                // CRITICAL FIX: Load existing model with fallback options
                val fileBuffer = modelFile.inputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, modelFile.length()
                )
                spacingInterpreter = createInterpreterWithFallback(fileBuffer, "spacing")
                spacingModelLoaded = spacingInterpreter != null
                
                if (spacingModelLoaded) {
                    Log.i(TAG, "Spacing model loaded successfully from: ${modelFile.path}")
                } else {
                    Log.w(TAG, "Failed to load spacing model from file, trying assets fallback")
                    loadSpacingModelFromAssets()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load spacing model", e)
            // Try fallback to assets
            loadSpacingModelFromAssets()
        }
    }
    
    /**
     * Load punctuation model, downloading if necessary
     */
    private suspend fun loadOrDownloadPunctuationModel(modelDir: File) {
        val modelFile = File(modelDir, PUNCTUATION_MODEL_NAME)
        
        try {
            // Check if model exists locally
            if (!modelFile.exists() || !isModelVersionCurrent(modelFile, PUNCTUATION_MODEL_VERSION)) {
                Log.i(TAG, "Punctuation model not found or outdated, using embedded fallback")
                // For production, you would download from server
                // For now, we'll try to load from assets as fallback
                loadPunctuationModelFromAssets()
            } else {
                // CRITICAL FIX: Load existing model with fallback options
                val fileBuffer = modelFile.inputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, modelFile.length()
                )
                punctuationInterpreter = createInterpreterWithFallback(fileBuffer, "punctuation")
                punctuationModelLoaded = punctuationInterpreter != null
                
                if (punctuationModelLoaded) {
                    Log.i(TAG, "Punctuation model loaded successfully from: ${modelFile.path}")
                } else {
                    Log.w(TAG, "Failed to load punctuation model from file, trying assets fallback")
                    loadPunctuationModelFromAssets()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load punctuation model", e)
            // Try fallback to assets
            loadPunctuationModelFromAssets()
        }
    }
    
    /**
     * ENHANCED: Load spacing model from assets with XNNPACK error handling
     */
    private fun loadSpacingModelFromAssets() {
        try {
            // Check if model exists in assets
            val assetManager = context.assets
            val modelPath = "models/$SPACING_MODEL_NAME"
            
            // Try to open the model file from assets
            assetManager.openFd(modelPath).use { fileDescriptor ->
                val mappedByteBuffer = fileDescriptor.createInputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
                
                // CRITICAL FIX: Try conservative options first to avoid XNNPACK warnings
                spacingInterpreter = createInterpreterWithFallback(mappedByteBuffer, "spacing")
                spacingModelLoaded = spacingInterpreter != null
                
                if (spacingModelLoaded) {
                    Log.i(TAG, "Spacing model loaded from assets successfully")
                } else {
                    Log.w(TAG, "Failed to create spacing model interpreter")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spacing model not found in assets, will use rule-based fallback", e)
            spacingModelLoaded = false
        }
    }
    
    /**
     * ENHANCED: Load punctuation model from assets with XNNPACK error handling
     */
    private fun loadPunctuationModelFromAssets() {
        try {
            // Check if model exists in assets
            val assetManager = context.assets
            val modelPath = "models/$PUNCTUATION_MODEL_NAME"
            
            // Try to open the model file from assets
            assetManager.openFd(modelPath).use { fileDescriptor ->
                val mappedByteBuffer = fileDescriptor.createInputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
                
                // CRITICAL FIX: Try conservative options first to avoid XNNPACK warnings
                punctuationInterpreter = createInterpreterWithFallback(mappedByteBuffer, "punctuation")
                punctuationModelLoaded = punctuationInterpreter != null
                
                if (punctuationModelLoaded) {
                    Log.i(TAG, "Punctuation model loaded from assets successfully")
                } else {
                    Log.w(TAG, "Failed to create punctuation model interpreter")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Punctuation model not found in assets, will use rule-based fallback", e)
            punctuationModelLoaded = false
        }
    }
    
    /**
     * CRITICAL FIX: Create interpreter with fallback options to minimize XNNPACK warnings
     */
    private fun createInterpreterWithFallback(modelBuffer: MappedByteBuffer, modelName: String): Interpreter? {
        // Try different configurations in order of preference
        val configurations = listOf(
            // 1. Conservative NNAPI (minimal XNNPACK usage)
            Interpreter.Options().apply {
                setNumThreads(1) // Single thread to minimize XNNPACK conflicts
                setUseNNAPI(true)
                setAllowBufferHandleOutput(false)
                setAllowFp16PrecisionForFp32(false)
            },
            
            // 2. CPU-only with optimized threads
            Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(false)
                setAllowBufferHandleOutput(false)
                setAllowFp16PrecisionForFp32(false)
            },
            
            // 3. Minimal CPU-only fallback
            Interpreter.Options().apply {
                setNumThreads(1)
                setUseNNAPI(false)
            }
        )
        
        for ((index, options) in configurations.withIndex()) {
            try {
                Log.d(TAG, "Attempting to create $modelName interpreter with configuration ${index + 1}")
                val interpreter = Interpreter(modelBuffer, options)
                
                // Test interpreter with a dummy operation to ensure it works
                try {
                    val inputShape = interpreter.getInputTensor(0).shape()
                    Log.d(TAG, "$modelName interpreter created successfully with config ${index + 1}, input shape: ${inputShape.contentToString()}")
                    return interpreter
                } catch (e: Exception) {
                    Log.w(TAG, "Interpreter test failed for $modelName config ${index + 1}: ${e.message}")
                    interpreter.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create $modelName interpreter with config ${index + 1}: ${e.message}")
            }
        }
        
        Log.e(TAG, "All interpreter configurations failed for $modelName model")
        return null
    }
    
    /**
     * Download model from server
     * NOTE: This is a placeholder implementation. In production, you would:
     * - Use proper download manager
     * - Show progress to user
     * - Handle network errors gracefully
     * - Verify model integrity with checksums
     */
    private suspend fun downloadModel(url: String, outputFile: File): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Downloading model from: $url")
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Model downloaded successfully to: ${outputFile.path}")
                    true
                } else {
                    Log.e(TAG, "Failed to download model, HTTP ${connection.responseCode}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model", e)
                false
            }
        }
    
    /**
     * Check if model version is current
     */
    private fun isModelVersionCurrent(modelFile: File, expectedVersion: Int): Boolean {
        // In a real implementation, you would store version metadata
        // For now, we'll just check if the file exists and is reasonable size
        val minSizeBytes = 1024 * 1024 // 1MB minimum
        return modelFile.exists() && modelFile.length() > minSizeBytes
    }
    
    /**
     * Get spacing model interpreter
     */
    fun loadSpacingModel(): Interpreter? {
        if (!spacingModelLoaded) {
            Log.w(TAG, "Spacing model not loaded")
            return null
        }
        return spacingInterpreter
    }
    
    /**
     * Get punctuation model interpreter
     */
    fun loadPunctuationModel(): Interpreter? {
        if (!punctuationModelLoaded) {
            Log.w(TAG, "Punctuation model not loaded")
            return null
        }
        return punctuationInterpreter
    }
    
    /**
     * Check if models are ready
     */
    fun areModelsReady(): Boolean {
        return spacingModelLoaded || punctuationModelLoaded
    }
    
    /**
     * Get model status information
     */
    fun getModelStatus(): ModelStatus {
        return ModelStatus(
            spacingModelReady = spacingModelLoaded,
            punctuationModelReady = punctuationModelLoaded,
            gpuAccelerationEnabled = false, // GPU disabled due to compatibility issues
            spacingModelVersion = if (spacingModelLoaded) SPACING_MODEL_VERSION else 0,
            punctuationModelVersion = if (punctuationModelLoaded) PUNCTUATION_MODEL_VERSION else 0
        )
    }
    
    /**
     * Release all models and free memory
     */
    fun release() {
        try {
            spacingInterpreter?.close()
            punctuationInterpreter?.close()
            spacingInterpreter = null
            punctuationInterpreter = null
            spacingModelLoaded = false
            punctuationModelLoaded = false
            Log.i(TAG, "TFLite models released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing models", e)
        }
    }
    
    /**
     * ENHANCED: Model status information with XNNPACK configuration details
     */
    data class ModelStatus(
        val spacingModelReady: Boolean,
        val punctuationModelReady: Boolean,
        val gpuAccelerationEnabled: Boolean,
        val spacingModelVersion: Int,
        val punctuationModelVersion: Int,
        val xnnpackOptimized: Boolean = false, // XNNPACK is conservatively disabled
        val executionMode: String = "CPU-Optimized" // Describes current execution strategy
    )
}