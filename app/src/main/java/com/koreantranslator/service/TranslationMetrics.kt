package com.koreantranslator.service

import android.content.Context
import android.util.Log
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Service to track translation quality metrics and provide insights
 * for continuous improvement of translation accuracy
 */
@Singleton
class TranslationMetrics @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TranslationMetrics"
        private const val PREFS_NAME = "translation_metrics"
        private const val KEY_TOTAL_TRANSLATIONS = "total_translations"
        private const val KEY_ENHANCED_TRANSLATIONS = "enhanced_translations"
        private const val KEY_PARTICLE_CORRECTIONS = "particle_corrections"
        private const val KEY_THINKING_USAGE = "thinking_usage"
        private const val KEY_AVERAGE_CONFIDENCE = "average_confidence"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Real-time metrics
    private val _currentSessionMetrics = MutableStateFlow(SessionMetrics())
    val currentSessionMetrics: StateFlow<SessionMetrics> = _currentSessionMetrics.asStateFlow()
    
    // Pattern-based accuracy tracking
    private val patternAccuracy = ConcurrentHashMap<String, PatternMetric>()
    
    // Track specific translation challenges
    private val challengingPhrases = mutableListOf<ChallengingPhrase>()
    
    data class SessionMetrics(
        val totalTranslations: Int = 0,
        val enhancedTranslations: Int = 0,
        val mlKitOnly: Int = 0,
        val averageConfidence: Float = 0f,
        val thinkingBudgetUsed: Int = 0,
        val particleCorrections: Int = 0,
        val slangDetections: Int = 0,
        val contextualEnhancements: Int = 0,
        val averageResponseTime: Long = 0L,
        val cacheHits: Int = 0
    )
    
    data class PatternMetric(
        val pattern: String,
        val occurrences: Int,
        val averageConfidence: Float,
        val successRate: Float
    )
    
    data class ChallengingPhrase(
        val korean: String,
        val mlKitTranslation: String?,
        val geminiTranslation: String?,
        val confidence: Float,
        val hasComplexParticles: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Record a translation and update metrics
     */
    fun recordTranslation(
        koreanText: String,
        response: TranslationResponse,
        mlKitResult: String? = null,
        responseTimeMs: Long = 0,
        wasEnhanced: Boolean = false,
        thinkingBudget: Int = 0,
        fromCache: Boolean = false
    ) {
        try {
            val currentMetrics = _currentSessionMetrics.value
            
            // Update session metrics
            _currentSessionMetrics.value = currentMetrics.copy(
                totalTranslations = currentMetrics.totalTranslations + 1,
                enhancedTranslations = if (wasEnhanced) currentMetrics.enhancedTranslations + 1 else currentMetrics.enhancedTranslations,
                mlKitOnly = if (response.engine == TranslationEngine.ML_KIT) currentMetrics.mlKitOnly + 1 else currentMetrics.mlKitOnly,
                averageConfidence = calculateNewAverage(
                    currentMetrics.averageConfidence,
                    response.confidence,
                    currentMetrics.totalTranslations
                ),
                thinkingBudgetUsed = currentMetrics.thinkingBudgetUsed + thinkingBudget,
                averageResponseTime = calculateNewAverage(
                    currentMetrics.averageResponseTime.toFloat(),
                    responseTimeMs.toFloat(),
                    currentMetrics.totalTranslations
                ).toLong(),
                cacheHits = if (fromCache) currentMetrics.cacheHits + 1 else currentMetrics.cacheHits
            )
            
            // Track pattern-specific metrics
            detectAndRecordPatterns(koreanText, response)
            
            // Track challenging phrases (low confidence or significant difference)
            if (response.confidence < 0.85f || (mlKitResult != null && significantDifference(mlKitResult, response.translatedText))) {
                challengingPhrases.add(
                    ChallengingPhrase(
                        korean = koreanText,
                        mlKitTranslation = mlKitResult,
                        geminiTranslation = response.translatedText,
                        confidence = response.confidence,
                        hasComplexParticles = detectComplexParticles(koreanText)
                    )
                )
                
                // Keep only recent challenging phrases
                if (challengingPhrases.size > 100) {
                    challengingPhrases.removeAt(0)
                }
            }
            
            // Log insights periodically
            if (currentMetrics.totalTranslations % 50 == 0) {
                logMetricsInsights()
            }
            
            // Persist important metrics
            persistMetrics()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording translation metrics", e)
        }
    }
    
    /**
     * Detect and record pattern-specific accuracy
     */
    private fun detectAndRecordPatterns(koreanText: String, response: TranslationResponse) {
        val patterns = listOf(
            "잖아요" to "reminder_assertion",
            "네요" to "realization",
            "거든요" to "explanation",
            "죠" to "confirmation",
            "ㅋㅋ" to "internet_slang",
            "ㅠㅠ" to "emotion_expression"
        )
        
        for ((pattern, category) in patterns) {
            if (koreanText.contains(pattern)) {
                val metric = patternAccuracy[category] ?: PatternMetric(
                    pattern = category,
                    occurrences = 0,
                    averageConfidence = 0f,
                    successRate = 0f
                )
                
                patternAccuracy[category] = metric.copy(
                    occurrences = metric.occurrences + 1,
                    averageConfidence = calculateNewAverage(
                        metric.averageConfidence,
                        response.confidence,
                        metric.occurrences
                    ),
                    successRate = if (response.confidence > 0.9f) {
                        calculateNewAverage(metric.successRate, 1f, metric.occurrences)
                    } else {
                        calculateNewAverage(metric.successRate, 0f, metric.occurrences)
                    }
                )
                
                // Update specific counters
                if (pattern.contains("ㅋ") || pattern.contains("ㅠ")) {
                    val current = _currentSessionMetrics.value
                    _currentSessionMetrics.value = current.copy(
                        slangDetections = current.slangDetections + 1
                    )
                } else {
                    val current = _currentSessionMetrics.value
                    _currentSessionMetrics.value = current.copy(
                        particleCorrections = current.particleCorrections + 1
                    )
                }
            }
        }
    }
    
    /**
     * Log comprehensive metrics insights
     */
    private fun logMetricsInsights() {
        val metrics = _currentSessionMetrics.value
        
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "TRANSLATION METRICS REPORT")
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "Total Translations: ${metrics.totalTranslations}")
        Log.d(TAG, "Enhanced: ${metrics.enhancedTranslations} (${(metrics.enhancedTranslations * 100f / metrics.totalTranslations).roundToInt()}%)")
        Log.d(TAG, "Average Confidence: ${String.format("%.2f", metrics.averageConfidence)}")
        Log.d(TAG, "Average Response Time: ${metrics.averageResponseTime}ms")
        Log.d(TAG, "Cache Hit Rate: ${(metrics.cacheHits * 100f / metrics.totalTranslations).roundToInt()}%")
        Log.d(TAG, "Thinking Budget Used: ${metrics.thinkingBudgetUsed} tokens total")
        
        Log.d(TAG, "────────────────────────────────────────")
        Log.d(TAG, "PATTERN ACCURACY:")
        patternAccuracy.forEach { (pattern, metric) ->
            Log.d(TAG, "  $pattern: ${metric.occurrences} times, " +
                    "${String.format("%.1f", metric.successRate * 100)}% success, " +
                    "${String.format("%.2f", metric.averageConfidence)} avg confidence")
        }
        
        if (challengingPhrases.isNotEmpty()) {
            Log.d(TAG, "────────────────────────────────────────")
            Log.d(TAG, "RECENT CHALLENGES (${challengingPhrases.size} phrases):")
            challengingPhrases.takeLast(5).forEach { phrase ->
                Log.d(TAG, "  Korean: \"${phrase.korean}\"")
                Log.d(TAG, "  Confidence: ${String.format("%.2f", phrase.confidence)}")
                if (phrase.hasComplexParticles) {
                    Log.d(TAG, "  ⚠ Complex particles detected")
                }
            }
        }
        
        Log.d(TAG, "════════════════════════════════════════")
        
        // Provide recommendations
        provideRecommendations()
    }
    
    /**
     * Provide actionable recommendations based on metrics
     */
    private fun provideRecommendations() {
        val metrics = _currentSessionMetrics.value
        val recommendations = mutableListOf<String>()
        
        // Check enhancement rate
        val enhancementRate = metrics.enhancedTranslations.toFloat() / metrics.totalTranslations
        if (enhancementRate < 0.7f) {
            recommendations.add("Consider checking network connectivity - low enhancement rate (${(enhancementRate * 100).roundToInt()}%)")
        }
        
        // Check confidence levels
        if (metrics.averageConfidence < 0.9f) {
            recommendations.add("Average confidence below 90% - review challenging phrases for pattern improvements")
        }
        
        // Pattern-specific recommendations
        patternAccuracy.forEach { (pattern, metric) ->
            if (metric.successRate < 0.8f && metric.occurrences > 5) {
                recommendations.add("Pattern '$pattern' has low success rate (${(metric.successRate * 100).roundToInt()}%) - consider prompt adjustments")
            }
        }
        
        // Response time
        if (metrics.averageResponseTime > 1000) {
            recommendations.add("High average response time (${metrics.averageResponseTime}ms) - consider optimizing thinking budget")
        }
        
        if (recommendations.isNotEmpty()) {
            Log.d(TAG, "RECOMMENDATIONS:")
            recommendations.forEach { rec ->
                Log.d(TAG, "  • $rec")
            }
        }
    }
    
    /**
     * Get quality report for UI display
     */
    fun getQualityReport(): QualityReport {
        val metrics = _currentSessionMetrics.value
        
        return QualityReport(
            totalTranslations = metrics.totalTranslations,
            averageConfidence = metrics.averageConfidence,
            enhancementRate = if (metrics.totalTranslations > 0) {
                metrics.enhancedTranslations.toFloat() / metrics.totalTranslations
            } else 0f,
            topChallenges = challengingPhrases.takeLast(10),
            patternSuccess = patternAccuracy.map { (k, v) -> k to v.successRate }.toMap(),
            averageResponseTime = metrics.averageResponseTime
        )
    }
    
    data class QualityReport(
        val totalTranslations: Int,
        val averageConfidence: Float,
        val enhancementRate: Float,
        val topChallenges: List<ChallengingPhrase>,
        val patternSuccess: Map<String, Float>,
        val averageResponseTime: Long
    )
    
    // Utility functions
    private fun calculateNewAverage(oldAverage: Float, newValue: Float, oldCount: Int): Float {
        return ((oldAverage * oldCount) + newValue) / (oldCount + 1)
    }
    
    private fun significantDifference(text1: String, text2: String): Boolean {
        // Simple heuristic: significant if more than 30% different
        val commonWords = text1.split(" ").intersect(text2.split(" ").toSet())
        val totalWords = text1.split(" ").size + text2.split(" ").size
        return commonWords.size.toFloat() / totalWords < 0.7f
    }
    
    private fun detectComplexParticles(korean: String): Boolean {
        val complexParticles = listOf("잖아요", "거든요", "더라고요", "네요", "군요")
        return complexParticles.any { korean.contains(it) }
    }
    
    private fun persistMetrics() {
        val metrics = _currentSessionMetrics.value
        prefs.edit().apply {
            putInt(KEY_TOTAL_TRANSLATIONS, prefs.getInt(KEY_TOTAL_TRANSLATIONS, 0) + 1)
            putInt(KEY_ENHANCED_TRANSLATIONS, prefs.getInt(KEY_ENHANCED_TRANSLATIONS, 0) + 
                    if (metrics.enhancedTranslations > 0) 1 else 0)
            putInt(KEY_PARTICLE_CORRECTIONS, prefs.getInt(KEY_PARTICLE_CORRECTIONS, 0) + 
                    metrics.particleCorrections)
            putInt(KEY_THINKING_USAGE, prefs.getInt(KEY_THINKING_USAGE, 0) + 
                    metrics.thinkingBudgetUsed)
            putFloat(KEY_AVERAGE_CONFIDENCE, metrics.averageConfidence)
        }.apply()
    }
    
    /**
     * Reset session metrics
     */
    fun resetSessionMetrics() {
        _currentSessionMetrics.value = SessionMetrics()
        patternAccuracy.clear()
        challengingPhrases.clear()
        Log.d(TAG, "Session metrics reset")
    }
    
    /**
     * Get historical metrics
     */
    fun getHistoricalMetrics(): HistoricalMetrics {
        return HistoricalMetrics(
            totalTranslations = prefs.getInt(KEY_TOTAL_TRANSLATIONS, 0),
            enhancedTranslations = prefs.getInt(KEY_ENHANCED_TRANSLATIONS, 0),
            particleCorrections = prefs.getInt(KEY_PARTICLE_CORRECTIONS, 0),
            totalThinkingTokens = prefs.getInt(KEY_THINKING_USAGE, 0),
            averageConfidence = prefs.getFloat(KEY_AVERAGE_CONFIDENCE, 0f)
        )
    }
    
    data class HistoricalMetrics(
        val totalTranslations: Int,
        val enhancedTranslations: Int,
        val particleCorrections: Int,
        val totalThinkingTokens: Int,
        val averageConfidence: Float
    )
}