package com.koreantranslator.service

import android.util.Log
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confidence-aware correction system that preserves high-quality transcriptions
 * and applies targeted fixes only to genuinely problematic words.
 * 
 * This replaces the over-processing pipeline with surgical precision corrections.
 */
@Singleton
class ConfidenceAwareCorrector @Inject constructor() {
    
    companion object {
        private const val TAG = "ConfidenceCorrector"
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.9f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.7f
        private const val CACHE_SIZE = 500
    }
    
    // Cache high-confidence transcriptions as ground truth
    private val groundTruthCache = LruCache<String, String>(CACHE_SIZE)
    
    // Common Korean corrections for low-confidence words only
    private val targetedCorrections = mapOf(
        // Only fix obvious recognition errors, not stylistic differences
        "안아세요" to "안녕하세요",
        "감사함니다" to "감사합니다",
        "죄송함니다" to "죄송합니다",
        "괜찬아요" to "괜찮아요",
        "알겠슴니다" to "알겠습니다",
        "잠시만여" to "잠시만요",
        "잠깐만여" to "잠깐만요",
        "도와저세요" to "도와주세요",
        "고마워여" to "고마워요",
        "미안해여" to "미안해요"
    )
    
    // Statistics for monitoring
    private var totalProcessed = 0
    private var highConfidencePreserved = 0
    private var correctionsMade = 0
    
    /**
     * Process transcript with confidence-aware approach
     * HIGH confidence (>90%) = Trust completely, cache as ground truth
     * MEDIUM confidence (70-90%) = Apply minimal corrections only
     * LOW confidence (<70%) = Apply targeted corrections to problematic words
     */
    fun processTranscript(text: String, confidence: Float): ProcessingResult {
        totalProcessed++
        val originalText = text.trim()
        
        Log.d(TAG, "Processing: '$originalText' (confidence: ${(confidence * 100).toInt()}%)")
        
        when {
            confidence >= HIGH_CONFIDENCE_THRESHOLD -> {
                // TRUST COMPLETELY - Cache as ground truth for future reference
                highConfidencePreserved++
                cacheAsGroundTruth(originalText)
                Log.d(TAG, "HIGH confidence - preserving exactly: '$originalText'")
                return ProcessingResult(
                    originalText = originalText,
                    processedText = originalText,
                    wasChanged = false,
                    finalConfidence = confidence,
                    processingLevel = "HIGH_CONFIDENCE_PRESERVED"
                )
            }
            
            confidence >= LOW_CONFIDENCE_THRESHOLD -> {
                // MEDIUM confidence - Check if we have ground truth for this phrase
                val groundTruthMatch = findGroundTruthMatch(originalText)
                if (groundTruthMatch != null) {
                    Log.d(TAG, "MEDIUM confidence - using ground truth: '$groundTruthMatch'")
                    return ProcessingResult(
                        originalText = originalText,
                        processedText = groundTruthMatch,
                        wasChanged = originalText != groundTruthMatch,
                        finalConfidence = 0.95f, // High confidence in ground truth
                        processingLevel = "GROUND_TRUTH_APPLIED"
                    )
                }
                
                // Apply very conservative corrections only
                val correctedText = applyConservativeCorrections(originalText)
                val wasChanged = correctedText != originalText
                if (wasChanged) correctionsMade++
                
                Log.d(TAG, "MEDIUM confidence - conservative correction: '$correctedText'")
                return ProcessingResult(
                    originalText = originalText,
                    processedText = correctedText,
                    wasChanged = wasChanged,
                    finalConfidence = if (wasChanged) confidence + 0.1f else confidence,
                    processingLevel = "CONSERVATIVE_CORRECTION"
                )
            }
            
            else -> {
                // LOW confidence - Apply targeted corrections to problematic words
                val correctedText = applyTargetedCorrections(originalText)
                val wasChanged = correctedText != originalText
                if (wasChanged) correctionsMade++
                
                Log.d(TAG, "LOW confidence - targeted correction: '$correctedText'")
                return ProcessingResult(
                    originalText = originalText,
                    processedText = correctedText,
                    wasChanged = wasChanged,
                    finalConfidence = if (wasChanged) confidence + 0.2f else confidence,
                    processingLevel = "TARGETED_CORRECTION"
                )
            }
        }
    }
    
    /**
     * Cache high-confidence transcriptions as ground truth for future reference
     */
    private fun cacheAsGroundTruth(text: String) {
        // Create variations for fuzzy matching
        val normalizedKey = normalizeForMatching(text)
        groundTruthCache.put(normalizedKey, text)
        
        // Also cache individual words for word-level corrections
        text.split(" ").forEach { word ->
            if (word.length >= 2) {
                val normalizedWord = normalizeForMatching(word)
                groundTruthCache.put(normalizedWord, word)
            }
        }
        
        Log.d(TAG, "Cached ground truth: '$text'")
    }
    
    /**
     * Find ground truth match for similar phrases
     */
    private fun findGroundTruthMatch(text: String): String? {
        val normalizedInput = normalizeForMatching(text)
        
        // Exact match first
        groundTruthCache.get(normalizedInput)?.let { return it }
        
        // Fuzzy match for similar phrases
        val inputWords = text.split(" ")
        if (inputWords.size >= 2) {
            // Try matching partial phrases
            for (i in inputWords.indices) {
                for (j in i + 1..inputWords.size) {
                    val phrase = inputWords.subList(i, j).joinToString(" ")
                    val normalizedPhrase = normalizeForMatching(phrase)
                    groundTruthCache.get(normalizedPhrase)?.let {
                        // Replace the matched portion with ground truth
                        val result = text.replace(phrase, it)
                        Log.d(TAG, "Partial ground truth match: '$phrase' -> '$it'")
                        return result
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Apply very conservative corrections - only fix obvious errors
     */
    private fun applyConservativeCorrections(text: String): String {
        var result = text
        
        // Only fix the most obvious spacing issues
        result = result.replace(Regex("\\s{2,}"), " ") // Multiple spaces
        result = result.replace("감사 합니다", "감사합니다")
        result = result.replace("안녕 하세요", "안녕하세요") 
        result = result.replace("죄송 합니다", "죄송합니다")
        
        return result.trim()
    }
    
    /**
     * Apply targeted corrections for low-confidence transcriptions
     */
    private fun applyTargetedCorrections(text: String): String {
        var result = text
        
        // Apply targeted corrections for common misrecognitions
        targetedCorrections.forEach { (wrong, correct) ->
            if (result.contains(wrong)) {
                result = result.replace(wrong, correct)
                Log.d(TAG, "Applied targeted correction: '$wrong' -> '$correct'")
            }
        }
        
        // Clean up spacing
        result = result.replace(Regex("\\s{2,}"), " ").trim()
        
        return result
    }
    
    /**
     * Normalize text for fuzzy matching
     */
    private fun normalizeForMatching(text: String): String {
        return text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[.,!?]"), "")
            .trim()
    }
    
    /**
     * Get processing statistics
     */
    fun getStatistics(): ProcessingStatistics {
        return ProcessingStatistics(
            totalProcessed = totalProcessed,
            highConfidencePreserved = highConfidencePreserved,
            correctionsMade = correctionsMade,
            groundTruthCacheSize = groundTruthCache.size(),
            preservationRate = if (totalProcessed > 0) highConfidencePreserved.toFloat() / totalProcessed.toFloat() else 0f,
            correctionRate = if (totalProcessed > 0) correctionsMade.toFloat() / totalProcessed.toFloat() else 0f
        )
    }
    
    /**
     * Reset statistics and cache (useful for testing)
     */
    fun reset() {
        totalProcessed = 0
        highConfidencePreserved = 0
        correctionsMade = 0
        groundTruthCache.evictAll()
        Log.d(TAG, "Statistics and cache reset")
    }
    
    data class ProcessingResult(
        val originalText: String,
        val processedText: String,
        val wasChanged: Boolean,
        val finalConfidence: Float,
        val processingLevel: String
    )
    
    data class ProcessingStatistics(
        val totalProcessed: Int,
        val highConfidencePreserved: Int,
        val correctionsMade: Int,
        val groundTruthCacheSize: Int,
        val preservationRate: Float,
        val correctionRate: Float
    )
}