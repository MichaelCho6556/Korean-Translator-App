package com.koreantranslator

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import com.koreantranslator.util.MLKitModelManager
import com.koreantranslator.util.LoggingOptimizer
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
        
        // CRITICAL FIX: Initialize only essential components synchronously
        LoggingOptimizer.initialize()
        setupGlobalExceptionHandler()
        logOptimizationStats()
        
        // PERFORMANCE FIX: Initialize heavy models asynchronously to avoid blocking UI
        initializeModelsInBackground()
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
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e("KoreanTranslatorApp", "Uncaught exception on thread ${thread.name}", exception)
            
            // In production, you would send this to a crash reporting service
            // For now, just log it
            
            // Call the default handler to maintain normal crash behavior
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, exception)
        }
    }
    
    /**
     * CRITICAL FIX: Log optimization statistics to monitor logging overhead reduction
     */
    private fun logOptimizationStats() {
        try {
            val stats = LoggingOptimizer.getOptimizationStats()
            Log.i("KoreanTranslatorApp", "Logging Optimization Stats:")
            Log.i("KoreanTranslatorApp", "  - Noisy tag filters: ${stats.noisyTagCount}")
            Log.i("KoreanTranslatorApp", "  - Production mode: ${stats.productionMode}")
            Log.i("KoreanTranslatorApp", "  - Optimization enabled: ${stats.optimizationEnabled}")
            Log.i("KoreanTranslatorApp", "  - Critical keyword filters: ${stats.criticalKeywordCount}")
            
            if (stats.productionMode) {
                Log.i("KoreanTranslatorApp", "✓ Firebase Transport logging minimized for production")
            } else {
                Log.i("KoreanTranslatorApp", "✓ Selective logging optimization enabled for debug")
            }
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