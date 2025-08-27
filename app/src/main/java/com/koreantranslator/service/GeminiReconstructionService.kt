package com.koreantranslator.service

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for reconstructing fragmented Korean text using Gemini AI
 * Replaces complex rule-based reconstruction with intelligent AI processing
 */
@Singleton
class GeminiReconstructionService @Inject constructor(
    private val geminiApiService: GeminiApiService
) {
    
    companion object {
        private const val TAG = "GeminiReconstruction"
        private const val CACHE_SIZE = 1000
        private const val MIN_FRAGMENT_LENGTH = 3
        private const val MAX_CONTEXT_LENGTH = 100
    }
    
    // LRU cache for common reconstructions to improve performance
    private val reconstructionCache = LruCache<String, ReconstructionResult>(CACHE_SIZE)
    
    /**
     * Data class for reconstruction results
     */
    data class ReconstructionResult(
        val originalText: String,
        val reconstructedText: String,
        val confidence: Float,
        val fromCache: Boolean = false,
        val processingTimeMs: Long = 0L
    )
    
    /**
     * Main method to reconstruct fragmented Korean text using Gemini
     */
    suspend fun reconstructKoreanText(
        fragmentedText: String,
        previousContext: String? = null,
        useCache: Boolean = true
    ): ReconstructionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            Log.d(TAG, "Reconstructing Korean text: '$fragmentedText'")
            
            // Early validation - skip if text looks already correct
            if (looksAlreadyCorrect(fragmentedText)) {
                Log.d(TAG, "Text appears already correct, skipping reconstruction")
                return@withContext ReconstructionResult(
                    originalText = fragmentedText,
                    reconstructedText = fragmentedText,
                    confidence = 1.0f,
                    fromCache = false,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            // Check cache first if enabled
            if (useCache) {
                val cacheKey = createCacheKey(fragmentedText, previousContext)
                reconstructionCache.get(cacheKey)?.let { cachedResult ->
                    Log.d(TAG, "Using cached reconstruction: '$fragmentedText' -> '${cachedResult.reconstructedText}'")
                    return@withContext cachedResult.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        fromCache = true
                    )
                }
            }
            
            // Build prompt for Gemini reconstruction
            val prompt = buildReconstructionPrompt(fragmentedText, previousContext)
            Log.d(TAG, "Sending to Gemini for reconstruction")
            
            // Call Gemini API for reconstruction
            val reconstructed = geminiApiService.reconstructKorean(prompt)
            
            if (reconstructed.isBlank()) {
                Log.w(TAG, "Gemini returned empty reconstruction, using fallback")
                return@withContext ReconstructionResult(
                    originalText = fragmentedText,
                    reconstructedText = applyBasicFallback(fragmentedText),
                    confidence = 0.5f,
                    fromCache = false,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            
            // Calculate confidence based on reconstruction quality
            val confidence = calculateReconstructionConfidence(fragmentedText, reconstructed)
            
            val result = ReconstructionResult(
                originalText = fragmentedText,
                reconstructedText = reconstructed.trim(),
                confidence = confidence,
                fromCache = false,
                processingTimeMs = processingTime
            )
            
            // Cache the successful result
            if (useCache && confidence > 0.7f) {
                val cacheKey = createCacheKey(fragmentedText, previousContext)
                reconstructionCache.put(cacheKey, result)
                Log.d(TAG, "Cached reconstruction result (confidence: ${(confidence * 100).toInt()}%)")
            }
            
            Log.d(TAG, "Reconstruction completed: '$fragmentedText' -> '$reconstructed' " +
                    "(${processingTime}ms, confidence: ${(confidence * 100).toInt()}%)")
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Reconstruction failed, using fallback", e)
            return@withContext ReconstructionResult(
                originalText = fragmentedText,
                reconstructedText = applyBasicFallback(fragmentedText),
                confidence = 0.3f,
                fromCache = false,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * SIMPLIFIED: Build direct, example-based prompt for faster reconstruction
     */
    private fun buildReconstructionPrompt(fragmentedText: String, previousContext: String?): String {
        val contextPart = if (previousContext != null && previousContext.length <= MAX_CONTEXT_LENGTH) {
            "Context: \"$previousContext\"\n"
        } else ""
        
        return """Fix Korean spacing:

${contextPart}Input: "$fragmentedText"

Examples:
제 가 → 제가
그 렇 잖 아 요 → 그렇잖아요
안 녕 하 세 요 → 안녕하세요
감 사 합 니 다 → 감사합니다
괜 찮 아 요 → 괜찮아요

Output:"""
    }
    
    /**
     * Check if text already looks correct and doesn't need reconstruction
     */
    private fun looksAlreadyCorrect(text: String): Boolean {
        // If text is too short, probably doesn't need reconstruction
        if (text.length < MIN_FRAGMENT_LENGTH) return true
        
        // Check for obvious fragmentation patterns
        val hasFragmentation = text.matches(Regex(".*[가-힣]\\s[가-힣]\\s[가-힣].*")) ||
                              text.matches(Regex(".*[가-힣]\\s[가-힣]\\s[가-힣]\\s[가-힣].*"))
        
        // If no fragmentation patterns detected, likely already correct
        return !hasFragmentation
    }
    
    /**
     * Create cache key combining fragmented text and context
     */
    private fun createCacheKey(fragmentedText: String, context: String?): String {
        return if (context != null && context.length <= 50) {
            "${fragmentedText}|${context}"
        } else {
            fragmentedText
        }
    }
    
    /**
     * ENHANCED: Calculate confidence score with improved heuristics
     */
    private fun calculateReconstructionConfidence(original: String, reconstructed: String): Float {
        // Start with higher base confidence for our improved system
        var confidence = 0.9f
        
        // Check Korean character ratio (should be high for good reconstruction)
        val koreanRatio = reconstructed.count { it in '가'..'힣' }.toFloat() / reconstructed.filter { !it.isWhitespace() }.length.toFloat()
        if (koreanRatio < 0.8f) {
            confidence -= 0.2f // Penalty for low Korean content
        }
        
        // Length reasonableness check (reconstructed should be similar length)
        val originalLength = original.replace(" ", "").length
        val lengthRatio = reconstructed.replace(" ", "").length.toFloat() / originalLength.toFloat()
        if (lengthRatio < 0.7f || lengthRatio > 1.3f) {
            confidence -= 0.2f // Penalty for unreasonable length change
        }
        
        // Bonus for good spacing reduction (removing excessive spaces)
        val originalSpaces = original.count { it == ' ' }
        val reconstructedSpaces = reconstructed.count { it == ' ' }
        if (originalSpaces > reconstructedSpaces && reconstructedSpaces > 0) {
            confidence += 0.05f // Small bonus for good spacing
        }
        
        // Bonus for containing common Korean patterns
        val goodPatterns = listOf("안녕", "감사", "괜찮", "그렇", "잖아", "하세요")
        if (goodPatterns.any { reconstructed.contains(it) }) {
            confidence += 0.05f // Bonus for recognizable patterns
        }
        
        // Major penalty for empty or garbled output
        if (reconstructed.isBlank() || reconstructed.length < 2) {
            confidence = 0.1f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Basic fallback reconstruction when Gemini fails
     */
    private fun applyBasicFallback(fragmentedText: String): String {
        // Simple fallback: remove excessive spacing but keep basic word boundaries
        return fragmentedText
            .replace(Regex("\\s{2,}"), " ") // Remove multiple spaces
            .replace(Regex("([가-힣])\\s([가-힣])")) { match ->
                val char1 = match.groupValues[1]
                val char2 = match.groupValues[2]
                
                // Basic particle attachment rules
                val particles = setOf("가", "를", "는", "은", "이", "을", "에", "에게", "와", "과", "도", "만")
                if (char2 in particles) {
                    char1 + char2 // Attach particle
                } else {
                    match.value // Keep space
                }
            }
            .trim()
    }
    
    /**
     * Get cache statistics for monitoring
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to reconstructionCache.size(),
            "maxSize" to reconstructionCache.maxSize(),
            "hitCount" to reconstructionCache.hitCount(),
            "missCount" to reconstructionCache.missCount(),
            "hitRate" to run {
                val totalRequests = reconstructionCache.hitCount() + reconstructionCache.missCount()
                if (totalRequests > 0) {
                    reconstructionCache.hitCount().toFloat() / totalRequests.toFloat()
                } else 0.0f
            }
        )
    }
    
    /**
     * Clear cache (useful for testing)
     */
    fun clearCache() {
        reconstructionCache.evictAll()
        Log.d(TAG, "Reconstruction cache cleared")
    }
}