package com.koreantranslator.service

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced Translation Manager that orchestrates multiple translation engines
 * with intelligent switching, caching, and performance optimization.
 */
@Singleton
class TranslationManager @Inject constructor(
    private val geminiTranslator: GeminiTranslatorImpl,
    private val mlKitTranslator: MLKitTranslatorImpl,
    private val koreanNLPService: com.koreantranslator.nlp.KoreanNLPService,
    private val networkStateMonitor: NetworkStateMonitor,
    private val translationCacheManager: TranslationCacheManager,
    private val circuitBreakerService: CircuitBreakerService,
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "TranslationManager"
        
        // Performance thresholds
        private const val ML_KIT_TIMEOUT_MS = 500L
        private const val GEMINI_TIMEOUT_MS = 3000L
        private const val SESSION_CACHE_SIZE = 150
        private const val MIN_TEXT_LENGTH_FOR_ENHANCEMENT = 10
    }
    
    // In-memory session cache for immediate responses
    private val sessionCache = LruCache<String, CachedTranslation>(SESSION_CACHE_SIZE)
    
    // Conversation context tracking
    private val conversationHistory = mutableListOf<String>()
    private val maxContextSize = 5
    
    // Translation state
    private val _translationState = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val translationState: StateFlow<TranslationState> = _translationState
    
    // Performance metrics
    private var totalTranslations = 0
    private var geminiSuccesses = 0
    private var mlKitFallbacks = 0
    private var cacheHits = 0
    
    /**
     * Main translation method with intelligent dual-engine orchestration
     */
    suspend fun translateWithDualEngine(
        koreanText: String,
        preferredEngine: TranslationEngine? = null,
        useContext: Boolean = true
    ): Flow<TranslationState> = flow {
        
        val normalizedText = koreanText.trim()
        if (normalizedText.isEmpty()) {
            Log.w(TAG, "Empty text provided for translation")
            emit(TranslationState.Error("Empty text", "Cannot translate empty text"))
            return@flow
        }
        
        totalTranslations++
        Log.d(TAG, "═══════════ TRANSLATION START ═══════════")
        Log.d(TAG, "Text: '${normalizedText}'")
        Log.d(TAG, "Preferred engine: ${preferredEngine?.name ?: "AUTO"}")
        Log.d(TAG, "Use context: $useContext")
        Log.d(TAG, "Total translations so far: $totalTranslations")
        
        // Update state flow to show we're starting
        _translationState.value = TranslationState.Idle
            // Step 1: Check cache first
            Log.d(TAG, "Step 1: Checking cache...")
            getCachedTranslation(normalizedText)?.let { cached ->
                cacheHits++
                Log.d(TAG, "✓ CACHE HIT! Found cached translation from ${cached.engine.name}")
                Log.d(TAG, "Cached result: '${cached.translatedText.take(50)}...' (conf: ${(cached.confidence * 100).toInt()}%)")
                
                val cachedState = TranslationState.Success(
                    originalText = normalizedText,
                    translatedText = cached.translatedText,
                    engine = cached.engine,
                    confidence = cached.confidence,
                    fromCache = true,
                    processingTimeMs = 0L
                )
                _translationState.value = cachedState
                emit(cachedState)
                Log.d(TAG, "═══════════ TRANSLATION COMPLETE (CACHED) ═══════════")
                return@flow
            }
            Log.d(TAG, "No cache hit, proceeding with dual-engine translation...")
            
            Log.d(TAG, "Step 2: Starting ML Kit translation...")
            val translatingState = TranslationState.Translating(normalizedText)
            _translationState.value = translatingState
            emit(translatingState)
            
            // Step 2: Get instant ML Kit translation
            val mlKitResult = getMLKitTranslation(normalizedText)
            if (mlKitResult != null) {
                Log.d(TAG, "✓ ML Kit translation successful: '${mlKitResult.translatedText.take(50)}...'")
                val mlKitState = TranslationState.Success(
                    originalText = normalizedText,
                    translatedText = mlKitResult.translatedText,
                    engine = TranslationEngine.ML_KIT,
                    confidence = mlKitResult.confidence,
                    isPartial = shouldUseGemini(preferredEngine, normalizedText),
                    processingTimeMs = mlKitResult.processingTimeMs
                )
                _translationState.value = mlKitState
                emit(mlKitState)
            } else {
                Log.w(TAG, "✗ ML Kit translation failed!")
            }
            
            // Step 3: Enhance with Gemini if conditions are met
            val useGemini = shouldUseGemini(preferredEngine, normalizedText)
            Log.d(TAG, "Step 3: Should use Gemini for enhancement? $useGemini")
            
            if (useGemini) {
                Log.d(TAG, "Starting Gemini enhancement...")
                val enhancingState = TranslationState.Enhancing(normalizedText)
                _translationState.value = enhancingState
                emit(enhancingState)
                
                val geminiResult = getGeminiTranslation(normalizedText, useContext)
                if (geminiResult != null) {
                    geminiSuccesses++
                    Log.d(TAG, "✓ GEMINI ENHANCEMENT SUCCESSFUL!")
                    Log.d(TAG, "Enhanced result: '${geminiResult.translatedText.take(50)}...' (conf: ${(geminiResult.confidence * 100).toInt()}%)")
                    
                    // Cache the premium result
                    cacheTranslation(normalizedText, geminiResult.translatedText, 
                                   TranslationEngine.GEMINI_FLASH, geminiResult.confidence)
                    
                    // Update conversation history
                    updateConversationHistory(normalizedText)
                    
                    val geminiState = TranslationState.Success(
                        originalText = normalizedText,
                        translatedText = geminiResult.translatedText,
                        engine = TranslationEngine.GEMINI_FLASH,
                        confidence = geminiResult.confidence,
                        wasEnhanced = true,
                        processingTimeMs = geminiResult.processingTimeMs
                    )
                    _translationState.value = geminiState
                    emit(geminiState)
                    Log.d(TAG, "═══════════ TRANSLATION COMPLETE (ENHANCED) ═══════════")
                } else {
                    Log.w(TAG, "✗ Gemini enhancement failed!")
                    if (mlKitResult == null) {
                        // Both engines failed
                        Log.e(TAG, "CRITICAL: Both ML Kit and Gemini failed!")
                        val errorState = TranslationState.Error(
                            originalText = normalizedText,
                            message = "Translation failed - please try again",
                            canRetry = true
                        )
                        _translationState.value = errorState
                        emit(errorState)
                    } else {
                        Log.d(TAG, "Using ML Kit result as fallback")
                        Log.d(TAG, "═══════════ TRANSLATION COMPLETE (ML KIT FALLBACK) ═══════════")
                    }
                }
            } else {
                Log.d(TAG, "Skipping Gemini enhancement (conditions not met)")
                if (mlKitResult != null) {
                    Log.d(TAG, "═══════════ TRANSLATION COMPLETE (ML KIT ONLY) ═══════════")
                }
            }
            
    }.catch { e ->
        Log.e(TAG, "Translation failed", e)
        val normalizedText = koreanText.trim()
        // Use flowOf() to properly emit error without transparency violation
        emitAll(flowOf(TranslationState.Error(
            originalText = normalizedText,
            message = getErrorMessage(e),
            canRetry = true
        )))
    }
    
    /**
     * Get ML Kit translation with timeout and Korean preprocessing
     */
    private suspend fun getMLKitTranslation(text: String): Translator.TranslationResult? {
        return try {
            if (!mlKitTranslator.isAvailable()) {
                Log.w(TAG, "ML Kit not available")
                return null
            }
            
            // CRITICAL FIX: Preprocess Korean text to fix speech recognition spacing issues
            Log.d(TAG, "Original text for ML Kit: '$text'")
            val preprocessedText = preprocessKoreanText(text)
            Log.d(TAG, "Preprocessed text for ML Kit: '$preprocessedText'")
            
            withTimeoutOrNull(ML_KIT_TIMEOUT_MS) {
                mlKitTranslator.translate(preprocessedText)
            }?.also {
                Log.d(TAG, "ML Kit translation: '${it.translatedText.take(50)}...' " +
                          "(${it.processingTimeMs}ms, conf: ${(it.confidence * 100).toInt()}%)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translation failed", e)
            mlKitFallbacks++
            null
        }
    }
    
    /**
     * CRITICAL FIX: Preprocess Korean text to fix spacing issues from speech recognition
     * This handles cases like "오 늘 날 씨 가" -> "오늘 날씨가"
     */
    private suspend fun preprocessKoreanText(text: String): String {
        return try {
            // Check if text contains Korean characters
            if (text.any { it in '가'..'힣' }) {
                Log.d(TAG, "Applying Korean NLP preprocessing for speech recognition spacing fix")
                koreanNLPService.fixSpeechRecognitionSpacing(text)
            } else {
                Log.d(TAG, "Non-Korean text, skipping preprocessing")
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Korean preprocessing failed, using original text", e)
            text // Fallback to original text if preprocessing fails
        }
    }
    
    /**
     * Get Gemini translation with context and circuit breaker protection
     */
    private suspend fun getGeminiTranslation(text: String, useContext: Boolean): Translator.TranslationResult? {
        return try {
            if (!networkStateMonitor.isNetworkSuitableForApiCalls()) {
                Log.d(TAG, "Network not suitable for Gemini API")
                return null
            }
            
            if (!circuitBreakerService.isServiceAvailable("gemini")) {
                Log.d(TAG, "Gemini circuit breaker is open")
                return null
            }
            
            circuitBreakerService.executeWithCircuitBreaker("gemini") {
                withTimeoutOrNull(GEMINI_TIMEOUT_MS) {
                    val context = if (useContext) getConversationContext() else emptyList()
                    geminiTranslator.translate(text, context)
                }
            }?.also {
                Log.d(TAG, "Gemini translation: '${it.translatedText.take(50)}...' " +
                          "(${it.processingTimeMs}ms, conf: ${(it.confidence * 100).toInt()}%)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini translation failed", e)
            null
        }
    }
    
    /**
     * Determine if Gemini should be used for enhancement
     */
    private fun shouldUseGemini(preferredEngine: TranslationEngine?, text: String): Boolean {
        return when {
            preferredEngine == TranslationEngine.ML_KIT -> false
            preferredEngine == TranslationEngine.GEMINI_FLASH -> true
            !networkStateMonitor.isNetworkSuitableForApiCalls() -> false
            !circuitBreakerService.isServiceAvailable("gemini") -> false
            text.length < MIN_TEXT_LENGTH_FOR_ENHANCEMENT -> false // Skip short phrases
            else -> true
        }
    }
    
    /**
     * Get cached translation if available
     */
    private suspend fun getCachedTranslation(text: String): CachedTranslation? {
        // Check session cache first (fastest)
        sessionCache.get(text)?.let { return it }
        
        // Check persistent cache
        return translationCacheManager.get(text)?.let { response ->
            val cached = CachedTranslation(
                translatedText = response.translatedText,
                engine = response.engine,
                confidence = response.confidence,
                timestamp = System.currentTimeMillis()
            )
            
            // Promote to session cache
            sessionCache.put(text, cached)
            cached
        }
    }
    
    /**
     * Cache a successful translation
     */
    private suspend fun cacheTranslation(originalText: String, translatedText: String, 
                                engine: TranslationEngine, confidence: Float) {
        val cached = CachedTranslation(
            translatedText = translatedText,
            engine = engine,
            confidence = confidence,
            timestamp = System.currentTimeMillis()
        )
        
        // Store in session cache
        sessionCache.put(originalText, cached)
        
        // Store in persistent cache if high confidence
        if (confidence > 0.7f) {
            translationCacheManager.put(originalText, com.koreantranslator.model.TranslationResponse(
                translatedText = translatedText,
                confidence = confidence,
                engine = engine,
                isEnhanced = engine == TranslationEngine.GEMINI_FLASH
            ))
        }
    }
    
    /**
     * Update conversation history for context
     */
    private fun updateConversationHistory(koreanText: String) {
        conversationHistory.add(koreanText)
        if (conversationHistory.size > maxContextSize) {
            conversationHistory.removeFirst()
        }
    }
    
    /**
     * Get conversation context for enhanced translation
     */
    private fun getConversationContext(): List<String> {
        return conversationHistory.takeLast(3) // Use last 3 entries for context
    }
    
    /**
     * Get user-friendly error message
     */
    private fun getErrorMessage(error: Throwable): String {
        return when (error) {
            is TranslationException -> error.message ?: "Translation failed"
            is java.net.UnknownHostException -> "Network unavailable"
            is java.util.concurrent.TimeoutException -> "Translation timed out"
            else -> "Translation error occurred"
        }
    }
    
    /**
     * Get performance statistics
     */
    fun getStatistics(): TranslationStatistics {
        return TranslationStatistics(
            totalTranslations = totalTranslations,
            geminiSuccesses = geminiSuccesses,
            mlKitFallbacks = mlKitFallbacks,
            cacheHits = cacheHits,
            sessionCacheSize = sessionCache.size(),
            conversationContextSize = conversationHistory.size
        )
    }
    
    /**
     * Clear session data
     */
    fun clearSession() {
        sessionCache.evictAll()
        conversationHistory.clear()
        _translationState.value = TranslationState.Idle
        Log.d(TAG, "Translation session cleared")
    }
    
    /**
     * Force refresh - clear cache and reset state
     */
    fun forceRefresh() {
        clearSession()
        Log.d(TAG, "Translation manager refreshed")
    }
}

// TranslationState moved to model package for better organization

/**
 * Cached translation entry
 */
data class CachedTranslation(
    val translatedText: String,
    val engine: TranslationEngine,
    val confidence: Float,
    val timestamp: Long
)

/**
 * Translation performance statistics
 */
data class TranslationStatistics(
    val totalTranslations: Int,
    val geminiSuccesses: Int,
    val mlKitFallbacks: Int,
    val cacheHits: Int,
    val sessionCacheSize: Int,
    val conversationContextSize: Int
) {
    val geminiSuccessRate: Float
        get() = if (totalTranslations > 0) geminiSuccesses.toFloat() / totalTranslations else 0f
    
    val cacheHitRate: Float  
        get() = if (totalTranslations > 0) cacheHits.toFloat() / totalTranslations else 0f
}