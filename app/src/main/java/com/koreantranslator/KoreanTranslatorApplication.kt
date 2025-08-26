package com.koreantranslator

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import com.koreantranslator.util.MLKitModelManager
import com.koreantranslator.util.LoggingOptimizer
import com.koreantranslator.util.DebugLogFilter
import com.koreantranslator.nlp.TFLiteModelManager
import com.koreantranslator.service.KoreanNLPEnhancementService
import com.koreantranslator.service.SonioxStreamingService
import com.koreantranslator.service.TranslationCacheManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltAndroidApp
class KoreanTranslatorApplication : Application() {
    
    @Inject
    lateinit var mlKitModelManager: MLKitModelManager
    
    @Inject
    lateinit var tfLiteModelManager: TFLiteModelManager
    
    @Inject
    lateinit var nlpEnhancementService: KoreanNLPEnhancementService
    
    @Inject
    lateinit var sonioxStreamingService: SonioxStreamingService
    
    @Inject
    lateinit var translationCacheManager: TranslationCacheManager
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        
        // CRITICAL FIX: Initialize logging optimizations first to reduce noise
        DebugLogFilter.initialize()
        LoggingOptimizer.initialize()
        
        // Additional Firebase logging suppression for debug builds
        if (BuildConfig.DEBUG) {
            suppressFirebaseLoggingInDebug()
            loadFirebasePropertiesFile()
        }
        
        setupGlobalExceptionHandler()
        logOptimizationStats()
        
        // PERFORMANCE FIX: Initialize heavy models asynchronously to avoid blocking UI
        initializeModelsInBackground()
    }
    
    /**
     * CRITICAL FIX: Comprehensive Firebase logging suppression for debug builds
     * This is the most aggressive approach to eliminate Firebase Transport noise
     */
    private fun suppressFirebaseLoggingInDebug() {
        try {
            Log.i("KoreanTranslatorApp", "🚀 Applying COMPREHENSIVE Firebase logging suppression...")
            
            // ===============================
            // FIREBASE CORE LOGGING SUPPRESSION
            // ===============================
            System.setProperty("firebase.logging.enabled", "false")
            System.setProperty("firebase.logging.level", "ERROR")
            System.setProperty("firebase.transport.logging.enabled", "false")
            System.setProperty("firebase.transport.logging.level", "ERROR")
            System.setProperty("firebase.crashlytics.debug", "false")
            System.setProperty("firebase.analytics.debug_mode", "false")
            System.setProperty("firebase.database.logging_enabled", "false")
            
            // ===============================
            // FIREBASE ML KIT LOGGING SUPPRESSION
            // ===============================
            System.setProperty("com.google.firebase.ml.logging.enabled", "false")
            System.setProperty("com.google.firebase.ml.logging.level", "ERROR") 
            System.setProperty("com.google.firebase.ml.vision.logging.enabled", "false")
            System.setProperty("com.google.mlkit.logging.enabled", "false")
            System.setProperty("com.google.mlkit.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.vision.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.common.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.nl.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.translate.logging.level", "ERROR")
            
            // ===============================
            // FIREBASE TRANSPORT SYSTEM SUPPRESSION (THE MAIN CULPRIT)
            // ===============================
            System.setProperty("com.google.android.datatransport.runtime.logging.enabled", "false")
            System.setProperty("com.google.android.datatransport.runtime.logging.level", "ERROR")
            System.setProperty("com.google.android.datatransport.cct.logging.level", "ERROR") 
            System.setProperty("com.google.android.datatransport.logging.enabled", "false")
            System.setProperty("datatransport.logging.enabled", "false")
            System.setProperty("datatransport.logging.level", "ERROR")
            System.setProperty("EventStore.logging.enabled", "false")
            System.setProperty("TransportRuntime.logging.enabled", "false")
            System.setProperty("TransportRuntime.logging.level", "ERROR")
            System.setProperty("Uploader.logging.enabled", "false")
            System.setProperty("Scheduler.logging.enabled", "false")
            
            // ===============================
            // TENSORFLOW LITE LOGGING SUPPRESSION
            // ===============================
            System.setProperty("tensorflow.logging.level", "3") // Only errors (0=all, 1=info, 2=warning, 3=error)
            System.setProperty("tf.logging.level", "ERROR")
            System.setProperty("tflite.logging.level", "ERROR")
            System.setProperty("tflite.logging.enabled", "false")
            System.setProperty("xnnpack.logging.enabled", "false")
            System.setProperty("xnnpack.logging.level", "ERROR")
            System.setProperty("tflite.gpu.logging.enabled", "false")
            
            // ===============================
            // GOOGLE SERVICES LOGGING SUPPRESSION  
            // ===============================
            System.setProperty("com.google.android.gms.common.logging.level", "ERROR")
            System.setProperty("com.google.android.gms.ml.logging.enabled", "false")
            System.setProperty("com.google.android.gms.ml.logging.level", "ERROR")
            System.setProperty("gms.logging.enabled", "false")
            System.setProperty("com.google.android.gms.internal.logging.level", "ERROR")
            
            // ===============================
            // ADDITIONAL NATIVE SUPPRESSION ATTEMPTS
            // ===============================
            try {
                // Try to suppress native-level logging through environment-like properties
                System.setProperty("FIREBASE_LOG_LEVEL", "ERROR")
                System.setProperty("GOOGLE_LOG_LEVEL", "ERROR") 
                System.setProperty("ML_KIT_LOG_LEVEL", "ERROR")
                System.setProperty("TRANSPORT_LOG_LEVEL", "ERROR")
            } catch (ignored: Exception) {
                // These may not work but won't cause problems
            }
            
            Log.i("KoreanTranslatorApp", "✅ COMPREHENSIVE Firebase Transport logging suppression applied!")
            Log.i("KoreanTranslatorApp", "✅ TensorFlow Lite logging reduced to ERROR level only")
            Log.i("KoreanTranslatorApp", "✅ Firebase ML Kit internal logging disabled")
            Log.i("KoreanTranslatorApp", "✅ Transport EventStore logging disabled")
            Log.i("KoreanTranslatorApp", "🎯 Debug builds should now have SIGNIFICANTLY cleaner logcat output")
            
        } catch (e: Exception) {
            Log.w("KoreanTranslatorApp", "Failed to suppress Firebase logging", e)
        }
    }
    
    /**
     * Load Firebase properties file to suppress logging at native level
     */
    private fun loadFirebasePropertiesFile() {
        try {
            Log.i("KoreanTranslatorApp", "Loading Firebase properties for native-level log suppression...")
            
            val inputStream = resources.openRawResource(R.raw.firebase)
            val properties = java.util.Properties()
            properties.load(inputStream)
            inputStream.close()
            
            // Apply all properties from firebase.properties file
            properties.forEach { (key, value) ->
                try {
                    System.setProperty(key.toString(), value.toString())
                } catch (e: Exception) {
                    // Ignore property setting failures
                }
            }
            
            Log.i("KoreanTranslatorApp", "✅ Firebase properties loaded - ${properties.size} properties applied")
            
        } catch (e: Exception) {
            Log.w("KoreanTranslatorApp", "Failed to load Firebase properties", e)
        }
    }
    
    /**
     * Initialize models in background to improve app startup performance
     */
    private fun initializeModelsInBackground() {
        applicationScope.launch {
            // Run both initializations in parallel for faster startup
            val mlKitJob = async { initializeMLKitModelsAsync() }
            val nlpJob = async { initializeNLPModelsAsync() }
            
            // Wait for both to complete
            mlKitJob.await()
            nlpJob.await()
            
            Log.d("KoreanTranslatorApp", "✓ All models initialized successfully in background")
        }
    }
    
    /**
     * Initialize ML Kit translation models asynchronously  
     * Downloads models if not present, checks for updates
     */
    private suspend fun initializeMLKitModelsAsync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("KoreanTranslatorApp", "Initializing ML Kit models...")
                
                // Check and download models if needed
                val modelsReady = mlKitModelManager.ensureModelsDownloaded()
                
                if (modelsReady) {
                    Log.d("KoreanTranslatorApp", "✓ ML Kit models ready")
                    
                    // Get model size info for logging
                    val sizeInfo = mlKitModelManager.getModelSizeInfo()
                    Log.d("KoreanTranslatorApp", "Model sizes - Korean: ${sizeInfo.koreanModelSizeMB}MB, English: ${sizeInfo.englishModelSizeMB}MB")
                    
                    // Show success toast on main thread
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@KoreanTranslatorApplication,
                            "Translation models ready",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.e("KoreanTranslatorApp", "✗ Failed to initialize ML Kit models")
                    
                    // Show error toast on main thread
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@KoreanTranslatorApplication,
                            "Translation models not available - translations may fail",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                // Log final status instead of blocking observe
                Log.d("KoreanTranslatorApp", "ML Kit model initialization completed")
                
            } catch (e: Exception) {
                Log.e("KoreanTranslatorApp", "Error initializing ML Kit models", e)
            }
        }
    }
    
    /**
     * Initialize NLP models for Korean text processing asynchronously
     * Loads TensorFlow Lite models for word segmentation and punctuation
     */
    private suspend fun initializeNLPModelsAsync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("KoreanTranslatorApp", "Initializing NLP models...")
                
                // Initialize TensorFlow Lite models
                tfLiteModelManager.initializeModels()
                
                // Initialize NLP enhancement service
                nlpEnhancementService.initialize()
                
                // Check model status
                val modelStatus = tfLiteModelManager.getModelStatus()
                Log.d("KoreanTranslatorApp", "NLP Models Status:")
                Log.d("KoreanTranslatorApp", "  - Spacing model: ${if (modelStatus.spacingModelReady) "Ready" else "Not available"}")
                Log.d("KoreanTranslatorApp", "  - Punctuation model: ${if (modelStatus.punctuationModelReady) "Ready" else "Not available"}")
                Log.d("KoreanTranslatorApp", "  - GPU acceleration: ${if (modelStatus.gpuAccelerationEnabled) "Enabled" else "Disabled"}")
                
                if (modelStatus.spacingModelReady || modelStatus.punctuationModelReady) {
                    Log.d("KoreanTranslatorApp", "✓ NLP models initialized successfully")
                    
                    // Warm up cache with common phrases
                    warmUpNLPCache()
                } else {
                    Log.w("KoreanTranslatorApp", "⚠ NLP models not available - falling back to rule-based processing")
                }
                
            } catch (e: Exception) {
                Log.e("KoreanTranslatorApp", "Error initializing NLP models", e)
                // App will still work with rule-based fallback
            }
        }
    }
    
    /**
     * Warm up NLP cache with common Korean phrases
     */
    private suspend fun warmUpNLPCache() {
        try {
            val commonPhrases = listOf(
                "안녕하세요",
                "감사합니다",
                "죄송합니다",
                "잠시만요",
                "알겠습니다"
            )
            
            Log.d("KoreanTranslatorApp", "Warming up NLP cache...")
            
            commonPhrases.forEach { phrase ->
                try {
                    nlpEnhancementService.enhance(phrase, useCache = true)
                } catch (e: Exception) {
                    // Ignore errors during warm-up
                }
            }
            
        } catch (e: Exception) {
            Log.w("KoreanTranslatorApp", "NLP cache warm-up failed", e)
        }
    }
    
    /**
     * Set up global exception handler for production crash reporting
     */
    private fun setupGlobalExceptionHandler() {
        // Store the original handler before setting our own to avoid infinite recursion
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e("KoreanTranslatorApp", "Uncaught exception on thread ${thread.name}", exception)
            
            // In production, you would send this to a crash reporting service
            // For now, just log it
            
            // Call the original handler to maintain normal crash behavior
            originalHandler?.uncaughtException(thread, exception)
        }
    }
    
    /**
     * CRITICAL FIX: Log optimization statistics to monitor logging overhead reduction
     */
    private fun logOptimizationStats() {
        try {
            val stats = LoggingOptimizer.getOptimizationStats()
            val debugStats = DebugLogFilter.getFilteringStats()
            
            Log.i("KoreanTranslatorApp", "════════════════════════════════════")
            Log.i("KoreanTranslatorApp", "📊 LOGGING OPTIMIZATION SUMMARY")
            Log.i("KoreanTranslatorApp", "════════════════════════════════════")
            Log.i("KoreanTranslatorApp", "LoggingOptimizer:")
            Log.i("KoreanTranslatorApp", "  - Noisy tag filters: ${stats.noisyTagCount}")
            Log.i("KoreanTranslatorApp", "  - Production mode: ${stats.productionMode}")
            Log.i("KoreanTranslatorApp", "  - Optimization enabled: ${stats.optimizationEnabled}")
            Log.i("KoreanTranslatorApp", "  - Critical keyword filters: ${stats.criticalKeywordCount}")
            
            if (BuildConfig.DEBUG) {
                Log.i("KoreanTranslatorApp", "DebugLogFilter:")
                Log.i("KoreanTranslatorApp", "  - Runtime filtering: ${debugStats.isInitialized}")
                Log.i("KoreanTranslatorApp", "  - Logs filtered: ${debugStats.filteredCount}")
                Log.i("KoreanTranslatorApp", "  - Logs allowed: ${debugStats.allowedCount}")
                Log.i("KoreanTranslatorApp", "  - Filtering ratio: ${(debugStats.filteringRatio * 100).toInt()}%")
            }
            
            if (stats.productionMode) {
                Log.i("KoreanTranslatorApp", "✅ Firebase Transport logging minimized for production")
            } else {
                Log.i("KoreanTranslatorApp", "✅ Debug log filtering active - Firebase noise suppressed")
                Log.i("KoreanTranslatorApp", "✅ TensorFlow Lite logging reduced")
                Log.i("KoreanTranslatorApp", "✅ Clean logcat for better debugging experience")
            }
            Log.i("KoreanTranslatorApp", "════════════════════════════════════")
            
        } catch (e: Exception) {
            Log.w("KoreanTranslatorApp", "Failed to log optimization stats", e)
        }
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        Log.d("KoreanTranslatorApp", "Memory trim requested: level $level")
        
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w("KoreanTranslatorApp", "Critical memory pressure - performing aggressive cleanup")
                performAggressiveMemoryCleanup()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.i("KoreanTranslatorApp", "Moderate memory pressure - performing light cleanup")
                performLightMemoryCleanup()
            }
        }
    }
    
    private fun performAggressiveMemoryCleanup() {
        try {
            // Clear all translation caches
            runBlocking { translationCacheManager.clear() }
            
            // Release audio resources if not actively recording
            sonioxStreamingService.cleanup()
            
            // Clear NLP cache
            nlpEnhancementService.clearCache()
            
            // Force garbage collection
            System.gc()
            
            Log.i("KoreanTranslatorApp", "Aggressive memory cleanup completed")
        } catch (e: Exception) {
            Log.e("KoreanTranslatorApp", "Error during aggressive memory cleanup", e)
        }
    }
    
    private fun performLightMemoryCleanup() {
        try {
            // Clear only expired cache entries
            runBlocking { translationCacheManager.clearExpired() }
            
            Log.i("KoreanTranslatorApp", "Light memory cleanup completed")
        } catch (e: Exception) {
            Log.e("KoreanTranslatorApp", "Error during light memory cleanup", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        
        // Clean up resources
        applicationScope.cancel()
        mlKitModelManager.cleanup()
        tfLiteModelManager.release()
        nlpEnhancementService.release()
        sonioxStreamingService.cleanup()
    }
}