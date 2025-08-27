package com.koreantranslator.service

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.koreantranslator.BuildConfig
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationResponse
import com.koreantranslator.util.DebugLogFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MLKitTranslationService @Inject constructor() {
    
    companion object {
        private const val TAG = "MLKitTranslation"
    }
    
    init {
        // OPTIMIZATION: Configure ML Kit for reduced logging in debug builds
        if (BuildConfig.DEBUG) {
            optimizeMLKitLogging()
        }
    }
    
    // Create translator options for Korean to English
    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.KOREAN)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()
    
    // Get the translator instance
    private val translator: Translator = Translation.getClient(translatorOptions)
    
    /**
     * OPTIMIZATION: Configure ML Kit to reduce excessive logging
     */
    private fun optimizeMLKitLogging() {
        try {
            // Set system properties to reduce ML Kit internal logging
            System.setProperty("com.google.mlkit.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.vision.logging.level", "ERROR")
            System.setProperty("com.google.mlkit.common.logging.level", "ERROR")
            
            DebugLogFilter.d(TAG, "✓ ML Kit logging optimization applied")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to optimize ML Kit logging", e)
        }
    }
    
    // Track model download state
    @Volatile
    private var isModelDownloading = false
    
    @Volatile
    private var modelDownloadAttempts = 0
    
    private val maxDownloadAttempts = 3
    
    // Common ML Kit mistranslations and their corrections
    // These are patterns we know ML Kit consistently gets wrong
    private val ML_KIT_CORRECTIONS = mapOf(
        // Critical: ML Kit confuses 바쁘다 (busy) with 예쁘다 (pretty)
        "pretty" to mapOf(
            "바쁘" to "busy",
            "바쁘거든요" to "busy",
            "바쁘거든" to "busy",
            "바빠" to "busy",
            "바빠요" to "busy",
            "바쁩니다" to "busy"
        ),
        "I'm pretty" to mapOf(
            "바쁘거든요" to "I'm busy (you see)",
            "바빠요" to "I'm busy",
            "바쁩니다" to "I'm busy",
            "지금 바쁘거든요" to "I'm busy right now",
            "지금 바빠요" to "I'm busy right now"
        ),
        // ENHANCED: Comprehensive Korean particle mistranslations
        "I did not" to mapOf(
            "제가 그랬잖아요" to "I told you I did that",
            "제가그랬잖아요" to "I told you I did that",
            "제가그랬잖아요잖아요" to "I told you I did that",  // Contaminated versions
            "내가 그랬잖아" to "I told you I did that",
            "제가 했잖아요" to "I told you I did it",
            "내가 했잖아" to "I told you I did it",
            "그랬잖아요" to "I told you that's how it was"
        ),
        "I didn't" to mapOf(
            "제가 그랬잖아요" to "I told you I did that",
            "제가그랬잖아요" to "I told you I did that",
            "제가그랬잖아요잖아요" to "I told you I did that",  // Contaminated versions
            "내가 그랬잖아" to "I told you I did that",
            "제가 했잖아요" to "I told you I did it",
            "내가 했잖아" to "I told you I did it"
        ),
        "You. I did it." to mapOf(  // ML Kit's literal translation
            "제가그랬잖아요잖아요 제 가 그 랬" to "I told you I did that",
            "제가그랬잖아요잖아요" to "I told you I did that"
        ),
        "It is not" to mapOf(
            "아니거든요" to "Actually, no",
            "아니거든" to "Actually, no"
        ),
        "How do I" to mapOf(
            "어떻게 해요" to "What should I do",
            "어떡해" to "What should I do"
        ),
        "What is it" to mapOf(
            "뭐예요" to "What is it",
            "뭐야" to "What is it"
        )
    )
    
    // ENHANCED: Pattern-based corrections for ML Kit with comprehensive Korean particle handling
    private val PATTERN_CORRECTIONS = listOf(
        // Fix negation errors with -잖아요 (most critical fix)
        CorrectionPattern(
            errorPattern = Regex("(did not|didn't|don't|doesn't|not)"),
            koreanPattern = Regex(".*(잖아요|잖아).*"),
            correction = { korean, english ->
                when {
                    korean.contains("그랬") -> "I told you I did that"
                    korean.contains("했") -> "I told you I did it" 
                    korean.contains("알") -> english.replace(Regex("(don't|doesn't)"), "know")
                    korean.contains("제가") -> "I told you"
                    else -> english.replace(Regex("(did not|didn't|not)"), "told you")
                }
            }
        ),
        // Fix literal translations that miss Korean nuance
        CorrectionPattern(
            errorPattern = Regex("You\\. I (did|said)"),
            koreanPattern = Regex(".*제가.*"),
            correction = { korean, english ->
                "I told you I did that"
            }
        ),
        // Fix question markers
        CorrectionPattern(
            errorPattern = Regex("^(What|How|Where|When|Why)\\s"),
            koreanPattern = Regex(".*(나요|가요|까요)$"),
            correction = { korean, english ->
                if (!english.endsWith("?")) "$english?" else english
            }
        )
    )
    
    data class CorrectionPattern(
        val errorPattern: Regex,
        val koreanPattern: Regex,
        val correction: (String, String) -> String
    )
    
    // Korean-English translation model for download management
    private val koreanEnglishModel = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
    private val modelManager = RemoteModelManager.getInstance()
    
    suspend fun translate(koreanText: String): TranslationResponse {
        return try {
            // CRITICAL FIX: Ensure model is available with proper cancellation handling
            ensureModelAvailable()
            
            // Perform the translation with timeout protection
            val translatedText = withContext(Dispatchers.IO) {
                try {
                    translator.translate(koreanText).await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Translation was cancelled, but ML Kit model is available")
                    throw Exception("Translation cancelled - retry needed")
                } catch (e: Exception) {
                    Log.e(TAG, "ML Kit translation error: ${e.message}")
                    throw e
                }
            }
            
            // Apply targeted corrections for known ML Kit errors
            val correctedText = if (needsCorrection(koreanText, translatedText)) {
                applyMLKitCorrections(koreanText, translatedText)
            } else {
                translatedText
            }
            
            DebugLogFilter.d(TAG, "ML Kit translation successful: '$koreanText' -> '$correctedText'")
            
            TranslationResponse(
                translatedText = correctedText,
                confidence = 0.75f, 
                engine = TranslationEngine.ML_KIT,
                isEnhanced = false
            )
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't wrap cancellation exceptions - let them propagate
            Log.w(TAG, "Translation cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translation failed: ${e.message}")
            throw Exception("ML Kit translation failed: ${e.message}")
        }
    }
    
    /**
     * ENHANCED: Ensure model is available with better error handling
     */
    private suspend fun ensureModelAvailable() {
        if (isModelDownloaded()) {
            DebugLogFilter.d(TAG, "ML Kit model already available")
            return
        }
        
        // Prevent concurrent downloads
        if (isModelDownloading) {
            Log.d(TAG, "Model download already in progress, waiting...")
            // Wait for ongoing download to complete
            while (isModelDownloading && modelDownloadAttempts < maxDownloadAttempts) {
                kotlinx.coroutines.delay(500)
            }
            
            if (!isModelDownloaded()) {
                throw Exception("Model download failed after waiting")
            }
            return
        }
        
        // Start download
        isModelDownloading = true
        modelDownloadAttempts++
        
        try {
            val downloaded = downloadLanguageModelWithRetry()
            if (!downloaded) {
                throw Exception("Failed to download translation model after $modelDownloadAttempts attempts")
            }
            Log.d(TAG, "Model download completed successfully")
        } finally {
            isModelDownloading = false
        }
    }
    
    /**
     * Check if ML Kit translation needs correction
     */
    private fun needsCorrection(koreanText: String, translation: String): Boolean {
        // Check for critical mistranslations
        // 1. 바쁘다 being translated as "pretty"
        if (koreanText.contains("바쁘") || koreanText.contains("바빠")) {
            if (translation.contains("pretty", ignoreCase = true)) {
                return true
            }
        }
        
        // 2. -잖아요 being translated as negation
        if (koreanText.contains("잖아요") || koreanText.contains("잖아")) {
            if (translation.contains("did not", ignoreCase = true) || 
                translation.contains("didn't", ignoreCase = true)) {
                return true
            }
        }
        
        // 3. -거든요 missing explanatory tone
        if (koreanText.contains("거든요") || koreanText.contains("거든")) {
            // Check if translation lacks explanatory markers
            if (!translation.contains("you see", ignoreCase = true) && 
                !translation.contains("actually", ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Apply corrections for known ML Kit translation errors
     */
    private fun applyMLKitCorrections(koreanText: String, mlKitTranslation: String): String {
        var correctedTranslation = mlKitTranslation
        
        // Check for exact phrase corrections
        for ((errorPhrase, koreanToCorrection) in ML_KIT_CORRECTIONS) {
            if (mlKitTranslation.contains(errorPhrase, ignoreCase = true)) {
                // Check if the Korean text matches any of the patterns for this error
                for ((koreanPattern, correction) in koreanToCorrection) {
                    if (koreanText == koreanPattern) {
                        correctedTranslation = mlKitTranslation.replace(
                            errorPhrase, 
                            correction, 
                            ignoreCase = true
                        )
                        android.util.Log.d("MLKitTranslation", 
                            "Applied correction: '$errorPhrase' -> '$correction' for Korean: '$koreanPattern'")
                        break
                    }
                }
            }
        }
        
        // Apply pattern-based corrections
        for (pattern in PATTERN_CORRECTIONS) {
            if (pattern.koreanPattern.matches(koreanText) && 
                pattern.errorPattern.containsMatchIn(correctedTranslation)) {
                val beforeCorrection = correctedTranslation
                correctedTranslation = pattern.correction(koreanText, correctedTranslation)
                if (beforeCorrection != correctedTranslation) {
                    android.util.Log.d("MLKitTranslation", 
                        "Applied pattern correction: '$beforeCorrection' -> '$correctedTranslation'")
                }
            }
        }
        
        // ENHANCED: Special handling for all variations of our problematic patterns
        val problematicPatterns = listOf(
            "제가 그랬잖아요",
            "제가그랬잖아요",
            "제가그랬잖아요잖아요",
            "제가그랬잖아요잖아요 제 가 그 랬",
            "제가그랬"
        )
        
        for (pattern in problematicPatterns) {
            if (koreanText.contains(pattern) && 
                (correctedTranslation.contains("did not", ignoreCase = true) || 
                 correctedTranslation.contains("didn't", ignoreCase = true) ||
                 correctedTranslation.contains("not.", ignoreCase = true) ||
                 correctedTranslation.contains("You. I did it.", ignoreCase = true))) {
                correctedTranslation = "I told you I did that"
                Log.d(TAG, "Applied pattern correction: '$correctedTranslation' -> 'I said/did that.'")
                break
            }
        }
        
        return correctedTranslation
    }
    
    /**
     * ENHANCED: Download model with retry logic and proper cancellation handling
     */
    private suspend fun downloadLanguageModelWithRetry(): Boolean {
        repeat(maxDownloadAttempts) { attempt ->
            try {
                Log.d(TAG, "Attempting model download (attempt ${attempt + 1}/$maxDownloadAttempts)")
                
                // Set download conditions - allow any network to ensure model availability
                val conditions = DownloadConditions.Builder()
                    // Removed WiFi requirement to allow download on any network
                    // Model is ~50MB, acceptable for mobile data
                    .build()
                
                // Download the Korean model with timeout
                withTimeout(30000) { // 30 second timeout
                    translator.downloadModelIfNeeded(conditions).await()
                }
                
                Log.d(TAG, "Korean model download completed successfully")
                return true
                
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Model download timeout on attempt ${attempt + 1}")
                if (attempt < maxDownloadAttempts - 1) {
                    delay(2000) // Wait 2 seconds before retry
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Model download cancelled")
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed on attempt ${attempt + 1}: ${e.message}")
                if (attempt < maxDownloadAttempts - 1) {
                    delay(2000) // Wait 2 seconds before retry
                }
            }
        }
        
        Log.e(TAG, "Failed to download model after $maxDownloadAttempts attempts")
        return false
    }
    
    suspend fun downloadLanguageModel(): Boolean {
        return downloadLanguageModelWithRetry()
    }
    
    suspend fun isModelDownloaded(): Boolean {
        return try {
            // Check if Korean-English model is downloaded
            val downloadedModels = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            downloadedModels.any { model ->
                model.language == TranslateLanguage.KOREAN
            }
        } catch (e: Exception) {
            false
        }
    }
    
    // Clean up translator when service is destroyed
    fun cleanup() {
        translator.close()
    }
}