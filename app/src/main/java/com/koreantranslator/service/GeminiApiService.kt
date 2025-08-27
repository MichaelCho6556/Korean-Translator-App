package com.koreantranslator.service

import com.google.gson.annotations.SerializedName
import com.koreantranslator.BuildConfig
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import android.app.Application
import android.util.Log
import com.koreantranslator.util.LoggingOptimizer
import android.widget.Toast
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

// Data classes for Gemini API request/response
data class GeminiRequest(
    @SerializedName("contents") val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GenerationConfig? = null
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>
)

data class GeminiPart(
    @SerializedName("text") val text: String
)

data class GenerationConfig(
    @SerializedName("thinking_config") val thinkingConfig: ThinkingConfig? = null,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int? = null
)

data class ThinkingConfig(
    @SerializedName("thinking_budget") val thinkingBudget: Int
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent,
    @SerializedName("thought") val thought: String? = null  // Captures thinking process
)

// Retrofit interface for Gemini API
interface GeminiApi {
    @Headers("Content-Type: application/json")
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @retrofit2.http.Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
}

@Singleton
class GeminiApiService @Inject constructor() {
    
    // Track which model is currently working
    private var currentActiveModel: String? = null
    
    // Create logging interceptor for debugging
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("GeminiAPI-HTTP", message)
    }.apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
    
    // Certificate pinning for enhanced security against MITM attacks
    private val certificatePinner = CertificatePinner.Builder()
        // Google Trust Services (GTS) CA certificates
        .add("*.googleapis.com", "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=") // GTS CA 1O1
        .add("*.googleapis.com", "sha256/fEzVOUp4dF3gI0ZVPRJhFbSD608BUmNBxJfgOpc7j/s=") // GTS CA 1C3  
        .add("generativelanguage.googleapis.com", "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=") // GTS CA 1O1
        .add("generativelanguage.googleapis.com", "sha256/fEzVOUp4dF3gI0ZVPRJhFbSD608BUmNBxJfgOpc7j/s=") // GTS CA 1C3
        // Backup pins for Google root certificates
        .add("*.googleapis.com", "sha256/hS5jJ4P+iQUErBkvoWBQOd1T7VOAEWJhm6UZOPFNfqr8=") // GlobalSign Root CA
        .add("*.googleapis.com", "sha256/1lgYMXKg74_9FfFUAahQz6QQ3n4-lWi6t1Jq7rvWy0M=") // DigiCert Global Root CA
        .build()

    // Create OkHttpClient with optimized connection pooling, timeouts, and certificate pinning
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .certificatePinner(certificatePinner) // Add certificate pinning for security
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)) // Pool 5 connections for 5 minutes
        .retryOnConnectionFailure(true)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val geminiApi = retrofit.create(GeminiApi::class.java)
    
    suspend fun translate(koreanText: String): TranslationResponse {
        return try {
            // CRITICAL FIX: Add timeout protection and proper cancellation handling
            withTimeout(45000) { // 45 second timeout for complex translations
                withContext(Dispatchers.IO) {
                    try {
                        // DRASTICALLY reduced thinking budget for cost optimization
                        val textComplexity = analyzeTextComplexity(koreanText)
                        val thinkingBudget = when {
                            // Only use minimal thinking for genuinely complex patterns  
                            textComplexity.hasComplexParticles && koreanText.length > 50 -> 128  // Reduced from 512 to 128
                            textComplexity.isLongSentence && koreanText.length > 120 -> 64       // Reduced from 256 to 64
                            textComplexity.hasHonorifics && koreanText.length > 80 -> 32         // Reduced from 128 to 32
                            else -> 0                                                             // No thinking for most translations
                        }
                        
                        Log.d("GeminiApiService", "Text complexity: $textComplexity, Thinking budget: $thinkingBudget")
                        
                        // Optimized concise prompt for cost efficiency
                        val prompt = if (thinkingBudget > 0) {
                            // Use detailed prompt only when thinking budget is allocated
                            """Translate Korean to English, preserving tone and cultural meaning:
                            
                            Key particles: -잖아요 ("I told you"), -네요 (surprise), -거든요 (explanation), -죠 (confirmation)
                            
                            Korean: "$koreanText"
                            
                            Provide only the natural English translation.""".trimIndent()
                        } else {
                            // Simple prompt for basic translations to save tokens
                            "Translate Korean to natural English, preserving tone:\n\n$koreanText"
                        }
                        
                        val translatedText = makeApiCall(prompt, thinkingBudget = thinkingBudget)
                        
                        TranslationResponse(
                            translatedText = translatedText,
                            confidence = calculateConfidence(textComplexity, thinkingBudget),
                            engine = TranslationEngine.GEMINI_FLASH,
                            isEnhanced = true,
                            modelInfo = "${currentActiveModel ?: "Gemini 2.5"} (Thinking: ${thinkingBudget})"
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.w("GeminiApiService", "Translation was cancelled")
                        throw e // Don't wrap cancellation exceptions
                    } catch (e: Exception) {
                        Log.e("GeminiApiService", "Gemini translation error: ${e.message}")
                        throw e
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiApiService", "Translation timeout after 45 seconds")
            throw Exception("Translation request timed out - please try again")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't wrap cancellation exceptions - let them propagate
            Log.w("GeminiApiService", "Translation cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("GeminiApiService", "Gemini translation failed: ${e.message}")
            throw Exception("Gemini translation failed: ${e.message}")
        }
    }
    
    // Analyze text complexity to determine optimal thinking budget
    private fun analyzeTextComplexity(text: String): TextComplexity {
        val complexParticles = listOf("잖아요", "거든요", "더라고요", "네요", "군요", "구나")
        val honorificMarkers = listOf("습니다", "세요", "십시오", "요")
        
        return TextComplexity(
            hasComplexParticles = complexParticles.any { text.contains(it) },
            hasHonorifics = honorificMarkers.any { text.contains(it) },
            isLongSentence = text.length > 50,
            wordCount = text.split(" ").size
        )
    }
    
    private fun calculateConfidence(complexity: TextComplexity, thinkingBudget: Int): Float {
        return when {
            thinkingBudget >= 4096 -> 0.98f
            thinkingBudget >= 2048 -> 0.96f
            thinkingBudget >= 1024 -> 0.94f
            complexity.hasComplexParticles -> 0.90f  // Lower confidence without thinking for complex text
            else -> 0.92f
        }
    }
    
    data class TextComplexity(
        val hasComplexParticles: Boolean,
        val hasHonorifics: Boolean,
        val isLongSentence: Boolean,
        val wordCount: Int
    )
    
    suspend fun enhanceTranslation(
        originalKorean: String,
        basicTranslation: String,
        context: List<String> = emptyList()
    ): TranslationResponse {
        return try {
            // CRITICAL FIX: Add timeout protection for enhancement
            withTimeout(40000) { // 40 second timeout for enhancement
                withContext(Dispatchers.IO) {
                    try {
                        // Analyze complexity for thinking budget
                        val textComplexity = analyzeTextComplexity(originalKorean)
                        val hasContextualAmbiguity = detectContextualAmbiguity(originalKorean, basicTranslation)
                        
                        // Use dynamic thinking for enhancement (more thinking for corrections)
                        val thinkingBudget = when {
                            hasContextualAmbiguity -> -1  // Dynamic thinking for ambiguous cases
                            textComplexity.hasComplexParticles -> 3072
                            context.isNotEmpty() -> 2048  // Context-aware needs more thinking
                            else -> 1024
                        }
                        
                        val contextText = if (context.isNotEmpty()) {
                            """
                            Previous conversation context (use for disambiguation):
                            ${context.takeLast(5).joinToString("\n")}
                            
                            """.trimIndent()
                        } else ""
                        
                        // Enhanced CoT prompt with validation steps
                        val prompt = """
                            ${contextText}You are an expert Korean-to-English translator performing quality enhancement.
                            
                            ANALYSIS PHASE:
                            Original Korean: "$originalKorean"
                            Initial translation: "$basicTranslation"
                            
                            STEP 1 - Error Detection:
                            - Check if sentence-ending particles are correctly translated
                            - Verify honorific levels are preserved
                            - Identify any literal translations that miss idiomatic meaning
                            
                            STEP 2 - Particle Validation:
                            Critical patterns to verify:
                            - "-잖아요" endings MUST convey reminder/assertion ("I told you", "as you know")
                            - "-네요" MUST express realization or surprise
                            - "-거든요" MUST provide explanation or justification
                            - "-죠/지요" MUST seek confirmation
                            
                            STEP 3 - Common Error Corrections:
                            - "제가 그랬잖아요" → "I told you" (NEVER "I did not")
                            - "그렇다" + "-잖아요" → asserting/reminding (NEVER negating)
                            - Zero anaphora: Infer and add missing subjects/objects
                            
                            STEP 4 - Natural Flow Enhancement:
                            - Adjust for English idioms and natural phrasing
                            - Maintain conversational continuity if context provided
                            - Preserve emotional tone and speaker intent
                            
                            FINAL OUTPUT:
                            Provide ONLY the enhanced English translation.
                        """.trimIndent()
                        
                        val enhancedText = makeApiCall(prompt, thinkingBudget = thinkingBudget)
                        
                        TranslationResponse(
                            translatedText = enhancedText,
                            confidence = if (thinkingBudget == -1) 0.97f else calculateConfidence(textComplexity, thinkingBudget),
                            engine = TranslationEngine.HYBRID,
                            isEnhanced = true,
                            modelInfo = "${currentActiveModel ?: "Gemini 2.5"} (Enhanced, Thinking: ${if (thinkingBudget == -1) "Dynamic" else thinkingBudget.toString()})"
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.w("GeminiApiService", "Enhancement was cancelled")
                        throw e // Don't wrap cancellation exceptions
                    } catch (e: Exception) {
                        Log.e("GeminiApiService", "Enhancement error: ${e.message}")
                        throw e
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiApiService", "Enhancement timeout after 40 seconds")
            throw Exception("Enhancement request timed out - please try again")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't wrap cancellation exceptions - let them propagate
            Log.w("GeminiApiService", "Enhancement cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("GeminiApiService", "Translation enhancement failed: ${e.message}")
            throw Exception("Translation enhancement failed: ${e.message}")
        }
    }
    
    // Detect if there's potential ambiguity between original and translation
    private fun detectContextualAmbiguity(korean: String, translation: String): Boolean {
        // Check for signs of potential mistranslation
        val problematicPatterns = mapOf(
            "잖아" to listOf("not", "didn't", "don't"),  // Common mistranslation
            "거든" to listOf("if", "when"),  // Often confused
            "네요" to listOf("is", "are")  // Missing surprise element
        )
        
        for ((koreanPattern, englishProblems) in problematicPatterns) {
            if (korean.contains(koreanPattern)) {
                for (problem in englishProblems) {
                    if (translation.contains(problem, ignoreCase = true)) {
                        Log.d("GeminiApiService", "Detected potential ambiguity: $koreanPattern vs $problem")
                        return true
                    }
                }
            }
        }
        return false
    }
    
    // Try different models in order of preference
    private val modelsToTry = listOf(
        "gemini-2.5-flash",       // Premium quality, best translations
        "gemini-2.5-flash-lite"   // Cost-optimized, high throughput fallback
    )
    
    private suspend fun makeApiCall(
        prompt: String, 
        thinkingBudget: Int = 0,
        maxRetries: Int = 3
    ): String {
        Log.d("GeminiApiService", "Making Gemini API call with prompt length: ${prompt.length}, thinking budget: $thinkingBudget")
        
        // Build generation config with thinking budget if specified
        val generationConfig = if (thinkingBudget != 0) {
            GenerationConfig(
                thinkingConfig = ThinkingConfig(thinkingBudget = thinkingBudget),
                temperature = 0.3f,  // Lower temperature for more consistent translations
                maxOutputTokens = 2048  // FIXED: Increased limit to prevent MAX_TOKENS errors
            )
        } else {
            GenerationConfig(
                temperature = 0.3f,
                maxOutputTokens = 2048  // FIXED: Increased limit to prevent MAX_TOKENS errors
            )
        }
        
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = generationConfig
        )
        
        var lastException: Exception? = null
        
        // Log API key status for debugging (secure)
        Log.d("GeminiApiService", "API Key configured: ${BuildConfig.GEMINI_API_KEY.isNotEmpty()}")
        
        // Try each model
        for (model in modelsToTry) {
            Log.d("GeminiApiService", "========================")
            Log.d("GeminiApiService", "Trying model: $model")
            var retryCount = 0
            
            while (retryCount < maxRetries) {
                try {
                    Log.d("GeminiApiService", "Model $model - Attempt ${retryCount + 1} of $maxRetries")
                    
                    // CRITICAL FIX: Add timeout protection for individual API calls
                    val response = withTimeout(35000) { // 35 second timeout per API call
                        geminiApi.generateContent(
                            model = model,
                            apiKey = BuildConfig.GEMINI_API_KEY,
                            request = request
                        )
                    }
                
                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        val translation = responseBody?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        
                        if (translation.isNullOrEmpty()) {
                            Log.e("GeminiApiService", "Empty response from $model")
                            throw Exception("Received empty translation from $model")
                        }
                        
                        val modelType = if (model.contains("lite", ignoreCase = true)) {
                            "💰 COST-OPTIMIZED"
                        } else {
                            "⭐ PREMIUM QUALITY"
                        }
                        
                        Log.d("GeminiApiService", "═══════════════════════════════════════")
                        Log.d("GeminiApiService", "✓ SUCCESS with $modelType model: $model")
                        Log.d("GeminiApiService", "═══════════════════════════════════════")
                        Log.d("GeminiApiService", "Translation: ${translation.take(100)}...")
                        
                        // Store which model succeeded for reporting
                        currentActiveModel = model
                        return translation.trim()
                    } else {
                        val errorBody = response.errorBody()?.string()
                        val errorMsg = "Model $model error: ${response.code()} - ${response.message()}"
                        Log.e("GeminiApiService", "$errorMsg\nError body: $errorBody")
                    
                        when (response.code()) {
                            401 -> {
                                // Invalid API key - skip to next model
                                Log.e("GeminiApiService", "API key invalid for model: $model")
                                lastException = Exception("Invalid API key for $model")
                                break // Try next model
                            }
                            404 -> {
                                // Model not found - try next model
                                Log.e("GeminiApiService", "Model not found: $model")
                                lastException = Exception("Model $model not available")
                                break // Try next model
                            }
                            429 -> {
                                // Rate limit - wait and retry same model with exponential backoff
                                if (retryCount < maxRetries - 1) {
                                    val waitTime = (2.0.pow(retryCount) * 1000).toLong() + (0..500).random()
                                    Log.d("GeminiApiService", "Rate limited, exponential backoff: ${waitTime}ms")
                                    delay(waitTime)
                                    retryCount++
                                    continue
                                }
                                lastException = Exception("Rate limit for $model")
                                break // Try next model
                            }
                            500, 502, 503 -> {
                                // Server error - retry same model with exponential backoff
                                if (retryCount < maxRetries - 1) {
                                    val waitTime = (2.0.pow(retryCount) * 800).toLong() + (0..300).random()
                                    Log.d("GeminiApiService", "Server error, exponential backoff: ${waitTime}ms")
                                    delay(waitTime)
                                    retryCount++
                                    continue
                                }
                                lastException = Exception("Server error for $model")
                                break // Try next model
                            }
                            else -> {
                                lastException = Exception(errorMsg)
                                break // Try next model
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    lastException = e
                    Log.w("GeminiApiService", "API call timeout for model $model on attempt ${retryCount + 1}")
                    
                    // For timeout, retry same model with exponential backoff
                    if (retryCount < maxRetries - 1) {
                        val waitTime = (2.0.pow(retryCount) * 1200).toLong() + (0..400).random()
                        Log.d("GeminiApiService", "Timeout, exponential backoff: ${waitTime}ms")
                        delay(waitTime)
                        retryCount++
                        continue
                    }
                    
                    // Max retries reached, try next model
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.w("GeminiApiService", "API call cancelled for model $model")
                    throw e // Don't retry on cancellation, propagate immediately
                } catch (e: Exception) {
                    lastException = e
                    Log.e("GeminiApiService", "Exception with model $model: ${e.message}")
                    
                    // For network errors, retry same model
                    if (e.message?.contains("network", true) == true || 
                        e.message?.contains("timeout", true) == true) {
                        if (retryCount < maxRetries - 1) {
                            val waitTime = (retryCount + 1) * 1000L
                            Log.d("GeminiApiService", "Network error, retrying after ${waitTime}ms")
                            delay(waitTime)
                            retryCount++
                            continue
                        }
                    }
                    
                    // Otherwise try next model
                    break
                }
            }
        }
        
        // All models failed
        Log.e("GeminiApiService", "All models failed. Last error: ${lastException?.message}")
        throw lastException ?: Exception("Failed to call any Gemini model")
    }
    
    /**
     * Optimized translation for short contexts with few-shot examples
     * Uses examples to guide the model for better accuracy on common patterns
     */
    suspend fun translateShortContext(koreanText: String): TranslationResponse {
        return try {
            // CRITICAL FIX: Add timeout protection for short context translation
            withTimeout(30000) { // 30 second timeout for short context
                withContext(Dispatchers.IO) {
                    try {
                        // For very short phrases, use targeted few-shot examples
                        val isShortPhrase = koreanText.length < 30
                        val thinkingBudget = if (isShortPhrase) 512 else 1024
                
                // Few-shot examples for common patterns
                val fewShotPrompt = """
                    You are an expert Korean-to-English translator. Use these examples to guide your translation:
                    
                    EXAMPLES OF CORRECT TRANSLATIONS:
                    
                    1. Reminder/Assertion (-잖아요):
                       Korean: "제가 그랬잖아요"
                       English: "I told you" / "I said that"
                       
                       Korean: "우리 약속했잖아요"
                       English: "We made a promise, remember?" / "We promised, as you know"
                    
                    2. Realization (-네요):
                       Korean: "날씨가 좋네요"
                       English: "Oh, the weather is nice" / "The weather is nice, I see"
                       
                       Korean: "벌써 시간이 이렇게 됐네요"
                       English: "Oh, it's already this late" / "Time has flown by"
                    
                    3. Explanation (-거든요):
                       Korean: "제가 지금 바쁘거든요"
                       English: "You see, I'm busy right now" / "It's because I'm busy now"
                       
                       Korean: "아니거든요"
                       English: "Actually, no" / "That's not it"
                    
                    4. Seeking Confirmation (-죠/-지요):
                       Korean: "맞죠?"
                       English: "Right?" / "Isn't that right?"
                       
                       Korean: "알고 있죠?"
                       English: "You know, right?" / "You're aware, aren't you?"
                    
                    5. Common Expressions:
                       Korean: "어떻게 해요?"
                       English: "What should I do?" / "How do I do this?"
                       
                       Korean: "괜찮아요"
                       English: "It's okay" / "I'm fine" / "It's alright"
                    
                    NOW TRANSLATE:
                    Korean: "$koreanText"
                    
                    Apply the patterns from the examples above. Provide ONLY the English translation.
                """.trimIndent()
                
                val translatedText = makeApiCall(
                    prompt = fewShotPrompt,
                    thinkingBudget = thinkingBudget
                )
                
                        TranslationResponse(
                            translatedText = translatedText,
                            confidence = 0.95f,  // High confidence with few-shot examples
                            engine = TranslationEngine.GEMINI_FLASH,
                            isEnhanced = true,
                            modelInfo = "Gemini 2.5 (Few-shot, Thinking: $thinkingBudget)"
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.w("GeminiApiService", "Short context translation was cancelled")
                        throw e // Don't wrap cancellation exceptions
                    } catch (e: Exception) {
                        Log.e("GeminiApiService", "Short context translation error: ${e.message}")
                        throw e
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiApiService", "Short context translation timeout after 30 seconds")
            throw Exception("Short context translation timed out - please try again")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't wrap cancellation exceptions - let them propagate
            Log.w("GeminiApiService", "Short context translation cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("GeminiApiService", "Short context translation failed: ${e.message}")
            throw Exception("Short context translation failed: ${e.message}")
        }
    }
    
    // Get the currently active model (for display/debugging)
    fun getCurrentActiveModel(): String? = currentActiveModel
    
    /**
     * Reconstruct fragmented Korean text using Gemini AI
     * Optimized for speed and accuracy with Korean syllable spacing issues
     */
    suspend fun reconstructKorean(prompt: String): String {
        return try {
            withTimeout(8000) { // REDUCED: 8 second timeout for faster response
                withContext(Dispatchers.IO) {
                    Log.d("GeminiApiService", "Reconstructing Korean text with Gemini...")
                    
                    // OPTIMIZED: Use only Flash lite model for speed, no thinking for reconstruction
                    val result = makeApiCallOptimized(
                        prompt = prompt, 
                        useOnlyLiteModel = true, // Use only lite model for speed
                        maxRetries = 2 // Quick retry on failure
                    )
                    
                    Log.d("GeminiApiService", "Korean reconstruction completed: ${result.length} chars")
                    return@withContext result.trim()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiApiService", "Korean reconstruction timeout after 8 seconds")
            "" // Return empty string to trigger fallback
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w("GeminiApiService", "Korean reconstruction cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("GeminiApiService", "Korean reconstruction failed: ${e.message}")
            "" // Return empty string to trigger fallback
        }
    }
    
    /**
     * OPTIMIZED: Streamlined API call for reconstruction tasks - uses only lite model
     */
    private suspend fun makeApiCallOptimized(
        prompt: String, 
        useOnlyLiteModel: Boolean = false,
        maxRetries: Int = 2
    ): String {
        Log.d("GeminiApiService", "Making optimized Gemini API call for reconstruction")
        
        // Simple generation config for reconstruction (no thinking needed)
        val generationConfig = GenerationConfig(
            temperature = 0.1f,  // Very low temperature for consistent reconstruction
            maxOutputTokens = 512  // Small limit for reconstruction tasks
        )
        
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = generationConfig
        )
        
        var lastException: Exception? = null
        
        // Use only lite model if specified
        val modelsToTry = if (useOnlyLiteModel) {
            listOf("gemini-2.5-flash-lite")
        } else {
            modelsToTry
        }
        
        // Try models with reduced retries for speed
        for (model in modelsToTry) {
            var retryCount = 0
            
            while (retryCount < maxRetries) {
                try {
                    Log.d("GeminiApiService", "Model $model - Attempt ${retryCount + 1} of $maxRetries")
                    
                    val response = withTimeout(6000) { // 6 second timeout per API call
                        geminiApi.generateContent(
                            model = model,
                            apiKey = BuildConfig.GEMINI_API_KEY,
                            request = request
                        )
                    }
                
                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        val translation = responseBody?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        
                        if (translation.isNullOrEmpty()) {
                            Log.e("GeminiApiService", "Empty response from $model")
                            throw Exception("Received empty reconstruction from $model")
                        }
                        
                        Log.d("GeminiApiService", "✓ FAST reconstruction success with $model")
                        currentActiveModel = model
                        return translation.trim()
                    } else {
                        val errorMessage = response.errorBody()?.string() ?: "Unknown error"
                        Log.e("GeminiApiService", "API Error ${response.code()}: $errorMessage")
                        lastException = Exception("HTTP ${response.code()}: $errorMessage")
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w("GeminiApiService", "Model $model timed out on attempt ${retryCount + 1}")
                    lastException = e
                } catch (e: Exception) {
                    Log.e("GeminiApiService", "Model $model failed on attempt ${retryCount + 1}: ${e.message}")
                    lastException = e
                }
                
                retryCount++
                if (retryCount < maxRetries) {
                    delay(500) // Short delay between retries
                }
            }
        }
        
        throw lastException ?: Exception("All models failed for reconstruction")
    }
    
    // Test method to verify API connectivity
    suspend fun testApiConnection(): String {
        return try {
            // CRITICAL FIX: Add timeout protection for API test
            withTimeout(20000) { // 20 second timeout for test
                withContext(Dispatchers.IO) {
                    try {
                        Log.d("GeminiApiService", "Testing Gemini 2.5 Flash API connection...")
                        Log.d("GeminiApiService", "Will try: Premium (2.5 Flash) → Cost-Optimized (2.5 Flash Lite)")
                        Log.d("GeminiApiService", "API Key present: ${BuildConfig.GEMINI_API_KEY.isNotEmpty()}")
                        
                        val testPrompt = "Say 'Hello' in Korean"
                        val result = makeApiCall(testPrompt, maxRetries = 1)
                        
                        Log.d("GeminiApiService", "✓ API Test Success! Response: $result")
                        return@withContext "Success: $result"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.w("GeminiApiService", "API test was cancelled")
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeminiApiService", "✗ API Test Failed!", e)
                        return@withContext "Failed: ${e.message}"
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("GeminiApiService", "API test timeout after 20 seconds")
            "Failed: API test timed out"
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w("GeminiApiService", "API test cancelled")
            throw e
        } catch (e: Exception) {
            Log.e("GeminiApiService", "API test error: ${e.message}")
            "Failed: ${e.message}"
        }
    }
}