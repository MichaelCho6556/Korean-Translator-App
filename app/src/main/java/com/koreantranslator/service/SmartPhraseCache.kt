package com.koreantranslator.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.koreantranslator.service.SonioxStreamingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart phrase caching system that learns from user interactions and high-confidence transcriptions
 * to continuously improve Soniox speech recognition accuracy.
 * 
 * Features:
 * - Learns from high-confidence transcriptions automatically
 * - Tracks phrase frequency and success rates
 * - Updates Soniox speech contexts dynamically
 * - Persists learned phrases across app sessions
 */
@Singleton
class SmartPhraseCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "SmartPhraseCache"
        private const val PREFS_NAME = "smart_phrase_cache"
        private const val KEY_LEARNED_PHRASES = "learned_phrases"
        private const val MIN_FREQUENCY_FOR_BOOST = 3
        private const val MAX_DYNAMIC_PHRASES = 50
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.9f
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Core Korean phrases that should always be boosted
    private val corePhrases = mapOf(
        "안녕하세요" to 25f,
        "감사합니다" to 25f,
        "죄송합니다" to 25f,
        "괜찮아요" to 20f,
        "알겠습니다" to 20f,
        "잠시만요" to 20f,
        "실례합니다" to 20f,
        "고마워요" to 15f,
        "미안해요" to 15f,
        "어서오세요" to 15f,
        "잠깐만요" to 15f,
        "도와주세요" to 15f,
        "그래요" to 10f,
        "네" to 10f,
        "아니요" to 10f,
        "맞아요" to 10f,
        "모르겠어요" to 10f
    )
    
    // Dynamic phrases learned from usage
    private val learnedPhrases = mutableMapOf<String, PhraseStats>()
    private val _speechContexts = MutableStateFlow<List<SonioxStreamingService.SpeechContext>>(emptyList())
    val speechContexts: StateFlow<List<SonioxStreamingService.SpeechContext>> = _speechContexts
    
    // Statistics
    private var phrasesLearned = 0
    private var contextsUpdated = 0
    
    init {
        loadPersistedPhrases()
        updateSpeechContexts()
        Log.i(TAG, "SmartPhraseCache initialized with ${learnedPhrases.size} learned phrases")
    }
    
    /**
     * Learn from a high-confidence transcription
     */
    fun learnFromTranscription(text: String, confidence: Float) {
        if (confidence < HIGH_CONFIDENCE_THRESHOLD) return
        
        Log.d(TAG, "Learning from high-confidence transcription: '$text' (${(confidence * 100).toInt()}%)")
        
        // Extract meaningful phrases (2+ words)
        val phrases = extractMeaningfulPhrases(text)
        var phrasesAdded = false
        
        phrases.forEach { phrase ->
            val stats = learnedPhrases.getOrPut(phrase) { 
                PhraseStats(phrase, 0, 0f, System.currentTimeMillis())
            }
            
            // Update statistics
            stats.frequency++
            stats.averageConfidence = (stats.averageConfidence * (stats.frequency - 1) + confidence) / stats.frequency
            stats.lastSeen = System.currentTimeMillis()
            
            if (stats.frequency >= MIN_FREQUENCY_FOR_BOOST) {
                phrasesAdded = true
                Log.d(TAG, "Phrase qualified for boost: '$phrase' (freq: ${stats.frequency}, conf: ${(stats.averageConfidence * 100).toInt()}%)")
            }
        }
        
        if (phrasesAdded) {
            phrasesLearned++
            updateSpeechContexts()
            persistPhrases()
        }
    }
    
    /**
     * Learn from user corrections (when user fixes a transcription)
     */
    fun learnFromUserCorrection(original: String, corrected: String) {
        Log.d(TAG, "Learning from user correction: '$original' -> '$corrected'")
        
        // The corrected version is what the user actually said
        learnFromTranscription(corrected, 1.0f) // User correction = 100% confidence
        
        // Also reduce boost for the incorrectly recognized phrase if it exists
        val incorrectPhrases = extractMeaningfulPhrases(original)
        incorrectPhrases.forEach { phrase ->
            learnedPhrases[phrase]?.let { stats ->
                // Reduce confidence and frequency to de-prioritize incorrect recognition
                stats.averageConfidence *= 0.8f
                if (stats.frequency > 1) stats.frequency--
                Log.d(TAG, "Reduced priority for incorrect phrase: '$phrase'")
            }
        }
        
        updateSpeechContexts()
        persistPhrases()
    }
    
    /**
     * Get current speech contexts for Soniox configuration
     */
    fun buildSpeechContexts(): List<SonioxStreamingService.SpeechContext> {
        val contexts = mutableListOf<SonioxStreamingService.SpeechContext>()
        
        // Core phrases context (always included)
        contexts.add(
            SonioxStreamingService.SpeechContext(
                phrases = corePhrases.keys.toList(),
                boost = 20f
            )
        )
        
        // High-frequency learned phrases
        val highFrequencyPhrases = learnedPhrases.values
            .filter { it.frequency >= MIN_FREQUENCY_FOR_BOOST && it.averageConfidence >= 0.7f }
            .sortedByDescending { it.frequency * it.averageConfidence }
            .take(MAX_DYNAMIC_PHRASES)
            .map { it.phrase }
        
        if (highFrequencyPhrases.isNotEmpty()) {
            contexts.add(
                SonioxStreamingService.SpeechContext(
                    phrases = highFrequencyPhrases,
                    boost = 15f
                )
            )
            Log.d(TAG, "Added ${highFrequencyPhrases.size} dynamic phrases to speech context")
        }
        
        // Recent phrases (said in last 24 hours)
        val recentPhrases = learnedPhrases.values
            .filter { 
                System.currentTimeMillis() - it.lastSeen < 24 * 60 * 60 * 1000 && // Last 24 hours
                it.frequency >= 2 && 
                it.averageConfidence >= 0.8f 
            }
            .sortedByDescending { it.lastSeen }
            .take(20)
            .map { it.phrase }
        
        if (recentPhrases.isNotEmpty()) {
            contexts.add(
                SonioxStreamingService.SpeechContext(
                    phrases = recentPhrases,
                    boost = 10f
                )
            )
            Log.d(TAG, "Added ${recentPhrases.size} recent phrases to speech context")
        }
        
        return contexts
    }
    
    /**
     * Extract meaningful phrases from text (2+ words, Korean content)
     */
    private fun extractMeaningfulPhrases(text: String): List<String> {
        val phrases = mutableSetOf<String>()
        val words = text.trim().split(Regex("\\s+"))
        
        // Extract 2-4 word phrases
        for (length in 2..4) {
            for (i in 0..words.size - length) {
                val phrase = words.subList(i, i + length).joinToString(" ")
                
                // Must contain Korean characters and be meaningful length
                if (phrase.contains(Regex("[가-힣]")) && phrase.length >= 4) {
                    phrases.add(phrase)
                }
            }
        }
        
        return phrases.toList()
    }
    
    /**
     * Update speech contexts and notify observers
     */
    private fun updateSpeechContexts() {
        val contexts = buildSpeechContexts()
        _speechContexts.value = contexts
        contextsUpdated++
        
        Log.d(TAG, "Speech contexts updated: ${contexts.size} contexts with ${contexts.sumOf { it.phrases.size }} total phrases")
    }
    
    /**
     * Persist learned phrases to SharedPreferences
     */
    private fun persistPhrases() {
        try {
            val serializedPhrases = learnedPhrases.map { (phrase, stats) ->
                "$phrase|${stats.frequency}|${stats.averageConfidence}|${stats.lastSeen}"
            }.joinToString("\n")
            
            prefs.edit()
                .putString(KEY_LEARNED_PHRASES, serializedPhrases)
                .apply()
            
            Log.d(TAG, "Persisted ${learnedPhrases.size} learned phrases")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist phrases", e)
        }
    }
    
    /**
     * Load persisted phrases from SharedPreferences
     */
    private fun loadPersistedPhrases() {
        try {
            val serializedPhrases = prefs.getString(KEY_LEARNED_PHRASES, "") ?: ""
            if (serializedPhrases.isBlank()) return
            
            serializedPhrases.split("\n").forEach { line ->
                val parts = line.split("|")
                if (parts.size == 4) {
                    val phrase = parts[0]
                    val frequency = parts[1].toIntOrNull() ?: 0
                    val confidence = parts[2].toFloatOrNull() ?: 0f
                    val lastSeen = parts[3].toLongOrNull() ?: System.currentTimeMillis()
                    
                    learnedPhrases[phrase] = PhraseStats(phrase, frequency, confidence, lastSeen)
                }
            }
            
            Log.d(TAG, "Loaded ${learnedPhrases.size} persisted phrases")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted phrases", e)
        }
    }
    
    /**
     * Get cache statistics for monitoring
     */
    fun getStatistics(): CacheStatistics {
        val totalPhrases = learnedPhrases.size
        val activePhrases = learnedPhrases.values.count { it.frequency >= MIN_FREQUENCY_FOR_BOOST }
        val averageConfidence = learnedPhrases.values.map { it.averageConfidence }.average().toFloat()
        
        return CacheStatistics(
            totalLearnedPhrases = totalPhrases,
            activeBoostedPhrases = activePhrases,
            phrasesLearned = phrasesLearned,
            contextsUpdated = contextsUpdated,
            averageConfidence = averageConfidence
        )
    }
    
    /**
     * Clear cache (useful for testing)
     */
    fun clearCache() {
        learnedPhrases.clear()
        prefs.edit().remove(KEY_LEARNED_PHRASES).apply()
        updateSpeechContexts()
        Log.d(TAG, "Cache cleared")
    }
    
    data class PhraseStats(
        val phrase: String,
        var frequency: Int,
        var averageConfidence: Float,
        var lastSeen: Long
    )
    
    data class CacheStatistics(
        val totalLearnedPhrases: Int,
        val activeBoostedPhrases: Int,
        val phrasesLearned: Int,
        val contextsUpdated: Int,
        val averageConfidence: Float
    )
}