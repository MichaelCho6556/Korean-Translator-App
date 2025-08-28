package com.koreantranslator.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.koreantranslator.BuildConfig
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import android.util.Log
import android.widget.Toast
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationService @Inject constructor(
    private val mlKitTranslationService: MLKitTranslationService,
    private val geminiApiService: GeminiApiService,
    private val koreanLinguisticService: KoreanLinguisticService,
    private val translationMetrics: TranslationMetrics,
    private val translationCacheManager: TranslationCacheManager,
    private val networkStateMonitor: NetworkStateMonitor,
    private val circuitBreakerService: CircuitBreakerService,
    private val errorReportingService: ErrorReportingService,
    @ApplicationContext private val context: Context
) {
    
    // Store conversation history for context-aware translations
    private val conversationHistory = mutableListOf<ConversationEntry>()
    private val maxHistorySize = 10 // Keep last 10 exchanges for context
    
    // Enhanced conversation entry with metadata
    data class ConversationEntry(
        val korean: String,
        val english: String,
        val sentenceType: String? = null, // statement, question, reminder, explanation
        val emotionalTone: String? = null, // assertive, questioning, surprised, explanatory
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Real-time enhancement tracking
    private var lastEnhancedText = ""
    private var lastEnhancedTranslation = ""
    private val realtimeEnhancementCache = mutableMapOf<String, String>() // Korean -> Enhanced English
    
    suspend fun translateHybrid(koreanText: String): Flow<TranslationResponse> = channelFlow {
        
        // Check comprehensive cache first for instant results
        translationCacheManager.get(koreanText)?.let { cachedResult ->
            // Record cache hit
            translationMetrics.recordTranslation(
                koreanText = koreanText,
                response = cachedResult,
                fromCache = true
            )
            Log.d("TranslationService", "Cache hit for: ${koreanText.take(30)}...")
            send(cachedResult)
            return@channelFlow
        }
        
        // Perform linguistic analysis
        val linguisticAnalysis = koreanLinguisticService.analyzeText(koreanText)
        Log.d("TranslationService", "Linguistic analysis confidence: ${linguisticAnalysis.confidence}")
        linguisticAnalysis.translationHints.forEach { hint ->
            Log.d("TranslationService", "Linguistic hint: $hint")
        }
        
        supervisorScope {
            // Step 1: Get ML Kit translation immediately (< 50ms)
            try {
                Log.d("TranslationService", "Starting translation for: \"${koreanText.take(50)}...\"")
                val mlKitResult = mlKitTranslationService.translate(koreanText)
                Log.d("TranslationService", "ML Kit translation: \"${mlKitResult.translatedText.take(50)}...\"")
                
                // With Soniox's 95.7% accuracy, only apply corrections for specific sentence-ending particles
                // that fundamentally change meaning (like -잖아요)
                val processedMlKitResult = if (linguisticAnalysis.confidence > 0.9f && linguisticAnalysis.suggestedTranslation != null) {
                    // High confidence correction for critical patterns only
                    Log.d("TranslationService", "Applying high-confidence linguistic correction")
                    mlKitResult.copy(
                        translatedText = linguisticAnalysis.suggestedTranslation,
                        confidence = 0.85f
                    )
                } else {
                    // Trust the translation as-is
                    mlKitResult
                }
                
                send(processedMlKitResult)
                
                // Step 2: If online, enhance with Gemini in background
                val networkAvailable = networkStateMonitor.isNetworkSuitableForApiCalls()
                Log.d("TranslationService", "Network suitable for API calls: $networkAvailable")
                
                // Use network monitoring service for better reliability
                if (networkAvailable && circuitBreakerService.isServiceAvailable("gemini")) {
                    Log.d("TranslationService", "Network available, attempting Gemini enhancement")
                    Log.d("TranslationService", "API Key configured: ${BuildConfig.GEMINI_API_KEY.isNotEmpty()}")
                    
                    launch {
                        Log.d("TranslationService", "Gemini enhancement coroutine started")
                        Toast.makeText(context, "Enhancing translation with Gemini 2.5...", Toast.LENGTH_SHORT).show()
                        
                        val startTime = System.currentTimeMillis()
                        
                        try {
                            // Prepare rich conversation context for Gemini
                            val contextMessages = conversationHistory.takeLast(5).map { entry ->
                                val metadata = if (entry.sentenceType != null || entry.emotionalTone != null) {
                                    " [${entry.sentenceType ?: ""}/${entry.emotionalTone ?: ""}]"
                                } else ""
                                "Korean: ${entry.korean}$metadata\nEnglish: ${entry.english}"
                            }
                            Log.d("TranslationService", "Using ${contextMessages.size} context messages")
                            
                            // Optimized translation strategy with circuit breaker protection
                            val (geminiResult, thinkingBudget) = try {
                                circuitBreakerService.executeWithCircuitBreaker("gemini") {
                                    when {
                                        // Short phrases - no thinking needed, use direct translation
                                        koreanText.length < 30 && contextMessages.isEmpty() -> {
                                            Log.d("TranslationService", "Using direct translation for short text")
                                            geminiApiService.translate(koreanText) to 0
                                        }
                                        
                                        // Only use thinking for genuinely complex text with context
                                        linguisticAnalysis.confidence > 0.9f && contextMessages.isNotEmpty() && koreanText.length > 50 -> {
                                            Log.d("TranslationService", "Using enhancement with minimal thinking for complex context")
                                            val budget = 256  // Drastically reduced from 3072/2048
                                            geminiApiService.enhanceTranslation(
                                                originalKorean = koreanText,
                                                basicTranslation = mlKitResult.translatedText,
                                                context = contextMessages
                                            ) to budget
                                        }
                                        
                                        // Default: Direct translation without thinking for cost optimization
                                        else -> {
                                            Log.d("TranslationService", "Using direct translation without thinking")
                                            geminiApiService.translate(koreanText) to 0
                                        }
                                    }
                                }
                            } catch (e: CircuitBreakerService.CircuitBreakerOpenException) {
                                Log.w("TranslationService", "Gemini circuit breaker is open, using ML Kit result")
                                errorReportingService.reportTranslationError(
                                    title = "Gemini Service Unavailable",
                                    message = "Circuit breaker open due to repeated failures",
                                    throwable = e,
                                    koreanText = koreanText,
                                    translationEngine = "Gemini"
                                )
                                // Return ML Kit result as fallback
                                return@launch
                            } catch (e: Exception) {
                                Log.e("TranslationService", "Gemini translation failed, using ML Kit result", e)
                                errorReportingService.reportTranslationError(
                                    title = "Gemini Translation Failed",
                                    message = "Falling back to ML Kit result",
                                    throwable = e,
                                    koreanText = koreanText,
                                    translationEngine = "Gemini"
                                )
                                // Return ML Kit result as fallback
                                return@launch
                            }
                            
                            val responseTime = System.currentTimeMillis() - startTime
                            Log.d("TranslationService", "Gemini API call completed in ${responseTime}ms")
                            
                            Log.d("TranslationService", "✓ Gemini 2.0 Flash enhanced: \"${geminiResult.translatedText.take(100)}...\"")
                            Log.d("TranslationService", "Translation engine: ${geminiResult.engine}, Enhanced: ${geminiResult.isEnhanced}")
                            
                            // Trust Gemini's enhanced translation - it already has improved prompts
                            // No need for additional post-processing with Soniox's high accuracy
                            
                            // Record metrics
                            translationMetrics.recordTranslation(
                                koreanText = koreanText,
                                response = geminiResult,
                                mlKitResult = mlKitResult.translatedText,
                                responseTimeMs = responseTime,
                                wasEnhanced = true,
                                thinkingBudget = thinkingBudget
                            )
                            
                            // Update conversation history
                            addToConversationHistory(koreanText, geminiResult.translatedText)
                            
                            // Cache the enhanced result using comprehensive cache manager
                            translationCacheManager.put(koreanText, geminiResult, isFrequent = false)
                            
                            // Send enhanced translation
                            send(geminiResult)
                            Log.d("TranslationService", "Successfully emitted Gemini enhanced translation")
                        } catch (e: Exception) {
                            // If Gemini fails, we still have the ML Kit result
                            // Log the error for debugging
                            Log.e("TranslationService", "✗ Gemini enhancement failed!", e)
                            Log.e("TranslationService", "Error details: ${e.message}")
                            Log.e("TranslationService", "Falling back to ML Kit translation")
                            
                            // Show error to user
                            Toast.makeText(context, "Gemini failed: ${e.message}", Toast.LENGTH_LONG).show()
                            
                            // Still add ML Kit result to history for continuity
                            addToConversationHistory(koreanText, mlKitResult.translatedText)
                        }
                    }
                } else {
                    // Offline mode - cache the ML Kit result
                    Log.w("TranslationService", "⚠ Network not available, using ML Kit only")
                    Toast.makeText(context, "Network not available - using offline translation", Toast.LENGTH_SHORT).show()
                    translationCacheManager.put(koreanText, mlKitResult)
                }
                
            } catch (e: Exception) {
                // If ML Kit fails and we're online, try Gemini directly
                if (isNetworkAvailable()) {
                    try {
                        Log.d("TranslationService", "ML Kit failed, trying Gemini 2.5 Flash directly")
                        val geminiResult = geminiApiService.translate(koreanText)
                        
                        // Update conversation history
                        addToConversationHistory(koreanText, geminiResult.translatedText)
                        
                        translationCacheManager.put(koreanText, geminiResult)
                        send(geminiResult)
                    } catch (geminiError: Exception) {
                        throw Exception("Translation failed: ${e.message}")
                    }
                } else {
                    throw Exception("Translation unavailable offline: ${e.message}")
                }
            }
        }
    }
    
    suspend fun translateWithMLKit(koreanText: String): TranslationResponse {
        return mlKitTranslationService.translate(koreanText)
    }
    
    suspend fun translateWithGemini(koreanText: String): TranslationResponse {
        return geminiApiService.translate(koreanText)
    }
    
    suspend fun enhanceTranslation(
        originalText: String,
        mlKitTranslation: String
    ): TranslationResponse {
        return geminiApiService.enhanceTranslation(originalText, mlKitTranslation)
    }
    
    /**
     * Optimized enhancement for real-time streaming
     * This method intelligently handles incremental text updates to minimize API calls
     */
    suspend fun enhanceTranslationIncremental(
        koreanText: String,
        forceRefresh: Boolean = false
    ): TranslationResponse {
        // Check real-time cache first
        if (!forceRefresh) {
            realtimeEnhancementCache[koreanText]?.let { cachedTranslation ->
                Log.d("TranslationService", "Using cached real-time enhancement")
                return TranslationResponse(
                    translatedText = cachedTranslation,
                    confidence = 0.95f,
                    engine = TranslationEngine.HYBRID,
                    isEnhanced = true,
                    modelInfo = "Cached Enhancement"
                )
            }
        }
        
        try {
            // Get ML Kit translation first
            val mlKitResult = mlKitTranslationService.translate(koreanText)
            
            // Check if we can reuse part of the previous enhancement
            if (lastEnhancedText.isNotEmpty() && koreanText.startsWith(lastEnhancedText)) {
                // Text has been appended - we can optimize by only translating the new part
                val newPortion = koreanText.substring(lastEnhancedText.length).trim()
                
                if (newPortion.isNotEmpty()) {
                    Log.d("TranslationService", "Incremental enhancement - only translating new portion: $newPortion")
                    
                    // Translate only the new portion
                    val newPortionTranslation = geminiApiService.translate(newPortion)
                    
                    // Combine with previous translation
                    val combinedTranslation = "$lastEnhancedTranslation ${newPortionTranslation.translatedText}"
                    
                    // Cache the result
                    realtimeEnhancementCache[koreanText] = combinedTranslation
                    lastEnhancedText = koreanText
                    lastEnhancedTranslation = combinedTranslation
                    
                    return TranslationResponse(
                        translatedText = combinedTranslation,
                        confidence = 0.96f,
                        engine = TranslationEngine.HYBRID,
                        isEnhanced = true,
                        modelInfo = "Incremental Enhancement"
                    )
                }
            }
            
            // Full enhancement needed
            Log.d("TranslationService", "Full enhancement for real-time translation")
            val enhancedResult = geminiApiService.enhanceTranslation(
                originalKorean = koreanText,
                basicTranslation = mlKitResult.translatedText,
                context = conversationHistory.takeLast(3).map { entry ->
                    val metadata = if (entry.sentenceType != null || entry.emotionalTone != null) {
                        " [${entry.sentenceType ?: ""}/${entry.emotionalTone ?: ""}]"
                    } else ""
                    "Korean: ${entry.korean}$metadata\nEnglish: ${entry.english}"
                }
            )
            
            // Cache the result
            realtimeEnhancementCache[koreanText] = enhancedResult.translatedText
            lastEnhancedText = koreanText
            lastEnhancedTranslation = enhancedResult.translatedText
            
            // Limit cache size
            if (realtimeEnhancementCache.size > 50) {
                // Remove oldest entries
                val entriesToRemove = realtimeEnhancementCache.keys.take(25)
                entriesToRemove.forEach { realtimeEnhancementCache.remove(it) }
            }
            
            return enhancedResult
            
        } catch (e: Exception) {
            Log.e("TranslationService", "Incremental enhancement failed", e)
            // Fallback to ML Kit
            return mlKitTranslationService.translate(koreanText)
        }
    }
    
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // For API 23+ use NetworkCapabilities
        val network = connectivityManager.activeNetwork
        if (network != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null) {
                // Check if network has internet capability (more reliable than transport types)
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                       capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        
        // Fallback for older APIs or if above checks fail
        @Suppress("DEPRECATION")
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo?.isConnected == true
    }
    
    // Add translation to conversation history for context
    private fun addToConversationHistory(korean: String, english: String) {
        // Analyze the sentence for metadata
        val linguisticAnalysis = koreanLinguisticService.analyzeText(korean)
        
        val sentenceType = when {
            korean.endsWith("?") || korean.endsWith("까요") || korean.endsWith("나요") -> "question"
            korean.endsWith("잖아요") || korean.endsWith("잖아") -> "reminder"
            korean.endsWith("거든요") || korean.endsWith("거든") -> "explanation"
            else -> "statement"
        }
        
        val emotionalTone = linguisticAnalysis.sentenceEnding?.tone ?: when {
            korean.endsWith("잖아요") -> "assertive/reminding"
            korean.endsWith("네요") -> "surprised/acknowledging"
            korean.endsWith("거든요") -> "explanatory"
            korean.endsWith("죠") || korean.endsWith("지요") -> "confirming"
            else -> "neutral"
        }
        
        conversationHistory.add(ConversationEntry(
            korean = korean,
            english = english,
            sentenceType = sentenceType,
            emotionalTone = emotionalTone
        ))
        
        // Keep only the most recent exchanges
        if (conversationHistory.size > maxHistorySize) {
            conversationHistory.removeAt(0)
        }
        
        Log.d("TranslationService", "Conversation history updated, size: ${conversationHistory.size}")
        Log.d("TranslationService", "Latest entry - Type: $sentenceType, Tone: $emotionalTone")
    }
    
    // Clear conversation history (useful when starting a new conversation)
    fun clearConversationHistory() {
        conversationHistory.clear()
        realtimeEnhancementCache.clear()
        lastEnhancedText = ""
        lastEnhancedTranslation = ""
        Log.d("TranslationService", "Conversation history and real-time cache cleared")
    }
    
    // Get current conversation context (for debugging or display)
    fun getConversationContext(): List<ConversationEntry> {
        return conversationHistory.toList()
    }
    
    // Initialize the comprehensive cache system
    suspend fun initializeCacheSystem() {
        translationCacheManager.initialize()
        Log.d("TranslationService", "Translation cache system initialized")
    }
    
    // Clear cache if needed and perform maintenance
    suspend fun clearCacheIfNeeded() {
        val sizeInfo = translationCacheManager.getCacheSizeInfo()
        if (sizeInfo.totalSize > 500) {  // Clear if cache is getting large
            translationCacheManager.clearExpired()
        }
        val stats = translationCacheManager.getStatistics()
        Log.d("TranslationService", "Cache stats - Hit rate: ${(stats.hitRate() * 100).toInt()}%, Total size: ${sizeInfo.totalSize}")
    }
}