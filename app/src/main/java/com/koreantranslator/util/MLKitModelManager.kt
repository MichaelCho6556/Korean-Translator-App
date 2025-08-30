package com.koreantranslator.util

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-ready ML Kit Model Manager
 * Handles model download, verification, and lifecycle management
 */
@Singleton
class MLKitModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MLKitModelManager"
        private const val PREF_NAME = "ml_kit_models"
        private const val KEY_KOREAN_MODEL_VERSION = "korean_model_version"
        private const val KEY_ENGLISH_MODEL_VERSION = "english_model_version"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val MODEL_CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private val modelManager = RemoteModelManager.getInstance()
    private val koreanModel = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
    private val englishModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // Create translator for model management
    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.KOREAN)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()
    private val translator = Translation.getClient(translatorOptions)
    
    // Model download state
    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.IDLE)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()
    
    // Download progress (0-100)
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    
    sealed class ModelDownloadState {
        object IDLE : ModelDownloadState()
        object CHECKING : ModelDownloadState()
        data class DOWNLOADING(val progress: Int) : ModelDownloadState()
        object COMPLETED : ModelDownloadState()
        data class ERROR(val message: String) : ModelDownloadState()
    }
    
    /**
     * Initialize and ensure models are downloaded
     * This should be called on app startup
     */
    suspend fun ensureModelsDownloaded(): Boolean {
        _downloadState.value = ModelDownloadState.CHECKING
        Log.d(TAG, "Checking ML Kit models...")
        
        return try {
            // Check if models need updating
            if (shouldCheckForUpdates()) {
                Log.d(TAG, "Checking for model updates...")
                checkForModelUpdates()
            }
            
            // Check if models are already downloaded
            val modelsDownloaded = areModelsDownloaded()
            
            if (modelsDownloaded) {
                Log.d(TAG, "Models already downloaded")
                _downloadState.value = ModelDownloadState.COMPLETED
                _downloadProgress.value = 100
                return true
            }
            
            // Models not downloaded, start download
            Log.d(TAG, "Models not found, starting download...")
            downloadModelsWithRetry()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure models", e)
            _downloadState.value = ModelDownloadState.ERROR(e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Check if both Korean and English models are downloaded
     */
    suspend fun areModelsDownloaded(): Boolean {
        return try {
            val downloadedModels = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            val hasKorean = downloadedModels.any { it.language == TranslateLanguage.KOREAN }
            val hasEnglish = downloadedModels.any { it.language == TranslateLanguage.ENGLISH }
            
            Log.d(TAG, "Korean model downloaded: $hasKorean")
            Log.d(TAG, "English model downloaded: $hasEnglish")
            
            hasKorean && hasEnglish
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model status", e)
            false
        }
    }
    
    /**
     * Download models with retry logic and progress tracking
     */
    private suspend fun downloadModelsWithRetry(): Boolean {
        var attempts = 0
        var lastException: Exception? = null
        
        while (attempts < MAX_RETRY_ATTEMPTS) {
            try {
                attempts++
                Log.d(TAG, "Download attempt $attempts/$MAX_RETRY_ATTEMPTS")
                
                _downloadState.value = ModelDownloadState.DOWNLOADING(0)
                
                // Set download conditions - allow download on any network
                // In production, you might want to restrict to WiFi
                val conditions = DownloadConditions.Builder()
                    // Remove WiFi requirement for immediate download
                    // .requireWifi()
                    .build()
                
                // Start download with progress tracking
                _downloadProgress.value = 10
                Log.d(TAG, "Downloading Korean model...")
                
                // Download using translator (handles both models)
                translator.downloadModelIfNeeded(conditions).await()
                
                _downloadProgress.value = 50
                Log.d(TAG, "Korean model downloaded, verifying...")
                
                // Verify download
                if (areModelsDownloaded()) {
                    _downloadProgress.value = 100
                    _downloadState.value = ModelDownloadState.COMPLETED
                    
                    // Update preferences
                    prefs.edit()
                        .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                        .apply()
                    
                    Log.d(TAG, "✓ Models downloaded successfully")
                    return true
                } else {
                    throw Exception("Models not found after download")
                }
                
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Download attempt $attempts failed", e)
                
                if (attempts < MAX_RETRY_ATTEMPTS) {
                    Log.d(TAG, "Retrying in ${RETRY_DELAY_MS}ms...")
                    delay(RETRY_DELAY_MS * attempts) // Exponential backoff
                }
            }
        }
        
        // All attempts failed
        val errorMsg = "Failed to download models after $MAX_RETRY_ATTEMPTS attempts: ${lastException?.message}"
        Log.e(TAG, errorMsg)
        _downloadState.value = ModelDownloadState.ERROR(errorMsg)
        return false
    }
    
    /**
     * Delete downloaded models to free up space
     */
    suspend fun deleteModels() {
        try {
            Log.d(TAG, "Deleting ML Kit models...")
            
            modelManager.deleteDownloadedModel(koreanModel).await()
            modelManager.deleteDownloadedModel(englishModel).await()
            
            // Clear preferences
            prefs.edit().clear().apply()
            
            _downloadState.value = ModelDownloadState.IDLE
            _downloadProgress.value = 0
            
            Log.d(TAG, "Models deleted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete models", e)
        }
    }
    
    /**
     * Check if we should check for model updates
     */
    private fun shouldCheckForUpdates(): Boolean {
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastCheckTime) > MODEL_CHECK_INTERVAL
    }
    
    /**
     * Check for model updates (for future implementation)
     */
    private suspend fun checkForModelUpdates() {
        // In a production app, you might want to check for model updates
        // and download newer versions if available
        Log.d(TAG, "Checking for model updates...")
        
        // For now, just update the check time
        prefs.edit()
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Get model size information
     */
    suspend fun getModelSizeInfo(): ModelSizeInfo {
        return try {
            val downloadedModels = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            
            // Approximate sizes (actual sizes may vary)
            val koreanModelSize = if (downloadedModels.any { it.language == TranslateLanguage.KOREAN }) 30 else 0
            val englishModelSize = if (downloadedModels.any { it.language == TranslateLanguage.ENGLISH }) 30 else 0
            
            ModelSizeInfo(
                koreanModelSizeMB = koreanModelSize,
                englishModelSizeMB = englishModelSize,
                totalSizeMB = koreanModelSize + englishModelSize
            )
        } catch (e: Exception) {
            ModelSizeInfo(0, 0, 0)
        }
    }
    
    data class ModelSizeInfo(
        val koreanModelSizeMB: Int,
        val englishModelSizeMB: Int,
        val totalSizeMB: Int
    )
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        translator.close()
    }
}