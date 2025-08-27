package com.koreantranslator.service

import android.content.Context
import android.util.Log
import com.koreantranslator.nlp.KoreanSpacingModel
import com.koreantranslator.nlp.PunctuationPredictor
import com.koreantranslator.nlp.NLPCache
import com.koreantranslator.nlp.TFLiteModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced Korean NLP Enhancement Service
 * 
 * This service provides ML-based text processing for Korean speech recognition output:
 * - Word segmentation (띄어쓰기) using deep learning models
 * - Punctuation prediction based on context and prosody
 * - Caching for improved performance
 * - Graceful fallback to rule-based processing
 */
@Singleton
class KoreanNLPEnhancementService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val koreanCorrectionService: KoreanCorrectionService,
    private val modelManager: TFLiteModelManager,
    private val nlpCache: NLPCache
) {
    
    companion object {
        private const val TAG = "KoreanNLPEnhancement"
        
        // Processing thresholds
        private const val MIN_TEXT_LENGTH = 2
        private const val MAX_TEXT_LENGTH = 500
        private const val CONFIDENCE_THRESHOLD = 0.7f
        
        // Cache settings
        private const val CACHE_ENABLED = true
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    // ML models
    private var spacingModel: KoreanSpacingModel? = null
    private var punctuationPredictor: PunctuationPredictor? = null
    
    // State tracking
    private var modelsInitialized = false
    private var initializationInProgress = false
    
    // Performance metrics
    private var totalProcessingTime = 0L
    private var processedCount = 0
    
    /**
     * Initialize ML models asynchronously
     */
    suspend fun initialize() {
        if (modelsInitialized || initializationInProgress) {
            return
        }
        
        initializationInProgress = true
        
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing Korean NLP models...")
                
                // Load spacing model
                spacingModel = modelManager.loadSpacingModel()?.let {
                    KoreanSpacingModel(context, it)
                }
                
                // Load punctuation model
                punctuationPredictor = modelManager.loadPunctuationModel()?.let {
                    PunctuationPredictor(context, it)
                }
                
                modelsInitialized = spacingModel != null || punctuationPredictor != null
                
                if (modelsInitialized) {
                    Log.i(TAG, "Korean NLP models initialized successfully")
                    Log.i(TAG, "Spacing model: ${if (spacingModel != null) "loaded" else "not available"}")
                    Log.i(TAG, "Punctuation model: ${if (punctuationPredictor != null) "loaded" else "not available"}")
                } else {
                    Log.w(TAG, "No NLP models available, falling back to rule-based processing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NLP models", e)
                modelsInitialized = false
            } finally {
                initializationInProgress = false
            }
        }
    }
    
    /**
     * Main enhancement function - processes Korean text with ML models
     * 
     * @param text Raw text from speech recognition
     * @param useCache Whether to use cached results
     * @param streamingMode Whether this is part of streaming (affects punctuation)
     * @return Enhanced text with proper spacing and punctuation
     */
    suspend fun enhance(
        text: String,
        useCache: Boolean = CACHE_ENABLED,
        streamingMode: Boolean = false
    ): EnhancementResult = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        
        // Input validation
        if (text.isBlank() || text.length < MIN_TEXT_LENGTH) {
            return@withContext EnhancementResult(
                originalText = text,
                enhancedText = text,
                spacingApplied = false,
                punctuationApplied = false,
                confidence = 1.0f,
                processingTimeMs = 0
            )
        }
        
        // Check cache first
        if (useCache) {
            nlpCache.get(text)?.let { cached ->
                Log.d(TAG, "Cache hit for text: ${text.take(30)}...")
                return@withContext cached
            }
        }
        
        // Ensure models are initialized
        if (!modelsInitialized) {
            initialize()
        }
        
        var processedText = text
        var spacingApplied = false
        var punctuationApplied = false
        var confidence = 0.5f
        
        try {
            // Step 1: Apply basic corrections first (fast, rule-based) 
            val correctionResult = koreanCorrectionService.correctKoreanText(text)
            processedText = correctionResult.correctedText
            
            // Check if input has broken word patterns that need forced processing
            val hasBrokenPatterns = detectBrokenWordPatterns(processedText)
            
            // Step 2: ENHANCED - Apply ML-based word segmentation with forced processing
            if (spacingModel != null && processedText.length <= MAX_TEXT_LENGTH) {
                val spacingResult = spacingModel!!.segment(processedText)
                
                // CRITICAL FIX: Force spacing application for broken word patterns
                if (spacingResult.confidence >= CONFIDENCE_THRESHOLD || hasBrokenPatterns) {
                    processedText = spacingResult.segmentedText
                    spacingApplied = true
                    confidence = maxOf(confidence, spacingResult.confidence)
                    
                    val appliedReason = if (hasBrokenPatterns) "forced (broken patterns)" else "normal (confidence: ${spacingResult.confidence})"
                    Log.d(TAG, "Applied ML spacing ($appliedReason): ${processedText.take(50)}...")
                } else {
                    Log.d(TAG, "Skipped ML spacing - low confidence: ${spacingResult.confidence}")
                }
            }
            
            // Step 3: ENHANCED - Apply punctuation prediction with forced processing
            if (!streamingMode && punctuationPredictor != null) {
                val punctuationResult = punctuationPredictor!!.predict(processedText)
                
                // Apply punctuation with lower threshold for broken patterns
                val punctuationThreshold = if (hasBrokenPatterns) 0.4f else CONFIDENCE_THRESHOLD
                if (punctuationResult.confidence >= punctuationThreshold) {
                    processedText = punctuationResult.punctuatedText
                    punctuationApplied = true
                    confidence = (confidence + punctuationResult.confidence) / 2
                    
                    val appliedReason = if (hasBrokenPatterns) "lowered threshold" else "normal threshold"
                    Log.d(TAG, "Applied punctuation ($appliedReason): ${processedText.take(50)}...")
                } else {
                    Log.d(TAG, "Skipped punctuation - low confidence: ${punctuationResult.confidence}")
                }
            }
            
            // Step 4: Enhanced final cleanup with additional broken pattern fixes
            processedText = finalCleanup(processedText)
            
            // Step 5: Additional validation pass for broken patterns
            if (hasBrokenPatterns) {
                processedText = additionalBrokenPatternCleanup(processedText)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during NLP enhancement", e)
            // Fall back to basic correction only
            processedText = koreanCorrectionService.correctKoreanText(text).correctedText
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        updateMetrics(processingTime)
        
        val result = EnhancementResult(
            originalText = text,
            enhancedText = processedText,
            spacingApplied = spacingApplied,
            punctuationApplied = punctuationApplied,
            confidence = confidence,
            processingTimeMs = processingTime
        )
        
        // Cache the result
        if (useCache && (spacingApplied || punctuationApplied)) {
            nlpCache.put(text, result, CACHE_TTL_MS)
        }
        
        Log.d(TAG, "Enhancement complete in ${processingTime}ms - " +
                "Spacing: $spacingApplied, Punctuation: $punctuationApplied, " +
                "Confidence: ${(confidence * 100).toInt()}%")
        
        result
    }
    
    /**
     * Process streaming text with special handling for partial sentences
     */
    suspend fun enhanceStreaming(
        text: String,
        previousContext: String? = null
    ): EnhancementResult {
        // For streaming, we want fast processing and no final punctuation
        // since the sentence might not be complete
        
        val contextualText = if (previousContext != null) {
            // Use previous context to improve word boundary detection
            val combined = "$previousContext $text"
            val enhanced = enhance(combined, useCache = false, streamingMode = true)
            
            // Extract only the new part
            val enhancedNew = if (enhanced.enhancedText.length > previousContext.length) {
                enhanced.enhancedText.substring(previousContext.length).trim()
            } else {
                enhanced.enhancedText
            }
            
            enhanced.copy(enhancedText = enhancedNew)
        } else {
            enhance(text, useCache = false, streamingMode = true)
        }
        
        return contextualText
    }
    
    /**
     * Final cleanup of processed text
     */
    private fun finalCleanup(text: String): String {
        return text
            .replace(Regex("\\s{2,}"), " ") // Multiple spaces to single
            .replace(Regex("\\s+([.,!?])"), "$1") // Remove space before punctuation
            .replace(Regex("([.,!?])(\\S)"), "$1 $2") // Add space after punctuation if missing
            .trim()
    }
    
    /**
     * Update performance metrics
     */
    private fun updateMetrics(processingTime: Long) {
        totalProcessingTime += processingTime
        processedCount++
        
        if (processedCount % 100 == 0) {
            val avgTime = totalProcessingTime / processedCount
            Log.i(TAG, "NLP Performance - Avg processing time: ${avgTime}ms over $processedCount requests")
        }
    }
    
    /**
     * NEW: Detect broken Korean word patterns that need forced NLP processing
     * Works in conjunction with KoreanCorrectionService for comprehensive detection
     */
    private fun detectBrokenWordPatterns(text: String): Boolean {
        // Check for patterns that indicate broken Korean words
        val brokenPatterns = listOf(
            // Syllables separated by spaces (most common issue)
            Regex("[가-힣]\\s+[가-힣]\\s+[가-힣]"), // 3+ Korean syllables with spaces
            
            // Known broken words from problem analysis
            Regex("괜\\s+"), // 괜 as fragment
            Regex("\\s+찮"), // 찮 as fragment
            Regex("나기\\s+"), // 나기 as fragment
            Regex("\\s+귀찮"), // 귀찮 as fragment
            
            // Spaced verb endings
            Regex("\\s+습니다"),
            Regex("\\s+세요"),
            Regex("\\s+어요"),
            Regex("\\s+아요"),
            
            // Common greetings broken
            Regex("안\\s+녕"),
            Regex("녕\\s+하세요"),
            
            // Thanks/sorry broken
            Regex("감\\s+사"),
            Regex("사\\s+합니다"),
            Regex("죄\\s+송"),
            Regex("송\\s+합니다"),
            
            // Excessive spacing
            Regex("[가-힣]\\s{2,}[가-힣]") // Korean chars with 2+ spaces
        )
        
        return brokenPatterns.any { it.containsMatchIn(text) }
    }
    
    /**
     * NEW: Additional cleanup specifically for broken word patterns
     * Applied after ML models to catch any remaining issues
     */
    private fun additionalBrokenPatternCleanup(text: String): String {
        var cleaned = text
        
        // Apply aggressive syllable merging for broken patterns
        val syllableMergePatterns = mapOf(
            // Merge isolated Korean syllables that are likely fragments
            Regex("([가-힣])\\s+([가-힣])\\s+([가-힣])\\s+([가-힣])") to "$1$2$3$4", // 4 syllables
            Regex("([가-힣])\\s+([가-힣])\\s+([가-힣])") to "$1$2$3", // 3 syllables
            
            // Specific broken word repairs based on analysis
            Regex("괜\\s*아\\s*찮\\s*요") to "괜찮아요",
            Regex("나\\s*기\\s*귀찮아\\s*요") to "나가기 귀찮아요",
            Regex("안\\s*녕\\s*하세요") to "안녕하세요",
            Regex("감\\s*사\\s*합니다") to "감사합니다",
            Regex("죄\\s*송\\s*합니다") to "죄송합니다",
            
            // Fix verb ending attachments
            Regex("([가-힣]+)\\s+(습니다|세요|어요|아요|지요)") to "$1$2"
        )
        
        syllableMergePatterns.forEach { (pattern, replacement) ->
            val before = cleaned
            cleaned = pattern.replace(cleaned, replacement)
            if (before != cleaned) {
                Log.d(TAG, "Additional cleanup applied: '${pattern.pattern}' -> '$replacement'")
            }
        }
        
        return cleaned
    }
    
    /**
     * Get current model status
     */
    fun getModelStatus(): ModelStatus {
        return ModelStatus(
            spacingModelLoaded = spacingModel != null,
            punctuationModelLoaded = punctuationPredictor != null,
            modelsInitialized = modelsInitialized,
            averageProcessingTimeMs = if (processedCount > 0) {
                totalProcessingTime / processedCount
            } else 0
        )
    }
    
    /**
     * Release resources
     */
    fun release() {
        spacingModel?.release()
        punctuationPredictor?.release()
        spacingModel = null
        punctuationPredictor = null
        modelsInitialized = false
    }
    
    /**
     * Clear the NLP cache
     */
    fun clearCache() {
        nlpCache.clear()
        Log.d(TAG, "NLP cache cleared")
    }
    
    /**
     * Result of text enhancement
     */
    data class EnhancementResult(
        val originalText: String,
        val enhancedText: String,
        val spacingApplied: Boolean,
        val punctuationApplied: Boolean,
        val confidence: Float,
        val processingTimeMs: Long
    )
    
    /**
     * Current status of ML models
     */
    data class ModelStatus(
        val spacingModelLoaded: Boolean,
        val punctuationModelLoaded: Boolean,
        val modelsInitialized: Boolean,
        val averageProcessingTimeMs: Long
    )
}