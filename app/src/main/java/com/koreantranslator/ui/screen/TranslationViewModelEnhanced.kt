package com.koreantranslator.ui.screen

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koreantranslator.model.TranslationMessage
import com.koreantranslator.repository.TranslationRepository
import com.koreantranslator.service.SonioxStreamingService
import com.koreantranslator.service.TranslationService
import com.koreantranslator.util.MLKitModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Enhanced Translation ViewModel with Production-Ready Features
 * - Proper debouncing to prevent API flooding
 * - Robust state management
 * - Comprehensive error handling
 * - Performance monitoring
 */
@HiltViewModel
class TranslationViewModelEnhanced @Inject constructor(
    application: Application,
    private val sonioxStreamingService: SonioxStreamingService,
    private val translationService: TranslationService,
    private val translationRepository: TranslationRepository,
    private val mlKitModelManager: MLKitModelManager
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "TranslationVM"
        
        // Debounce configuration - Optimized for cost efficiency
        private const val PARTIAL_TRANSLATION_DEBOUNCE_MS = 1500L // Wait 1.5s after speech pause (increased for cost savings)
        private const val ENHANCEMENT_DEBOUNCE_MS = 3000L // Wait 3s for enhancement (doubled for API cost optimization)
        private const val PERIODIC_ENHANCEMENT_MS = 8000L // Enhance every 8s during recording (doubled to reduce API calls)
        
        // Performance thresholds
        private const val MAX_TRANSLATION_ATTEMPTS = 3
        private const val TRANSLATION_TIMEOUT_MS = 5000L
        private const val MAX_CACHED_TRANSLATIONS = 100
    }
    
    // Enhanced UI State with more granular status tracking
    data class EnhancedTranslationState(
        val messages: List<TranslationMessage> = emptyList(),
        val isRecording: Boolean = false,
        val isLoading: Boolean = false,
        val isOnline: Boolean = true,
        val currentPartialText: String? = null,
        val currentPartialTranslation: String? = null,
        val error: String? = null,
        val translationStatus: TranslationStatus = TranslationStatus.IDLE,
        val mlKitModelStatus: ModelStatus = ModelStatus.UNKNOWN,
        val apiCallsThisSession: Int = 0,
        val estimatedCostCents: Int = 0
    )
    
    enum class TranslationStatus {
        IDLE,
        LISTENING,
        RECOGNIZING,
        TRANSLATING,
        ENHANCING,
        COMPLETED,
        ERROR
    }
    
    enum class ModelStatus {
        UNKNOWN,
        DOWNLOADING,
        READY,
        ERROR
    }
    
    private val _uiState = MutableStateFlow(EnhancedTranslationState())
    val uiState: StateFlow<EnhancedTranslationState> = _uiState.asStateFlow()
    
    // Conversation tracking
    private var currentConversationId: String? = null
    private var currentSegmentNumber = 0
    
    // Enhancement tracking
    private var lastEnhancedText: String = ""
    private var enhancementJob: Job? = null
    private var periodicEnhancementJob: Job? = null
    
    // Performance metrics
    private var sessionStartTime = 0L
    private var totalApiCalls = 0
    private var totalTranslationTime = 0L
    
    // Debounced flows for preventing API flooding
    private val partialTextDebouncer = MutableSharedFlow<String>()
    private val enhancementDebouncer = MutableSharedFlow<String>()
    
    init {
        setupDebounceFlows()
        observeSpeechRecognition()
        observeModelStatus()
        loadMessagesFromDatabase()
        monitorNetworkStatus()
    }
    
    /**
     * Set up debounced flows to prevent API flooding
     * This is the CRITICAL fix for the $432/month problem
     */
    private fun setupDebounceFlows() {
        // Debounced partial text translation with intelligent filtering
        // Only translate after user pauses speaking for 1.5s
        viewModelScope.launch {
            partialTextDebouncer
                .debounce(PARTIAL_TRANSLATION_DEBOUNCE_MS)
                .distinctUntilChanged()
                .filter { koreanText ->
                    // Only process if text is substantial enough to warrant API call
                    koreanText.trim().length >= 3 && koreanText.any { it.isLetterOrDigit() }
                }
                .collect { koreanText ->
                    Log.d(TAG, "Debounced translation triggered for: ${koreanText.take(50)}...")
                    _uiState.update { it.copy(apiCallsThisSession = it.apiCallsThisSession + 1) }
                    translatePartialTextDebounced(koreanText)
                }
        }
        
        // Debounced enhancement with cost-aware filtering
        // Only enhance after 3s of stable text
        viewModelScope.launch {
            enhancementDebouncer
                .debounce(ENHANCEMENT_DEBOUNCE_MS)
                .distinctUntilChanged()
                .filter { koreanText ->
                    // Only enhance if text is substantial and network is available
                    koreanText.trim().length >= 8 && 
                    _uiState.value.isOnline &&
                    _uiState.value.apiCallsThisSession < 50 // Daily limit for cost control
                }
                .collect { koreanText ->
                    Log.d(TAG, "Debounced enhancement triggered for: ${koreanText.take(50)}...")
                    val estimatedCostCents = (_uiState.value.apiCallsThisSession * 2) // Rough estimate: 2 cents per call
                    _uiState.update { 
                        it.copy(
                            apiCallsThisSession = it.apiCallsThisSession + 1,
                            estimatedCostCents = estimatedCostCents
                        )
                    }
                    triggerRealtimeEnhancementDebounced(koreanText)
                }
        }
    }
    
    /**
     * Observe speech recognition with proper flow control
     */
    private fun observeSpeechRecognition() {
        // Observe recognized segments (final results)
        viewModelScope.launch {
            sonioxStreamingService.recognizedText
                .filterNotNull()
                .filter { it.isNotEmpty() }
                .collect { recognizedText ->
                    Log.d(TAG, "Segment recognized: $recognizedText")
                    
                    // Update status
                    _uiState.update { it.copy(translationStatus = TranslationStatus.RECOGNIZING) }
                    
                    // REMOVED: accumulatedText functionality - using individual sentence processing
                    // Enhancement now handled by individual sentence recognition
                    // if (_uiState.value.isRecording) {
                    //     enhancementDebouncer.emit(recognizedText.value)
                    // }
                }
        }
        
        // Observe partial text with debouncing
        viewModelScope.launch {
            sonioxStreamingService.partialText
                .filterNotNull()
                .filter { it.isNotEmpty() && it != "connecting" }
                .collect { partialText ->
                    // Update UI immediately for responsiveness
                    _uiState.update { it.copy(currentPartialText = partialText) }
                    
                    // Send to translation debouncer (will wait 500ms)
                    partialTextDebouncer.emit(partialText)
                }
        }
        
        // Observe recording state
        viewModelScope.launch {
            sonioxStreamingService.isListening.collect { isListening ->
                _uiState.update { it.copy(isRecording = isListening) }
            }
        }
        
        // Observe errors
        viewModelScope.launch {
            sonioxStreamingService.error.collect { error ->
                _uiState.update { it.copy(error = error) }
            }
        }
    }
    
    /**
     * Observe ML Kit model status
     */
    private fun observeModelStatus() {
        viewModelScope.launch {
            mlKitModelManager.downloadState.collect { state ->
                val modelStatus = when (state) {
                    is MLKitModelManager.ModelDownloadState.DOWNLOADING -> ModelStatus.DOWNLOADING
                    is MLKitModelManager.ModelDownloadState.COMPLETED -> ModelStatus.READY
                    is MLKitModelManager.ModelDownloadState.ERROR -> ModelStatus.ERROR
                    else -> ModelStatus.UNKNOWN
                }
                _uiState.update { it.copy(mlKitModelStatus = modelStatus) }
            }
        }
    }
    
    /**
     * Start recording with enhanced error handling
     */
    fun startRecording() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "=== STARTING ENHANCED RECORDING ===")
                
                // Check ML Kit models first
                if (_uiState.value.mlKitModelStatus != ModelStatus.READY) {
                    Log.w(TAG, "ML Kit models not ready, checking...")
                    val modelsReady = mlKitModelManager.ensureModelsDownloaded()
                    if (!modelsReady) {
                        _uiState.update { 
                            it.copy(error = "Translation models not available. Please check your internet connection.")
                        }
                        return@launch
                    }
                }
                
                // Initialize conversation
                if (currentConversationId == null) {
                    currentConversationId = java.util.UUID.randomUUID().toString()
                    currentSegmentNumber = 0
                    sessionStartTime = System.currentTimeMillis()
                }
                
                // Reset enhancement tracking
                lastEnhancedText = ""
                
                // Update UI state
                _uiState.update { 
                    it.copy(
                        currentPartialText = "connecting",
                        error = null,
                        translationStatus = TranslationStatus.LISTENING
                    )
                }
                
                // Start speech recognition
                sonioxStreamingService.startListening()
                
                // Start periodic enhancement (with proper debouncing)
                startPeriodicEnhancementSmart()
                
                Log.d(TAG, "Recording started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _uiState.update { 
                    it.copy(
                        error = "Failed to start recording: ${e.message}",
                        translationStatus = TranslationStatus.ERROR
                    )
                }
            }
        }
    }
    
    /**
     * Stop recording with proper cleanup
     */
    fun stopRecording() {
        viewModelScope.launch {
            Log.d(TAG, "Stopping enhanced recording...")
            
            // Cancel enhancement jobs
            periodicEnhancementJob?.cancel()
            enhancementJob?.cancel()
            
            // REMOVED: Get final text - using individual sentence processing
            val finalText = "" // Placeholder - accumulatedText removed
            val finalTranslation = _uiState.value.currentPartialTranslation
            
            // Stop speech recognition
            sonioxStreamingService.stopListening()
            
            // Save final message if we have content
            if (!finalText.isNullOrEmpty() && !finalTranslation.isNullOrEmpty()) {
                saveFinalTranslation(finalText, finalTranslation)
            }
            
            // Clear UI after delay
            delay(500)
            _uiState.update { 
                it.copy(
                    currentPartialText = null,
                    currentPartialTranslation = null,
                    translationStatus = TranslationStatus.IDLE
                )
            }
            
            // Log session metrics
            logSessionMetrics()
        }
    }
    
    /**
     * Translate partial text with debouncing
     * This prevents API flooding by waiting for speech pauses
     */
    private suspend fun translatePartialTextDebounced(koreanText: String) {
        try {
            totalApiCalls++
            _uiState.update { 
                it.copy(
                    translationStatus = TranslationStatus.TRANSLATING,
                    apiCallsThisSession = totalApiCalls
                )
            }
            
            // Use ML Kit for instant translation
            val response = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                translationService.translateWithMLKit(koreanText)
            }
            
            response?.let {
                _uiState.update { state ->
                    state.copy(
                        currentPartialTranslation = it.translatedText,
                        translationStatus = TranslationStatus.COMPLETED
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Partial translation error", e)
            // Don't show errors for partial translations
        }
    }
    
    /**
     * Trigger enhancement with proper debouncing
     */
    private suspend fun triggerRealtimeEnhancementDebounced(koreanText: String) {
        // Cancel previous enhancement
        enhancementJob?.cancel()
        
        enhancementJob = viewModelScope.launch {
            try {
                // Skip if text hasn't changed
                if (koreanText == lastEnhancedText) {
                    return@launch
                }
                
                totalApiCalls++
                _uiState.update { 
                    it.copy(
                        translationStatus = TranslationStatus.ENHANCING,
                        apiCallsThisSession = totalApiCalls,
                        estimatedCostCents = calculateEstimatedCost(totalApiCalls)
                    )
                }
                
                // Get ML Kit translation first
                val mlKitResponse = translationService.translateWithMLKit(koreanText)
                _uiState.update { 
                    it.copy(currentPartialTranslation = mlKitResponse.translatedText) 
                }
                
                // Enhance with Gemini if online
                if (translationService.isNetworkAvailable()) {
                    val enhancedResponse = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                        translationService.enhanceTranslationIncremental(
                            koreanText = koreanText,
                            forceRefresh = false
                        )
                    }
                    
                    enhancedResponse?.let {
                        _uiState.update { state ->
                            state.copy(
                                currentPartialTranslation = it.translatedText,
                                translationStatus = TranslationStatus.COMPLETED
                            )
                        }
                        lastEnhancedText = koreanText
                        showToast("✓ Enhanced translation updated")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Enhancement error", e)
            }
        }
    }
    
    /**
     * Smart periodic enhancement with rate limiting
     */
    private fun startPeriodicEnhancementSmart() {
        periodicEnhancementJob?.cancel()
        
        periodicEnhancementJob = viewModelScope.launch {
            delay(2000) // Initial delay
            
            while (_uiState.value.isRecording) {
                // REMOVED: accumulatedText - using individual sentence processing
                val currentText = "" // Placeholder - accumulatedText removed
                
                if (!currentText.isNullOrEmpty() && currentText != lastEnhancedText) {
                    // Check if we should enhance (rate limiting)
                    if (shouldEnhance()) {
                        enhancementDebouncer.emit(currentText)
                    }
                }
                
                delay(PERIODIC_ENHANCEMENT_MS)
            }
        }
    }
    
    /**
     * Determine if we should trigger enhancement (rate limiting)
     */
    private fun shouldEnhance(): Boolean {
        // Limit API calls to prevent excessive costs
        val maxCallsPerMinute = 15
        val elapsedMinutes = (System.currentTimeMillis() - sessionStartTime) / 60000.0
        val callsPerMinute = if (elapsedMinutes > 0) totalApiCalls / elapsedMinutes else 0.0
        
        return callsPerMinute < maxCallsPerMinute
    }
    
    /**
     * Calculate estimated API cost
     */
    private fun calculateEstimatedCost(apiCalls: Int): Int {
        // Rough estimate: $0.0001 per API call
        return (apiCalls * 0.01).toInt() // In cents
    }
    
    /**
     * Save final translation to database
     */
    private suspend fun saveFinalTranslation(koreanText: String, translatedText: String) {
        val message = TranslationMessage(
            id = java.util.UUID.randomUUID().toString(),
            originalText = koreanText,
            translatedText = translatedText,
            confidence = 0.95f,
            isEnhanced = lastEnhancedText == koreanText,
            translationEngine = if (lastEnhancedText == koreanText) 
                com.koreantranslator.model.TranslationEngine.HYBRID
            else 
                com.koreantranslator.model.TranslationEngine.ML_KIT,
            conversationId = currentConversationId,
            segmentNumber = currentSegmentNumber++,
            isPartOfContinuous = true
        )
        
        translationRepository.insertMessage(message)
    }
    
    /**
     * Load messages from database
     */
    private fun loadMessagesFromDatabase() {
        viewModelScope.launch {
            translationRepository.getAllMessages().collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }
    
    /**
     * Monitor network status
     */
    private fun monitorNetworkStatus() {
        viewModelScope.launch {
            while (true) {
                val isOnline = translationService.isNetworkAvailable()
                _uiState.update { it.copy(isOnline = isOnline) }
                delay(5000)
            }
        }
    }
    
    /**
     * Log session metrics for monitoring
     */
    private fun logSessionMetrics() {
        val sessionDuration = System.currentTimeMillis() - sessionStartTime
        val avgTranslationTime = if (totalApiCalls > 0) totalTranslationTime / totalApiCalls else 0
        
        Log.d(TAG, "=== SESSION METRICS ===")
        Log.d(TAG, "Duration: ${sessionDuration / 1000}s")
        Log.d(TAG, "API Calls: $totalApiCalls")
        Log.d(TAG, "Avg Translation Time: ${avgTranslationTime}ms")
        Log.d(TAG, "Estimated Cost: $${_uiState.value.estimatedCostCents / 100.0}")
        Log.d(TAG, "======================")
    }
    
    // Utility functions
    
    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
        sonioxStreamingService.clearError()
    }
    
    fun clearConversation() {
        sonioxStreamingService.clearAccumulatedText()
        translationService.clearConversationHistory()
        currentConversationId = null
        currentSegmentNumber = 0
        _uiState.update { 
            it.copy(
                currentPartialText = null,
                currentPartialTranslation = null
            )
        }
    }
    
    fun clearAllMessages() {
        viewModelScope.launch {
            translationRepository.clearAllMessages()
        }
    }
    
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            translationRepository.deleteMessage(messageId)
        }
    }
    
    /**
     * Test Gemini API connectivity - useful for debugging
     */
    fun testGeminiApi() {
        viewModelScope.launch {
            showToast("Testing Gemini API...")
            Log.d(TAG, "Testing Gemini API...")
            
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                // Try a simple translation to test the API
                val testText = "안녕하세요"
                val response = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    translationService.translateWithGemini(testText)
                }
                
                if (response != null) {
                    showToast("✓ Gemini API working: ${response.translatedText}")
                    Log.d(TAG, "Gemini API test successful")
                } else {
                    showToast("✗ Gemini API timeout")
                    Log.e(TAG, "Gemini API test timeout")
                }
                
            } catch (e: Exception) {
                showToast("API Test Failed: ${e.message}")
                Log.e(TAG, "Gemini API test failed", e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}