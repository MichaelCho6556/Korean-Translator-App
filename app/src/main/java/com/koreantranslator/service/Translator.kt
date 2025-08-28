package com.koreantranslator.service

import com.koreantranslator.model.TranslationEngine

/**
 * Common interface for all translation engines (Gemini, ML Kit, etc.)
 * Provides a unified abstraction for the dual-engine translation system.
 */
interface Translator {
    
    /**
     * Translate Korean text to English with optional context
     */
    suspend fun translate(
        koreanText: String,
        context: List<String> = emptyList()
    ): TranslationResult
    
    /**
     * Check if this translator is currently available
     */
    suspend fun isAvailable(): Boolean
    
    /**
     * Get the engine type for this translator
     */
    fun getEngineType(): TranslationEngine
    
    /**
     * Get typical confidence threshold for this engine
     */
    fun getConfidenceThreshold(): Float
    
    /**
     * Get engine display name for UI
     */
    fun getDisplayName(): String
    
    /**
     * Result from a translation operation
     */
    data class TranslationResult(
        val translatedText: String,
        val confidence: Float,
        val processingTimeMs: Long,
        val modelVersion: String? = null,
        val wasEnhanced: Boolean = false,
        val usedContext: Boolean = false
    )
}

/**
 * Implementation for Gemini-powered translation
 */
class GeminiTranslatorImpl(
    private val geminiApiService: GeminiApiService
) : Translator {
    
    override suspend fun translate(
        koreanText: String,
        context: List<String>
    ): Translator.TranslationResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val response = if (context.isNotEmpty()) {
                // Use enhanced translation with context
                geminiApiService.enhanceTranslation(
                    originalKorean = koreanText,
                    basicTranslation = "", // Gemini will provide full translation
                    context = context
                )
            } else {
                // Direct translation
                geminiApiService.translate(koreanText)
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            
            Translator.TranslationResult(
                translatedText = response.translatedText,
                confidence = response.confidence,
                processingTimeMs = processingTime,
                modelVersion = response.modelInfo,
                wasEnhanced = context.isNotEmpty(),
                usedContext = context.isNotEmpty()
            )
        } catch (e: Exception) {
            throw TranslationException("Gemini translation failed", e)
        }
    }
    
    override suspend fun isAvailable(): Boolean {
        // Check if API key is configured and service is responsive
        return try {
            geminiApiService.testApiConnection()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getEngineType(): TranslationEngine = TranslationEngine.GEMINI_FLASH
    override fun getConfidenceThreshold(): Float = 0.85f
    override fun getDisplayName(): String = "Gemini AI"
}

/**
 * Implementation for ML Kit-powered translation
 */
class MLKitTranslatorImpl(
    private val mlKitTranslationService: MLKitTranslationService
) : Translator {
    
    override suspend fun translate(
        koreanText: String,
        context: List<String>
    ): Translator.TranslationResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val response = mlKitTranslationService.translate(koreanText)
            val processingTime = System.currentTimeMillis() - startTime
            
            Translator.TranslationResult(
                translatedText = response.translatedText,
                confidence = response.confidence,
                processingTimeMs = processingTime,
                modelVersion = "ML Kit",
                wasEnhanced = false,
                usedContext = false // ML Kit doesn't support context
            )
        } catch (e: Exception) {
            throw TranslationException("ML Kit translation failed", e)
        }
    }
    
    override suspend fun isAvailable(): Boolean {
        // Check if Korean language model is downloaded
        return try {
            mlKitTranslationService.isModelDownloaded()
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getEngineType(): TranslationEngine = TranslationEngine.ML_KIT
    override fun getConfidenceThreshold(): Float = 0.60f
    override fun getDisplayName(): String = "ML Kit"
}

/**
 * Exception thrown when translation fails
 */
class TranslationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)