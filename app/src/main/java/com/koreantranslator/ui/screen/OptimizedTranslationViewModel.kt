package com.koreantranslator.ui.screen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koreantranslator.BuildConfig
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationMessage
import com.koreantranslator.model.TranslationState
import com.koreantranslator.repository.TranslationRepository
import com.koreantranslator.service.SonioxStreamingService
import com.koreantranslator.service.TranslationManager
import com.koreantranslator.service.ProductionAlertingService
import com.koreantranslator.nlp.KoreanNLPService
import com.koreantranslator.util.SentenceDetectionConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Type-safe message update operations for accumulation system
 */
sealed class MessageUpdate {
    abstract val koreanText: String?
    abstract val englishText: String?
    
    data class Accumulate(
        override val koreanText: String?,
        override val englishText: String?
    ) : MessageUpdate()
    
    data class Replace(
        override val koreanText: String?,
        override val englishText: String?
    ) : MessageUpdate()
}

/**
 * Translation backup data for emergency recovery
 */
data class TranslationBackup(
    val koreanText: String,
    val englishText: String,
    val confidence: Float,
    val timestamp: Long,
    val engine: TranslationEngine,
    val isComplete: Boolean
)

/**
 * Professional session state management for recording and translation
 */
sealed class SessionState {
    object Idle : SessionState()
    object Recording : SessionState()
    object Processing : SessionState()
    object Finalizing : SessionState()
    data class Completed(val message: String, val timestamp: Long) : SessionState()
    data class Error(val message: String) : SessionState()
}

/**
 * Optimized Translation ViewModel with dual-engine architecture
 * Features:
 * - Smart debouncing to prevent API flooding
 * - Dual-engine translation with intelligent switching
 * - Performance monitoring and cost optimization
 * - User correction learning system
 */
@HiltViewModel
class OptimizedTranslationViewModel @Inject constructor(
    application: Application,
    private val sonioxStreamingService: SonioxStreamingService,
    private val translationManager: TranslationManager,
    private val translationRepository: TranslationRepository,
    private val productionAlertingService: ProductionAlertingService,
    private val koreanNLPService: KoreanNLPService
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "OptimizedTranslationVM"
        
        // Optimized debouncing for cost efficiency
        private const val TRANSLATION_DEBOUNCE_MS = 800L  // Wait 800ms after speech
        private const val MAX_TRANSLATION_ATTEMPTS = 2
        private const val MIN_MESSAGE_LENGTH = 1  // Minimum characters for message creation (reduced for Korean)
        
        // Message accumulation settings (SIMPLIFIED - no limits)
        // REMOVED: SESSION_TIMEOUT_MS - no timeout, accumulate indefinitely
        // REMOVED: MAX_ACCUMULATED_LENGTH - no length limit, user controls via Clear button
        
        // Active message caching
        private const val CACHE_VALIDITY_MS = 500L
    }
    
    // UI State with translation-specific properties
    data class OptimizedTranslationState(
        val messages: List<TranslationMessage> = emptyList(),
        val sessionState: SessionState = SessionState.Idle, // Professional state management
        val isRecording: Boolean = false,
        val isTranslating: Boolean = false,
        val isEnhancing: Boolean = false,
        val currentPartialText: String? = null,
        val currentPartialTranslation: String? = null, // For UI compatibility
        val currentTranslationState: TranslationState = TranslationState.Idle,
        val error: String? = null,
        val isOnline: Boolean = true,
        val isLoading: Boolean = false, // Derived from isTranslating || isEnhancing
        val continuousRecordingMode: Boolean = false, // For extended recording sessions
        val sessionStatistics: SessionStatistics = SessionStatistics(),
        val isAccumulatingMessage: Boolean = false, // Whether currently accumulating to existing message (no timeout)
        // NEW: Separate accumulated text that persists across Soniox partial text resets
        val accumulatedKoreanText: String = "",
        val accumulatedEnglishText: String = ""
    )
    
    data class SessionStatistics(
        val totalTranslations: Int = 0,
        val geminiTranslations: Int = 0,
        val mlKitFallbacks: Int = 0,
        val cacheHits: Int = 0,
        val userCorrections: Int = 0,
        val averageLatency: Long = 0L
    )
    
    private val _uiState = MutableStateFlow(OptimizedTranslationState())
    val uiState: StateFlow<OptimizedTranslationState> = _uiState.asStateFlow()
    
    // Conversation state
    private var currentConversationId: String = UUID.randomUUID().toString()
    private var currentSegmentNumber = 0
    private val startTime = System.currentTimeMillis() // For alerting time windows
    
    // Real-time message tracking
    private var currentActiveMessageId: String? = null
    
    // Thread safety for concurrent active message operations
    private val activeMessageMutex = Mutex()
    
    // Separate mutex for cache operations to prevent deadlocks
    private val cacheMutex = Mutex()
    
    // CRITICAL: Dedicated mutex for accumulation state synchronization  
    // This prevents race conditions between UI, method, and database state
    private val accumulationStateLock = Mutex()
    
    // Active message caching for performance optimization
    private var cachedActiveMessage: TranslationMessage? = null
    private var cacheTimestamp: Long = 0
    
    // Atomic finalization tracking to prevent race conditions
    private val finalizingMessages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    
    // Translation debouncing
    private val translationTrigger = MutableSharedFlow<String>()
    private var translationJob: Job? = null
    // REMOVED: partialTranslationJob - no longer doing partial translations
    
    // Active message update debouncing - now type-safe
    private val activeMessageUpdateTrigger = MutableSharedFlow<MessageUpdate>()
    private var activeMessageUpdateJob: Job? = null
    
    // Professional finalization job for proper state management
    private var finalizationJob: Job? = null
    
    // Performance tracking
    private val sessionStartTime = System.currentTimeMillis()
    private val translationLatencies = mutableListOf<Long>()
    
    // PRODUCTION SAFEGUARDS - Critical monitoring and circuit breakers
    data class AccumulationMetrics(
        var totalAccumulationAttempts: Int = 0,
        var successfulAccumulations: Int = 0,
        var stateMismatchWarnings: Int = 0,
        var raceConditionsDetected: Int = 0,
        var circuitBreakerTrips: Int = 0
    )
    
    private val accumulationMetrics = AccumulationMetrics()
    private var isAccumulationDisabled = false  // Emergency kill switch
    private var databaseOperationFailures = 0
    private val maxDatabaseFailures = 5  // Circuit breaker threshold
    
    // EMERGENCY BACKUP: Store last successful translation for recovery
    private var lastSuccessfulTranslation: TranslationBackup? = null
    
    init {
        // Initialize data flows
        observeSpeechRecognition()
        observeTranslationDebouncing()
        observeActiveMessageUpdateDebouncing()
        observeTranslationState()
        // REMOVED: observePartialTextForRealTime() - redundant with simpler partial text flow
        loadMessages()
        
        // Clean up any orphaned active messages from previous app crashes
        cleanupOrphanedActiveMessages()
        
        Log.d(TAG, "OptimizedTranslationViewModel initialized with dual-engine architecture")
        Log.d(TAG, "Initial conversation ID: $currentConversationId")
    }
    
    /**
     * Observe Soniox speech recognition results - SIMPLIFIED
     */
    private fun observeSpeechRecognition() {
        viewModelScope.launch {
            // SIMPLIFIED: Accept all recognized text, no filtering
            sonioxStreamingService.recognizedText
                .filterNotNull()
                .filter { it.isNotBlank() }
                .collect { rawRecognizedText ->
                    Log.d(TAG, "Text received for translation: '${rawRecognizedText.take(50)}...'")
                    
                    // Process Korean text through NLP service for proper spacing
                    val processedKoreanText = koreanNLPService.process(rawRecognizedText.trim())
                    Log.d(TAG, "Korean NLP processed: '${rawRecognizedText.trim()}' -> '$processedKoreanText'")
                    
                    // APPEND to accumulated Korean text (separate from Soniox partial text)
                    _uiState.update { currentState ->
                        val newAccumulated = if (currentState.accumulatedKoreanText.isBlank()) {
                            processedKoreanText
                        } else {
                            "${currentState.accumulatedKoreanText} $processedKoreanText"
                        }
                        currentState.copy(accumulatedKoreanText = newAccumulated)
                    }
                    
                    // CRITICAL BACKUP: Immediately create backup when Korean text is recognized
                    // This ensures Korean text is never lost, even if translation fails
                    val koreanTextBackup = TranslationBackup(
                        koreanText = processedKoreanText, // Use processed text with proper spacing
                        englishText = "", // No translation yet, but Korean is preserved
                        confidence = 0.0f,
                        timestamp = System.currentTimeMillis(),
                        engine = TranslationEngine.ML_KIT,
                        isComplete = true
                    )
                    lastSuccessfulTranslation = koreanTextBackup
                    Log.d(TAG, "💾 Created immediate Korean backup: '$processedKoreanText'")
                    
                    // CRITICAL FIX: Always translate just the NEW sentence, accumulation happens in handleSuccessfulTranslation
                    // RACE CONDITION GUARD: Prevent multiple simultaneous translations
                    accumulationStateLock.withLock {
                        if (_uiState.value.isTranslating || _uiState.value.isEnhancing) {
                            Log.d(TAG, "🚨 RACE GUARD: Translation already in progress - queuing request")
                            // Cancel existing translation to prevent overlap
                            translationJob?.cancel()
                            delay(50) // Brief delay to let cancellation complete
                        }
                        translationTrigger.emit(processedKoreanText)
                    }
                    
                    Log.d(TAG, "Translating text: '$processedKoreanText'")
                    if (_uiState.value.isAccumulatingMessage) {
                        Log.d(TAG, "Will accumulate this translation result to existing message")
                    } else {
                        Log.d(TAG, "Will replace/create new message with this translation")
                    }
                }
        }
        
        viewModelScope.launch {
            // Listen to partial text - just update UI, don't append partial text
            sonioxStreamingService.partialText
                .debounce(100L) // Brief debounce to prevent excessive updates
                .collect { partialText ->
                    // Just show current partial text in UI
                    _uiState.update { it.copy(currentPartialText = partialText) }
                    
                    if (!partialText.isNullOrEmpty() && partialText.length > 2) {
                        Log.d(TAG, "Partial text update: '${partialText.take(30)}...'")
                    }
                }
        }
        
        viewModelScope.launch {
            // Listen to recording state with optimization to prevent unnecessary updates
            sonioxStreamingService.isListening
                .collect { isListening ->
                    val previousState = _uiState.value.isRecording
                    
                    // PERFORMANCE: Only update if state actually changed
                    if (previousState != isListening) {
                        _uiState.update { it.copy(isRecording = isListening) }
                        
                        if (isListening) {
                            // Create or continue accumulating active message when recording starts
                            createOrContinueActiveMessage()
                            Log.d(TAG, "Recording started - created or continued active message")
                        } else {
                            // CRITICAL FIX: Don't auto-finalize here - let professional stopRecording() handle it
                            // This prevents double finalization race condition
                            Log.d(TAG, "Recording stopped - finalization will be handled by stopRecording()")
                        }
                    } else {
                        Log.d(TAG, "Recording state update skipped - no change: $isListening")
                    }
                }
        }
        
    }
    
    /**
     * Observe translation debouncing to prevent API flooding
     */
    private fun observeTranslationDebouncing() {
        viewModelScope.launch {
            translationTrigger
                .debounce(TRANSLATION_DEBOUNCE_MS) // Wait for pause in speech
                .distinctUntilChanged() // Don't retranslate same text
                .collect { text ->
                    startTranslation(text)
                }
        }
    }
    
    /**
     * Observe active message update debouncing to reduce database writes
     */
    private fun observeActiveMessageUpdateDebouncing() {
        viewModelScope.launch {
            activeMessageUpdateTrigger
                .debounce(100L) // FIXED: Reduced from 500ms to 100ms to prevent race conditions with finalization
                .distinctUntilChanged() // Don't update with same content
                .collect { update ->
                    when (update) {
                        is MessageUpdate.Accumulate -> {
                            Log.d(TAG, "🔄 Processing ACCUMULATE update")
                            performActiveMessageUpdate(update.koreanText, update.englishText, shouldAccumulate = true)
                        }
                        is MessageUpdate.Replace -> {
                            Log.d(TAG, "🔄 Processing REPLACE update")
                            performActiveMessageUpdate(update.koreanText, update.englishText, shouldAccumulate = false)
                        }
                    }
                }
        }
    }
    
    /**
     * Observe translation state from TranslationManager
     */
    private fun observeTranslationState() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting to observe translation state from TranslationManager")
                translationManager.translationState.collect { state ->
                    Log.d(TAG, "Translation state received: ${state::class.simpleName}")
                    
                    _uiState.update { currentState ->
                        val isTranslating = state is TranslationState.Translating
                        val isEnhancing = state is TranslationState.Enhancing
                        
                        Log.d(TAG, "Updating UI state - translating: $isTranslating, enhancing: $isEnhancing")
                        
                        currentState.copy(
                            currentTranslationState = state,
                            isTranslating = isTranslating,
                            isEnhancing = isEnhancing,
                            isLoading = isTranslating || isEnhancing,
                            error = if (state is TranslationState.Error) state.message else null,
                            accumulatedEnglishText = if (state is TranslationState.Success && !state.isPartial) {
                                // APPEND translation to accumulated English text
                                if (currentState.accumulatedEnglishText.isBlank()) {
                                    state.translatedText
                                } else {
                                    "${currentState.accumulatedEnglishText} ${state.translatedText}"
                                }
                            } else currentState.accumulatedEnglishText
                        )
                    }
                    
                    // Handle successful translations
                    if (state is TranslationState.Success && !state.isPartial) {
                        Log.d(TAG, "Handling successful translation: '${state.translatedText}' from ${state.engine}")
                        handleSuccessfulTranslation(state)
                    } else if (state is TranslationState.Success && state.isPartial) {
                        Log.d(TAG, "Handling partial translation: '${state.translatedText}' from ${state.engine}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing translation state", e)
                _uiState.update { 
                    it.copy(
                        error = "Failed to observe translation state: ${e.message}",
                        isTranslating = false,
                        isEnhancing = false,
                        isLoading = false
                    )
                }
            }
        }
    }
    
    // REMOVED: observePartialTextForRealTime() method - redundant with simpler partial text flow
    // Partial text is now handled by the simple flow at lines 208-211
    
    /**
     * Start translation with the dual-engine system
     * Korean text is already processed through NLP service in observeSpeechRecognition()
     */
    private fun startTranslation(koreanText: String) {
        // Cancel any existing translation
        translationJob?.cancel()
        
        translationJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Starting dual-engine translation for: '${koreanText.take(30)}...'")
                Log.d(TAG, "Translation triggered for text: '$koreanText'")
                val startTime = System.currentTimeMillis()
                
                // Clear any previous errors
                _uiState.update { it.copy(error = null) }
                
                // Use TranslationManager's dual-engine approach
                val translationFlow = translationManager.translateWithDualEngine(
                    koreanText = koreanText, // Text is already processed through NLP
                    useContext = true // Use conversation context
                )
                
                Log.d(TAG, "Translation flow created, starting collection...")
                
                translationFlow.collect { state ->
                    Log.d(TAG, "Translation flow emitted state: ${state::class.simpleName}")
                    
                    // Track performance for completed translations
                    if (state is TranslationState.Success && !state.isPartial) {
                        val latency = System.currentTimeMillis() - startTime
                        translationLatencies.add(latency)
                        
                        // ROLLING WINDOW: Limit to last 100 entries to prevent unbounded memory growth
                        if (translationLatencies.size > 100) {
                            translationLatencies.removeAt(0) // Remove oldest entry
                        }
                        
                        updateSessionStatistics(state)
                        Log.d(TAG, "Translation completed successfully in ${latency}ms")
                    }
                }
                
                Log.d(TAG, "Translation flow collection completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed with exception", e)
                handleTranslationError(koreanText, e)
            }
        }
    }
    
    /**
     * Handle successful translation - Update active message and UI
     */
    private fun handleSuccessfulTranslation(state: TranslationState.Success) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Handling successful translation - Original: '${state.originalText}', Translated: '${state.translatedText}'")
                
                // ENHANCED: Store comprehensive backup data in UI state for recovery
                _uiState.update { currentState ->
                    currentState.copy(
                        isTranslating = false,
                        isEnhancing = false,
                        isLoading = false,
                        currentPartialText = state.originalText,
                        currentPartialTranslation = state.translatedText,
                        error = null
                    )
                }
                
                // CRITICAL BACKUP: Store translation with timestamp for emergency recovery
                val translationBackup = TranslationBackup(
                    koreanText = state.originalText,
                    englishText = state.translatedText,
                    confidence = state.confidence,
                    timestamp = System.currentTimeMillis(),
                    engine = state.engine,
                    isComplete = !state.isPartial
                )
                lastSuccessfulTranslation = translationBackup
                
                Log.d(TAG, "🔒 Stored translation backup for recovery: confidence=${(state.confidence * 100).toInt()}%, engine=${state.engine.name}")
                
                // ENHANCED RACE CONDITION FIX: Atomic duplicate prevention with state locking
                accumulationStateLock.withLock {
                    val stateSnapshot = getConsistentAccumulationState()
                    
                    Log.d(TAG, "🔒 ATOMIC STATE CHECK: isUIAccumulating=${stateSnapshot.isUIAccumulating}, " +
                              "hasActiveMessage=${stateSnapshot.hasActiveMessage}, shouldAccumulate=${stateSnapshot.shouldAccumulate}")
                    
                    if (stateSnapshot.shouldAccumulate) {
                        // SIMPLIFIED: Just accumulate the text - no duplicate checks
                        appendToActiveMessage(
                            koreanText = state.originalText,
                            englishText = state.translatedText
                        )
                        Log.d(TAG, "✅ ACCUMULATED translation: ${state.engine.name} (${(state.confidence * 100).toInt()}%)")
                        Log.d(TAG, "   Korean: '${state.originalText}'")
                        Log.d(TAG, "   English: '${state.translatedText}'")
                } else {
                    // REPLACE: First message or non-accumulating mode
                    updateActiveMessageWithReplacement(
                        koreanText = state.originalText,
                        englishText = state.translatedText
                    )
                    Log.d(TAG, "✅ REPLACED message using atomic state validation: ${state.engine.name} (${(state.confidence * 100).toInt()}%)")
                    Log.d(TAG, "   Reason: isUIAccumulating=${stateSnapshot.isUIAccumulating}, hasActiveMessage=${stateSnapshot.hasActiveMessage}")
                    }
                } // Close accumulationStateLock.withLock
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle translation update", e)
                _uiState.update { 
                    it.copy(
                        error = "Translation update failed: ${e.message}",
                        isTranslating = false,
                        isEnhancing = false,
                        isLoading = false
                    )
                }
            }
        }
    }
    
    
    /**
     * Create active message when recording starts OR continue accumulating to existing message
     */
    private fun createOrContinueActiveMessage() {
        viewModelScope.launch {
            try {
                // Only create if we don't already have an active message
                if (currentActiveMessageId != null) {
                    Log.d(TAG, "Active message already exists, not creating new one")
                    return@launch
                }
                
                // FIXED: Check if we should continue accumulating to existing messages 
                // Allow unlimited pause duration for seamless conversation accumulation
                val activeMessage = translationRepository.getActiveMessage()
                
                if (activeMessage != null) {
                    // RACE CONDITION FIX: Check atomic finalization tracking
                    val isMessageBeingFinalized = isMessageFinalizing(activeMessage.id)
                    
                    // SIMPLIFIED: Always accumulate - no length limits or timeouts
                    // Only check that it's not being finalized and same conversation
                    val canAccumulate = activeMessage.conversationId == currentConversationId &&
                                       !isMessageBeingFinalized
                    
                    Log.d(TAG, "Accumulation eligibility check (SIMPLIFIED):")
                    Log.d(TAG, "  - Message length: ${activeMessage.originalText.length} chars (no limit)")
                    Log.d(TAG, "  - Same conversation: ${activeMessage.conversationId == currentConversationId}")
                    Log.d(TAG, "  - Not being finalized: ${!isMessageBeingFinalized}")
                    Log.d(TAG, "  - Can accumulate: $canAccumulate")
                                       
                    if (canAccumulate) {
                        // ATOMIC STATE TRANSITION: Eliminate race condition by making database state authoritative
                        Log.d(TAG, "🔒 ATOMIC: Beginning atomic accumulation state transition")
                        
                        try {
                            // Step 1: Perform database operation FIRST (authoritative source)
                            invalidateActiveMessageCache()
                            translationRepository.setMessageActive(activeMessage.id)
                            
                            // Step 2: Only after database success, update UI state and internal tracking
                            currentActiveMessageId = activeMessage.id
                            _uiState.update { it.copy(isAccumulatingMessage = true) }
                            
                            Log.d(TAG, "✅ ATOMIC SUCCESS: Database and UI state now consistent")
                            Log.d(TAG, "   Message ID: ${activeMessage.id}")
                            Log.d(TAG, "   Message length: ${activeMessage.originalText.length} chars")
                            Log.d(TAG, "   Unlimited accumulation enabled - no time limits")
                            
                            trackAccumulationMetrics("ATOMIC_TRANSITION", true, stateMismatch = false)
                            return@launch
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "🚨 ATOMIC FAILURE: Rolling back state transition", e)
                            // Rollback: Clear any partial state
                            currentActiveMessageId = null
                            _uiState.update { it.copy(isAccumulatingMessage = false) }
                            trackAccumulationMetrics("ATOMIC_TRANSITION", false, raceCondition = true)
                            // Fall through to create new message instead
                        }
                    } else if (isMessageBeingFinalized) {
                        Log.d(TAG, "Message being finalized, waiting then creating new message")
                        // Wait for finalization to complete
                        delay(100)
                        createNewActiveMessage()
                        return@launch
                    } else {
                        Log.d(TAG, "Cannot accumulate - different conversation: ${activeMessage.conversationId != currentConversationId}, being finalized: ${isMessageBeingFinalized}")
                    }
                }
                
                // Create new message if we can't continue accumulating
                createNewActiveMessage()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create or continue active message", e)
                // Fallback to creating new message
                createNewActiveMessage()
            }
        }
    }
    
    /**
     * Create a completely new active message
     */
    private fun createNewActiveMessage() {
        viewModelScope.launch {
            try {
                val messageId = UUID.randomUUID().toString()
                val message = TranslationMessage(
                    id = messageId,
                    originalText = "", // Will be updated as text comes in
                    translatedText = "", // Will be updated as translation comes in
                    confidence = 0.75f,
                    translationEngine = TranslationEngine.ML_KIT,
                    isEnhanced = false,
                    conversationId = currentConversationId,
                    segmentNumber = currentSegmentNumber,
                    timestamp = Date(),
                    isActive = true // This is the key - marks it as actively updating
                )
                
                // Invalidate cache BEFORE creating new message to prevent stale data
                invalidateActiveMessageCache()
                translationRepository.insertMessage(message)
                currentActiveMessageId = messageId
                
                _uiState.update { it.copy(isAccumulatingMessage = false) }
                
                Log.d(TAG, "Created new active message: $messageId")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create new active message", e)
            }
        }
    }
    
    /**
     * Update the active message with new text and translation (debounced) - DEPRECATED
     * Use updateActiveMessageWithReplacement() or appendToActiveMessage() instead
     */
    @Deprecated("Use updateActiveMessageWithReplacement() or appendToActiveMessage() instead")
    private fun updateActiveMessage(koreanText: String?, englishText: String?) {
        Log.w(TAG, "⚠️ DEPRECATED: updateActiveMessage() called - should use specific replacement or accumulation methods")
        // Forward to replacement method for backward compatibility
        updateActiveMessageWithReplacement(koreanText, englishText)
    }
    
    /**
     * Append text to active message (for final translations only)
     */
    private fun appendToActiveMessage(koreanText: String?, englishText: String?) {
        viewModelScope.launch {
            try {
                // Trigger debounced update with ACCUMULATE operation
                activeMessageUpdateTrigger.emit(MessageUpdate.Accumulate(koreanText, englishText))
                Log.d(TAG, "✅ Triggered ACCUMULATE update: Korean='${koreanText?.take(30)}...', English='${englishText?.take(30)}...'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger active message append", e)
            }
        }
    }
    
    /**
     * Replace text in active message (for partial text updates)
     */
    private fun updateActiveMessageWithReplacement(koreanText: String?, englishText: String?) {
        viewModelScope.launch {
            try {
                // Trigger debounced update with REPLACE operation
                activeMessageUpdateTrigger.emit(MessageUpdate.Replace(koreanText, englishText))
                Log.d(TAG, "✅ Triggered REPLACE update: Korean='${koreanText?.take(30)}...', English='${englishText?.take(30)}...'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger active message replacement", e)
            }
        }
    }
    
    /**
     * Perform the actual database update for active message with error recovery and accumulation support
     */
    private fun performActiveMessageUpdate(koreanText: String?, englishText: String?, shouldAccumulate: Boolean) {
        viewModelScope.launch {
            activeMessageMutex.withLock {
            try {
                // ATOMIC VALIDATION: Check database state consistency before any operations
                val atomicState = getConsistentAccumulationState()
                val hasStateMismatch = shouldAccumulate != atomicState.shouldAccumulate
                
                if (hasStateMismatch) {
                    Log.w(TAG, "⚠️ DANGEROUS STATE MISMATCH: shouldAccumulate=true but isAccumulatingMessage=false")
                    Log.w(TAG, "   This could indicate a race condition or state desynchronization")
                    trackAccumulationMetrics("DATABASE_UPDATE", false, stateMismatch = true)
                }
                
                // EMERGENCY SAFEGUARD: Abort if accumulation is disabled by circuit breaker
                if (shouldAccumulate && isAccumulationDisabled) {
                    Log.e(TAG, "🚨 CRITICAL: Aborting database update - accumulation disabled by circuit breaker")
                    trackAccumulationMetrics("DATABASE_UPDATE", false, raceCondition = true)
                    return@launch
                }
                
                Log.d(TAG, "🔒 Starting database update - shouldAccumulate: $shouldAccumulate")
                trackAccumulationMetrics("DATABASE_UPDATE", true, stateMismatch = hasStateMismatch)
                val activeMessageId = currentActiveMessageId
                if (activeMessageId == null) {
                    // No active message ID - if we're recording, try to recover by creating new one
                    if (_uiState.value.isRecording) {
                        Log.w(TAG, "No active message ID while recording - creating recovery message")
                        createOrContinueActiveMessage()
                    }
                    return@launch
                }
                
                // Get the current active message
                val activeMessage = translationRepository.getActiveMessage()
                if (activeMessage == null) {
                    Log.w(TAG, "Active message not found in database - possible deletion")
                    
                    // Try to recover if we're still recording
                    if (_uiState.value.isRecording) {
                        Log.d(TAG, "Attempting to recover missing active message")
                        currentActiveMessageId = null // Reset to allow new creation
                        createOrContinueActiveMessage()
                        // Re-trigger update after short delay to let creation complete
                        delay(100)
                        val update = if (shouldAccumulate) {
                            MessageUpdate.Accumulate(koreanText, englishText)
                        } else {
                            MessageUpdate.Replace(koreanText, englishText)
                        }
                        activeMessageUpdateTrigger.emit(update)
                    }
                    return@launch
                }
                
                if (activeMessage.id != activeMessageId) {
                    Log.w(TAG, "Active message ID mismatch - expected: $activeMessageId, got: ${activeMessage.id}")
                    
                    // Update our tracking to match reality
                    currentActiveMessageId = activeMessage.id
                }
                
                // Check if message is stale (older than 5 minutes)
                val messageAge = System.currentTimeMillis() - activeMessage.timestamp.time
                val maxMessageAge = 5 * 60 * 1000L // 5 minutes
                
                if (messageAge > maxMessageAge) {
                    Log.w(TAG, "Active message is stale (${messageAge / 1000}s old) - finalizing and creating new one")
                    
                    // Always preserve stale messages - never delete user speech
                    translationRepository.setMessageInactive(activeMessage.id)
                    Log.d(TAG, "Preserved stale message: Korean='${activeMessage.originalText}', English='${activeMessage.translatedText}'")
                    
                    // Create new active message if still recording
                    if (_uiState.value.isRecording) {
                        currentActiveMessageId = null
                        createOrContinueActiveMessage()
                    }
                    return@launch
                }
                
                // FIXED ACCUMULATION LOGIC: Separate replace vs accumulate based on shouldAccumulate parameter
                val isAccumulating = _uiState.value.isAccumulatingMessage && shouldAccumulate
                val updatedKoreanText = if (koreanText?.trim()?.isNotEmpty() == true) {
                    if (isAccumulating && activeMessage.originalText.isNotBlank()) {
                        // Accumulate Korean text with proper spacing (only for final translations)
                        "${activeMessage.originalText.trim()} ${koreanText.trim()}".trim()
                    } else {
                        // Replace text (for partial updates) or first text
                        koreanText.trim()
                    }
                } else {
                    activeMessage.originalText
                }
                
                val updatedEnglishText = if (englishText?.trim()?.isNotEmpty() == true) {
                    if (isAccumulating && activeMessage.translatedText.isNotBlank()) {
                        // Accumulate English text with proper spacing (only for final translations)
                        "${activeMessage.translatedText.trim()} ${englishText.trim()}".trim()
                    } else {
                        // Replace text (for partial updates) or first text
                        englishText.trim()
                    }
                } else {
                    activeMessage.translatedText
                }
                
                // Update with accumulated content
                val updatedMessage = activeMessage.copy(
                    originalText = updatedKoreanText,
                    translatedText = updatedEnglishText,
                    timestamp = Date() // Update timestamp to show it's been updated
                )
                
                // PRODUCTION SAFEGUARD: Monitor critical database operation  
                try {
                    // OPTIMIZED CACHE INVALIDATION: Only invalidate if content actually changed
                    val contentChanged = (updatedKoreanText != activeMessage.originalText) || 
                                       (updatedEnglishText != activeMessage.translatedText)
                    if (contentChanged) {
                        invalidateActiveMessageCache()
                        Log.d(TAG, "Cache invalidated due to content change")
                    } else {
                        Log.d(TAG, "Cache preserved - no content change detected")
                    }
                    
                    translationRepository.updateMessage(updatedMessage)
                    
                    // Success - reset circuit breaker
                    handleDatabaseOperationResult(true, "UPDATE_MESSAGE")
                    trackAccumulationMetrics(
                        if (isAccumulating) "ACCUMULATION" else "REPLACEMENT", 
                        true
                    )
                    
                    if (isAccumulating) {
                        Log.d(TAG, "✅ ACCUMULATED to active message:")
                        Log.d(TAG, "  Korean: '${activeMessage.originalText}' + '${koreanText}' = '${updatedKoreanText}'")
                        Log.d(TAG, "  English: '${activeMessage.translatedText}' + '${englishText}' = '${updatedEnglishText}'")
                    } else {
                        Log.d(TAG, "✅ REPLACED active message content:")
                        Log.d(TAG, "  Korean: '${updatedKoreanText}'")
                        Log.d(TAG, "  English: '${updatedEnglishText}'")
                        Log.d(TAG, "  (shouldAccumulate=${shouldAccumulate}, isAccumulatingMessage=${_uiState.value.isAccumulatingMessage})")
                    }
                    
                } catch (dbException: Exception) {
                    // Database operation failed - trigger circuit breaker
                    Log.e(TAG, "🚨 Database operation failed", dbException)
                    handleDatabaseOperationResult(false, "UPDATE_MESSAGE")
                    trackAccumulationMetrics(
                        if (isAccumulating) "ACCUMULATION" else "REPLACEMENT", 
                        false
                    )
                    throw dbException // Re-throw for outer catch block
                }
                
                // Return success if we reach this point
                "success"
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform atomic active message update", e)
                
                // Critical error recovery - reset active message state
                if (_uiState.value.isRecording) {
                    Log.d(TAG, "Critical error - resetting active message state")
                    currentActiveMessageId = null
                    
                    // Try to create new active message after short delay
                    delay(1000)
                    try {
                        createOrContinueActiveMessage()
                    } catch (recoveryError: Exception) {
                        Log.e(TAG, "Failed to recover active message", recoveryError)
                    }
                }
                null // Return null on failure
            }
            } // Close activeMessageMutex.withLock
        }
    }
    
    /**
     * Finalize active message when recording stops - deletes if empty
     */
    /**
     * Professional message finalization with comprehensive error handling
     */
    private suspend fun finalizeActiveMessage() {
        activeMessageMutex.withLock {
            try {
                val activeMessageId = currentActiveMessageId
                if (activeMessageId == null) {
                    Log.d(TAG, "No active message to finalize")
                    return
                }
                
                Log.d(TAG, "Professional finalization started for message: $activeMessageId")
                
                // Mark message as being finalized to prevent race conditions
                markMessageFinalizing(activeMessageId)
                
                try {
                    // Step 1: Ensure all operations are complete with professional retry
                    ensureAllOperationsComplete(activeMessageId)
                    
                    // Step 2: Get final message state with retry mechanism
                    val message = getActiveMessageWithRetry(activeMessageId)
                    
                    if (message != null) {
                        Log.d(TAG, "Retrieved message for finalization: Korean='${message.originalText.take(30)}...', English='${message.translatedText.take(30)}...'")
                        
                        // Step 3: Decide whether to delete or preserve based on content
                        if (shouldDeleteMessage(message)) {
                            Log.d(TAG, "Deleting empty message: $activeMessageId")
                            translationRepository.deleteMessage(activeMessageId)
                        } else {
                            Log.d(TAG, "Preserving message: $activeMessageId")
                            translationRepository.setMessageInactive(activeMessageId)
                            currentSegmentNumber++
                        }
                    } else {
                        Log.w(TAG, "Message not found during finalization: $activeMessageId")
                    }
                    
                } finally {
                    // Always clear finalization mark
                    clearMessageFinalizing(activeMessageId)
                }
                
                // Clear active message tracking
                currentActiveMessageId = null
                
                Log.d(TAG, "Professional finalization completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Professional finalization failed", e)
                
                // Clear finalization mark on error
                currentActiveMessageId?.let { id ->
                    clearMessageFinalizing(id)
                }
                
                throw e // Re-throw for error handling at higher level
            }
        }
    }
    
    /**
     * Ensure all operations complete before finalization
     */
    private suspend fun ensureAllOperationsComplete(messageId: String) {
        Log.d(TAG, "Ensuring all operations complete for: $messageId")
        
        // ENHANCED: Wait for debounced updates to process (100ms debounce + buffer)
        delay(150)
        
        // Force flush any still-pending updates
        val lastPendingUpdate = activeMessageUpdateTrigger.replayCache.lastOrNull()
        if (lastPendingUpdate != null) {
            Log.d(TAG, "Found pending update - executing immediately: ${lastPendingUpdate::class.simpleName}")
            when (lastPendingUpdate) {
                is MessageUpdate.Accumulate -> {
                    performActiveMessageUpdate(lastPendingUpdate.koreanText, lastPendingUpdate.englishText, shouldAccumulate = true)
                }
                is MessageUpdate.Replace -> {
                    performActiveMessageUpdate(lastPendingUpdate.koreanText, lastPendingUpdate.englishText, shouldAccumulate = false)
                }
            }
            // Wait for database write to complete
            delay(150)
            Log.d(TAG, "Pending update processed successfully")
        }
        
        // FIXED: Ensure translation job completes before finalizing
        translationJob?.join()
        
        // Invalidate cache to ensure fresh data
        invalidateActiveMessageCache()
    }
    
    /**
     * Get active message with retry mechanism for reliability
     */
    private suspend fun getActiveMessageWithRetry(id: String): TranslationMessage? {
        repeat(3) { attempt ->
            try {
                invalidateActiveMessageCache()
                val message = translationRepository.getActiveMessage()
                if (message?.id == id) {
                    Log.d(TAG, "Retrieved message on attempt ${attempt + 1}")
                    return message
                }
                Log.w(TAG, "Message ID mismatch on attempt ${attempt + 1}: expected $id, got ${message?.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get message on attempt ${attempt + 1}", e)
            }
            
            if (attempt < 2) delay(100) // Brief delay before retry
        }
        
        Log.e(TAG, "Failed to retrieve message after 3 attempts: $id")
        return null
    }
    
    /**
     * Professional logic for determining if message should be deleted
     * FIXED: Preserve messages with Korean content even if English translation is empty
     */
    private fun shouldDeleteMessage(message: TranslationMessage): Boolean {
        val koreanText = message.originalText.trim()
        val englishText = message.translatedText.trim()
        
        // DEFENSIVE: Never delete if there's ANY Korean content
        val hasKoreanCharacters = koreanText.any { it in '가'..'힣' }
        if (hasKoreanCharacters) {
            Log.d(TAG, "🛡️ PRESERVED: Message contains Korean characters - never delete")
            return false
        }
        
        // DEFENSIVE: Check if we have backup translation data that should be preserved
        val hasBackupData = lastSuccessfulTranslation?.let { backup ->
            backup.koreanText.isNotBlank() || backup.englishText.isNotBlank()
        } ?: false
        
        if (hasBackupData) {
            Log.d(TAG, "🛡️ PRESERVED: Backup translation data exists - recovering to message")
            // Attempt to restore from backup
            lastSuccessfulTranslation?.let { backup ->
                if (backup.koreanText.isNotBlank() || backup.englishText.isNotBlank()) {
                    Log.d(TAG, "🔄 RECOVERY: Restoring from backup - Korean: '${backup.koreanText}', English: '${backup.englishText}'")
                    // Update message with backup content (this will be processed in next retry)
                    updateActiveMessageWithReplacement(backup.koreanText, backup.englishText)
                }
            }
            return false
        }
        
        // Only delete if BOTH Korean and English are completely empty AND no Korean characters found
        val shouldDelete = koreanText.isBlank() && englishText.isBlank()
        
        if (shouldDelete) {
            Log.d(TAG, "✅ SAFE TO DELETE: No Korean content, no English content, no backup data")
        } else {
            Log.d(TAG, "🛡️ PRESERVED: Contains content - Korean='${koreanText.take(20)}...', English='${englishText.take(20)}...'")
        }
        
        return shouldDelete
    }
    
    /**
     * Clear message finalizing state
     */
    private fun clearMessageFinalizing(messageId: String) {
        finalizingMessages.remove(messageId)
        Log.d(TAG, "Cleared finalizing state for: $messageId")
    }
    
    /**
     * Handle translation errors
     */
    private fun handleTranslationError(originalText: String, error: Throwable) {
        _uiState.update { 
            it.copy(
                error = "Translation failed: ${error.message}",
                isTranslating = false,
                isEnhancing = false,
                isLoading = false
            )
        }
    }
    
    /**
     * Handle user corrections to improve future translations
     */
    fun handleUserCorrection(originalKorean: String, correctedEnglish: String) {
        viewModelScope.launch {
            Log.d(TAG, "User correction: '$originalKorean' -> '$correctedEnglish'")
            
            // Let the TranslationManager learn from the correction
            // This would feed back to SmartPhraseCache for improved recognition
            sonioxStreamingService.handleUserCorrection(originalKorean, correctedEnglish)
            
            // Update statistics
            _uiState.update { state ->
                state.copy(
                    sessionStatistics = state.sessionStatistics.copy(
                        userCorrections = state.sessionStatistics.userCorrections + 1
                    )
                )
            }
        }
    }
    
    /**
     * Update session statistics
     */
    private fun updateSessionStatistics(state: TranslationState.Success) {
        _uiState.update { currentState ->
            val stats = currentState.sessionStatistics
            val newStats = stats.copy(
                totalTranslations = stats.totalTranslations + 1,
                geminiTranslations = if (state.engine == TranslationEngine.GEMINI_FLASH) 
                    stats.geminiTranslations + 1 else stats.geminiTranslations,
                mlKitFallbacks = if (state.engine == TranslationEngine.ML_KIT) 
                    stats.mlKitFallbacks + 1 else stats.mlKitFallbacks,
                cacheHits = if (state.fromCache) stats.cacheHits + 1 else stats.cacheHits,
                averageLatency = if (translationLatencies.isNotEmpty()) 
                    translationLatencies.average().toLong() else 0L
            )
            
            currentState.copy(sessionStatistics = newStats)
        }
    }
    
    /**
     * Clean up orphaned active messages from previous app crashes
     */
    private fun cleanupOrphanedActiveMessages() {
        viewModelScope.launch {
            try {
                val orphanedActiveMessage = translationRepository.getActiveMessage()
                if (orphanedActiveMessage != null) {
                    val koreanText = orphanedActiveMessage.originalText.trim()
                    val englishText = orphanedActiveMessage.translatedText.trim()
                    
                    // Check if the orphaned message has meaningful content
                    if (koreanText.length >= MIN_MESSAGE_LENGTH || englishText.isNotBlank()) {
                        // Orphan has content (Korean OR English) - finalize it
                        translationRepository.setMessageInactive(orphanedActiveMessage.id)
                        Log.d(TAG, "Preserved orphaned message with content: Korean='$koreanText', English='$englishText'")
                    } else {
                        // Only delete if truly empty (no Korean AND no English)
                        translationRepository.setMessageInactive(orphanedActiveMessage.id)  
                        Log.d(TAG, "Preserved potentially empty message for user review")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup orphaned active messages", e)
            }
        }
    }
    
    /**
     * Load existing messages from database with optimization to prevent unnecessary UI updates
     */
    private fun loadMessages() {
        viewModelScope.launch {
            translationRepository.getAllMessages()
                .distinctUntilChanged() // PERFORMANCE: Prevent identical emissions
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }
    }
    
    
    // REMOVED: triggerPartialTranslationForUI() method completely
    // Partial text is now UI-only, no API calls during typing to save costs
    
    /**
     * Start recording with Soniox
     * @param continuousMode If true, enables extended session mode with enhanced reconnection
     */
    fun startRecording(continuousMode: Boolean = false) {
        viewModelScope.launch {
            try {
                // FIXED: Don't reset active message ID - preserve it for accumulation
                // Only reset if explicitly starting a new conversation
                val preservingActiveMessage = currentActiveMessageId != null
                if (preservingActiveMessage) {
                    Log.d(TAG, "Preserving active message ID for accumulation: $currentActiveMessageId")
                } else {
                    Log.d(TAG, "No active message ID to preserve - will create or find message to accumulate to")
                }
                
                Log.d(TAG, "Starting recording - continuous mode: $continuousMode")
                Log.d(TAG, "Continuing conversation ID: $currentConversationId")
                
                // Clear only states, preserve accumulated text from previous session
                _uiState.update { currentState ->
                    // PRESERVE: Keep previous session's text visible until new text arrives
                    currentState.copy(
                        error = null,
                        isTranslating = false,
                        isEnhancing = false,
                        isLoading = false,
                        continuousRecordingMode = continuousMode
                        // KEEP: currentPartialText and currentPartialTranslation from previous session
                    ) 
                }
                
                // Start Soniox with continuous mode flag
                sonioxStreamingService.startListening(continuous = continuousMode)
                clearError()
                
                Log.d(TAG, "Recording started, continuing conversation ID: $currentConversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                handleTranslationError("", e)
            }
        }
    }
    
    /**
     * Professional stop recording with dynamic completion tracking
     */
    fun stopRecording() {
        // Cancel any pending finalization
        finalizationJob?.cancel()
        
        finalizationJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Professional stop recording initiated")
                
                // 1. Update state and stop audio input
                updateSessionState(SessionState.Processing)
                sonioxStreamingService.stopListening()
                _uiState.update { 
                    it.copy(
                        isRecording = false,
                        continuousRecordingMode = false
                    ) 
                }
                
                // 2. Wait for pipeline completion with timeout (increased for dual-engine translation)
                val completed = withTimeoutOrNull(5000) {  // Increased from 2s to 5s for ML Kit + Gemini
                    waitForPipelineCompletion()
                } ?: false
                
                if (!completed) {
                    Log.w(TAG, "Pipeline completion timeout - forcing flush")
                    forceFlushAllPendingOperations()
                }
                
                // 3. Finalize based on recording mode
                updateSessionState(SessionState.Finalizing)
                if (_uiState.value.continuousRecordingMode) {
                    finalizeAndContinue()
                } else {
                    finalizeAndStop()
                }
                
                // 4. Handle post-finalization UI
                handlePostFinalizationUI()
                
                Log.d(TAG, "Professional stop recording completed successfully")
                
            } catch (e: CancellationException) {
                Log.d(TAG, "Stop recording cancelled - likely restarted")
                // Don't reset state on cancellation - new operation will handle it
            } catch (e: Exception) {
                Log.e(TAG, "Error during stop recording", e)
                handleFinalizationError(e)
            }
        }
    }
    
    /**
     * Toggle between standard and continuous recording modes
     */
    fun toggleContinuousRecording() {
        val currentState = _uiState.value
        if (currentState.isRecording) {
            // Stop current recording and restart in opposite mode
            stopRecording()
            // Small delay to ensure clean stop, then restart
            viewModelScope.launch {
                delay(500)
                startRecording(continuousMode = !currentState.continuousRecordingMode)
            }
        } else {
            // Start in continuous mode if not already recording
            startRecording(continuousMode = true)
        }
    }
    
    /**
     * Thread-safe session state updates with logging and stability optimization
     */
    private fun updateSessionState(newState: SessionState) {
        val currentState = _uiState.value.sessionState
        
        // STABILITY: Only update if state actually changes to prevent unnecessary recomposition
        if (currentState::class != newState::class) {
            _uiState.update { it.copy(sessionState = newState) }
            Log.d(TAG, "State transition: ${currentState::class.simpleName} → ${newState::class.simpleName}")
        } else {
            Log.d(TAG, "State update skipped - same state type: ${currentState::class.simpleName}")
        }
    }
    
    /**
     * Dynamic pipeline completion tracking (no fixed delays)
     */
    private suspend fun waitForPipelineCompletion(): Boolean {
        val startTime = System.currentTimeMillis()
        var attempts = 0
        
        while (attempts < 100) { // 5 seconds max (50ms * 100) - increased for dual-engine processing
            if (!isAnyOperationPending()) {
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "Pipeline completion took ${duration}ms")
                return true
            }
            delay(50)
            attempts++
        }
        
        Log.w(TAG, "Pipeline completion timeout after 5 seconds")
        return false
    }
    
    /**
     * Check if any operations are still pending in the pipeline
     */
    private fun isAnyOperationPending(): Boolean {
        return translationManager.isProcessing() ||
               hasPendingDatabaseOperations() ||
               hasPendingTriggerEmissions()
    }
    
    /**
     * Force flush all pending operations in parallel
     */
    private suspend fun forceFlushAllPendingOperations() {
        Log.d(TAG, "Force flushing all pending operations")
        
        coroutineScope {
            // Parallel flush of all systems for efficiency
            launch { flushTranslationPipeline() }
            launch { flushDatabaseOperations() }
            launch { flushUIUpdates() }
        }
        
        // Brief wait for operations to process
        delay(200)
    }
    
    /**
     * Flush translation pipeline with current partial text
     */
    private suspend fun flushTranslationPipeline() {
        _uiState.value.currentPartialText?.let { text ->
            if (text.isNotBlank()) {
                Log.d(TAG, "Flushing translation for: '${text.take(30)}...'")
                translationTrigger.emit(text)
            }
        }
    }
    
    /**
     * Flush pending database operations
     */
    private suspend fun flushDatabaseOperations() {
        activeMessageUpdateTrigger.replayCache.lastOrNull()?.let { update ->
            Log.d(TAG, "Flushing database update: ${update::class.simpleName}")
            activeMessageUpdateTrigger.emit(update)
        }
    }
    
    /**
     * Flush UI updates
     */
    private suspend fun flushUIUpdates() {
        // Cancel any pending jobs to flush immediately
        translationJob?.cancel()
        activeMessageUpdateJob?.cancel()
    }
    
    /**
     * Finalize and continue (for continuous recording mode)
     */
    private suspend fun finalizeAndContinue() {
        Log.d(TAG, "Finalizing for continuous mode")
        
        // Finalize current segment
        finalizeActiveMessage()
        
        // Prepare for next segment immediately
        currentSegmentNumber++
        updateSessionState(SessionState.Idle)
        
        _uiState.update { 
            it.copy(
                isTranslating = false,
                isEnhancing = false
            ) 
        }
        
        Log.d(TAG, "Continuous mode: Ready for segment $currentSegmentNumber")
    }
    
    /**
     * Stop recording but keep text visible for accumulation (NEVER AUTO-CLEAR)
     */
    private suspend fun finalizeAndStop() {
        Log.d(TAG, "Stopping recording - keeping text visible for accumulation")
        
        // SIMPLIFIED: Just stop recording, don't clear ANYTHING
        val finalText = _uiState.value.currentPartialText ?: ""
        updateSessionState(SessionState.Completed(finalText, System.currentTimeMillis()))
        
        // NEVER CLEAR TEXT - keep everything visible for user control
        _uiState.update { 
            it.copy(
                // Keep the text visible! NO CLEARING!
                // currentPartialText = keep as is (don't clear)
                // currentPartialTranslation = keep as is (don't clear)
                isTranslating = false,
                isEnhancing = false,
                isAccumulatingMessage = true  // Always ready to accumulate
            ) 
        }
        
        Log.d(TAG, "Recording stopped, text preserved for continued use")
        Log.d(TAG, "Active message ID preserved: $currentActiveMessageId")
    }
    
    /**
     * Handle post-finalization UI (SIMPLIFIED - no smart clearing)
     */
    private suspend fun handlePostFinalizationUI() {
        Log.d(TAG, "Handling post-finalization UI - no smart clearing needed")
        
        // SIMPLIFIED: No smart clearing, no delays, no automatic UI clearing
        // Text remains visible for user control
    }
    
    /**
     * Manual clear function - ONLY way to clear text (user controlled)
     */
    fun clearAllText() {
        _uiState.update {
            it.copy(
                currentPartialText = null,
                currentPartialTranslation = null,
                isAccumulatingMessage = false,
                sessionState = SessionState.Idle,
                accumulatedKoreanText = "",
                accumulatedEnglishText = ""
            )
        }
        // Clear active message tracking
        currentActiveMessageId = null
        
        Log.d(TAG, "User manually cleared all text - fresh start")
    }
    
    /**
     * Handle finalization errors with recovery
     */
    private fun handleFinalizationError(e: Exception) {
        Log.e(TAG, "Finalization error - attempting recovery", e)
        
        updateSessionState(SessionState.Error(e.message ?: "Unknown finalization error"))
        
        _uiState.update { 
            it.copy(
                error = "Recording finalization failed: ${e.message}",
                isRecording = false,
                isTranslating = false,
                isEnhancing = false,
            ) 
        }
        
        // Auto-recovery after error
        viewModelScope.launch {
            delay(3000) // Show error for 3 seconds
            if (_uiState.value.sessionState is SessionState.Error) {
                updateSessionState(SessionState.Idle)
                _uiState.update { it.copy(error = null) }
                Log.d(TAG, "Auto-recovery from finalization error completed")
            }
        }
    }
    
    /**
     * Check for pending database operations
     */
    private fun hasPendingDatabaseOperations(): Boolean {
        return activeMessageUpdateTrigger.replayCache.isNotEmpty()
    }
    
    /**
     * Check for pending trigger emissions
     */
    private fun hasPendingTriggerEmissions(): Boolean {
        return translationTrigger.subscriptionCount.value > 0
    }
    
    /**
     * Get cached active message for performance optimization (thread-safe, non-blocking)
     */
    private suspend fun getCachedActiveMessage(): TranslationMessage? {
        return cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedActiveMessage != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
                cachedActiveMessage
            } else {
                cachedActiveMessage = translationRepository.getActiveMessage()
                cacheTimestamp = now
                cachedActiveMessage
            }
        }
    }
    
    /**
     * Get active message safely - can be called from within active message mutex
     */
    private suspend fun getActiveMessageSafe(): TranslationMessage? {
        // First try cache without blocking
        val cached = cacheMutex.tryLock()
        return if (cached) {
            try {
                val now = System.currentTimeMillis()
                if (cachedActiveMessage != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
                    cachedActiveMessage
                } else {
                    null // Cache miss, will fetch directly
                }
            } finally {
                cacheMutex.unlock()
            }
        } else {
            null // Cache locked, will fetch directly
        } ?: translationRepository.getActiveMessage() // Fallback to direct DB access
    }
    
    /**
     * Invalidate the active message cache (thread-safe)
     */
    private suspend fun invalidateActiveMessageCache() {
        cacheMutex.withLock {
            cachedActiveMessage = null
            cacheTimestamp = 0
        }
    }
    
    /**
     * Mark message as being finalized to prevent race conditions
     */
    private fun markMessageFinalizing(messageId: String) {
        finalizingMessages[messageId] = true
        Log.d(TAG, "🔒 Marked message $messageId as finalizing")
    }
    
    /**
     * Remove finalization mark when complete
     */
    private fun clearFinalizationMark(messageId: String) {
        finalizingMessages.remove(messageId)
        Log.d(TAG, "🔓 Cleared finalization mark for message $messageId")
    }
    
    /**
     * Check if message is being finalized
     */
    private fun isMessageFinalizing(messageId: String): Boolean {
        return finalizingMessages.containsKey(messageId)
    }
    
    /**
     * ATOMIC STATE VALIDATION: Database-first state checking to eliminate race conditions
     * Uses database state as single source of truth, with UI state as derived presentation layer
     */
    data class AccumulationStateSnapshot(
        val isUIAccumulating: Boolean,
        val hasActiveMessage: Boolean,
        val activeMessageId: String?,
        val shouldAccumulate: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private suspend fun getConsistentAccumulationState(): AccumulationStateSnapshot {
        return accumulationStateLock.withLock {
            val uiState = _uiState.value
            
            // DATABASE-FIRST: Query actual database state as single source of truth
            val databaseActiveMessage = try {
                translationRepository.getActiveMessage()
            } catch (e: Exception) {
                Log.w(TAG, "Database query failed in state check, using UI fallback", e)
                null
            }
            
            // Determine truth from database state, not UI state
            val hasActiveMessage = databaseActiveMessage != null
            val activeMessageId = databaseActiveMessage?.id ?: currentActiveMessageId
            
            // ATOMIC VALIDATION: Check for UI/database mismatches
            val uiAccumulating = uiState.isAccumulatingMessage
            val dbAccumulating = hasActiveMessage
            val hasStateMismatch = uiAccumulating != dbAccumulating
            
            if (hasStateMismatch) {
                Log.w(TAG, "🚨 STATE MISMATCH DETECTED: UI=$uiAccumulating, DB=$dbAccumulating")
                Log.w(TAG, "   This indicates a race condition - using database state as truth")
                trackAccumulationMetrics("STATE_CHECK", false, stateMismatch = true, raceCondition = true)
            }
            
            // PRODUCTION SAFEGUARD: Circuit breaker check
            val shouldAccumulate = !isAccumulationDisabled && 
                                   dbAccumulating && // Use DATABASE state, not UI state
                                   uiState.isRecording
            
            val snapshot = AccumulationStateSnapshot(
                isUIAccumulating = uiAccumulating,
                hasActiveMessage = hasActiveMessage,
                activeMessageId = activeMessageId,
                shouldAccumulate = shouldAccumulate
            )
            
            // Track circuit breaker blocks
            if (dbAccumulating && isAccumulationDisabled) {
                Log.w(TAG, "🚨 Accumulation blocked by circuit breaker - protecting against corruption")
                trackAccumulationMetrics("STATE_CHECK", false, stateMismatch = false, raceCondition = true)
            }
            
            return@withLock snapshot
        }
    }
    
    /**
     * SENTENCE VALIDATION: Check if text is a complete sentence ready for translation
     * This works with the sentence accumulation in SonioxStreamingService to prevent fragmentation
     */
    private fun isValidSentenceForTranslation(text: String): Boolean {
        val trimmedText = text.trim()
        if (trimmedText.length < SentenceDetectionConstants.MIN_SENTENCE_LENGTH) {
            return false
        }
        
        // Check for Korean punctuation endings using shared constants
        if (SentenceDetectionConstants.KOREAN_PUNCTUATION.any { trimmedText.endsWith(it) }) {
            Log.d(TAG, "Valid sentence: punctuation ending detected")
            return true
        }
        
        // Check for common Korean sentence endings using shared constants
        if (SentenceDetectionConstants.KOREAN_SENTENCE_ENDINGS.any { trimmedText.endsWith(it) }) {
            Log.d(TAG, "Valid sentence: Korean ending detected")
            return true
        }
        
        // For longer texts without obvious endings, consider them potentially valid
        // (This handles cases where timeout processing from SonioxStreamingService sends longer fragments)
        if (trimmedText.length >= SentenceDetectionConstants.MIN_LENGTH_FOR_TIMEOUT_COMPLETION) {
            Log.d(TAG, "Valid sentence: sufficient length (${trimmedText.length} chars)")
            return true
        }
        
        Log.d(TAG, "Invalid sentence: too short or incomplete - '${trimmedText.take(30)}...'")
        return false
    }
    
    /**
     * Clear current partial translation only when explicitly requested
     * For real-time display, keep showing accumulated text to user
     */
    private fun clearCurrentTranslation() {
        _uiState.update { currentState ->
            // PRESERVE USER TEXT: Only clear states, keep text visible
            currentState.copy(
                currentTranslationState = TranslationState.Idle,
                isTranslating = false,
                isEnhancing = false,
                isLoading = false
                // KEEP: currentPartialText and currentPartialTranslation remain visible
            )
        }
    }
    
    /**
     * Clear all messages - this is an EXPLICIT user action that finalizes any active message
     */
    fun clearAllMessages() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "User explicitly requested to clear all messages")
                
                // Finalize any active message before clearing all messages
                if (currentActiveMessageId != null) {
                    Log.d(TAG, "Finalizing active message before clearing all messages")
                    finalizeActiveMessage()
                }
                
                // Clear database and session
                translationRepository.clearAllMessages()
                translationManager.clearSession()
                
                // Reset all state
                currentActiveMessageId = null
                _uiState.update {
                    it.copy(
                        isAccumulatingMessage = false,
                        currentPartialText = null,
                        currentPartialTranslation = null,
                        error = null
                    )
                }
                
                Log.d(TAG, "All messages cleared successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all messages", e)
                handleTranslationError("", e)
            }
        }
    }
    
    /**
     * Clear current error
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Force start a new message, breaking current accumulation if active
     * This is the ONLY time we finalize a message outside of explicit conversation clearing
     */
    fun forceNewMessage() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "User explicitly requested new message")
                
                // If we have an active message, finalize it first
                if (currentActiveMessageId != null) {
                    Log.d(TAG, "Finalizing current active message before starting new one")
                    finalizeActiveMessage()
                } else {
                    Log.d(TAG, "No active message to finalize")
                }
                
                // Reset accumulation state but preserve visible text
                _uiState.update { 
                    it.copy(
                        isAccumulatingMessage = false,
                        // KEEP: currentPartialText and currentPartialTranslation visible
                    ) 
                }
                
                Log.d(TAG, "New message ready - accumulation reset")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to force new message", e)
                handleTranslationError("", e)
            }
        }
    }

    /**
     * Clear current conversation and start new one
     * This is an EXPLICIT user action that finalizes the current message
     */
    fun clearConversation() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "User explicitly requested new conversation")
                
                // Finalize any active message before clearing conversation
                if (currentActiveMessageId != null) {
                    Log.d(TAG, "Finalizing active message before clearing conversation")
                    finalizeActiveMessage()
                }
                
                // Start a new conversation - this is the ONLY place where new conversation ID is created
                val newConversationId = UUID.randomUUID().toString()
                currentConversationId = newConversationId
                currentSegmentNumber = 0
                currentActiveMessageId = null
                
                Log.d(TAG, "New conversation started with ID: $newConversationId")
                
                translationManager.clearSession()
                // Clear UI state for new conversation
                _uiState.update { 
                    it.copy(
                        currentPartialText = null, 
                        currentPartialTranslation = null,
                        error = null,
                        isLoading = false,
                        isAccumulatingMessage = false, // Reset accumulation for new conversation
                    ) 
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear conversation", e)
                handleTranslationError("", e)
            }
        }
    }
    
    /**
     * Test Gemini API connectivity (debug only)
     */
    fun testGeminiApi() {
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                startTranslation("안녕하세요 테스트입니다") // "Hello, this is a test"
            }
        }
    }
    
    /**
     * Get performance statistics
     */
    fun getDetailedStatistics(): String {
        val state = _uiState.value
        val stats = state.sessionStatistics
        val sessionDuration = (System.currentTimeMillis() - sessionStartTime) / 1000
        
        return """
            Session Statistics:
            - Duration: ${sessionDuration}s
            - Total translations: ${stats.totalTranslations}
            - Gemini translations: ${stats.geminiTranslations}
            - ML Kit fallbacks: ${stats.mlKitFallbacks}
            - Cache hits: ${stats.cacheHits}
            - User corrections: ${stats.userCorrections}
            - Average latency: ${stats.averageLatency}ms
            - Gemini success rate: ${if (stats.totalTranslations > 0) (stats.geminiTranslations * 100 / stats.totalTranslations) else 0}%
            - Cache hit rate: ${if (stats.totalTranslations > 0) (stats.cacheHits * 100 / stats.totalTranslations) else 0}%
        """.trimIndent()
    }
    
    /**
     * PRODUCTION MONITORING - Track accumulation metrics and detect issues
     */
    private fun trackAccumulationMetrics(
        attemptType: String, 
        success: Boolean, 
        stateMismatch: Boolean = false,
        raceCondition: Boolean = false
    ) {
        accumulationMetrics.totalAccumulationAttempts++
        
        if (success) {
            accumulationMetrics.successfulAccumulations++
        }
        
        if (stateMismatch) {
            accumulationMetrics.stateMismatchWarnings++
            Log.w(TAG, "🚨 PRODUCTION ALERT: State mismatch detected in $attemptType operation")
            
            // PRODUCTION ALERTING: State mismatch pattern detection
            productionAlertingService.alertStateMismatchPattern(
                mismatchCount = accumulationMetrics.stateMismatchWarnings,
                timeWindowMs = System.currentTimeMillis() - startTime,
                operation = attemptType
            )
            
            // If too many mismatches, consider disabling accumulation
            if (accumulationMetrics.stateMismatchWarnings > 10) {
                Log.e(TAG, "🚨 CRITICAL: Too many state mismatches (${accumulationMetrics.stateMismatchWarnings})")
                Log.e(TAG, "   Consider investigating race conditions or disabling accumulation")
            }
        }
        
        if (raceCondition) {
            accumulationMetrics.raceConditionsDetected++
            Log.e(TAG, "🚨 RACE CONDITION DETECTED: $attemptType - Total detected: ${accumulationMetrics.raceConditionsDetected}")
            
            // PRODUCTION ALERTING: Race condition detection
            productionAlertingService.alertRaceConditionDetected(
                operation = attemptType,
                details = "Race condition detected during $attemptType operation",
                affectedData = currentActiveMessageId
            )
        }
        
        // Log metrics periodically
        if (accumulationMetrics.totalAccumulationAttempts % 50 == 0) {
            logProductionMetrics()
        }
    }
    
    /**
     * CIRCUIT BREAKER - Handle database operation failures gracefully
     */
    private fun handleDatabaseOperationResult(success: Boolean, operation: String) {
        if (success) {
            // Reset failure counter on success
            databaseOperationFailures = 0
        } else {
            databaseOperationFailures++
            Log.w(TAG, "⚠️ Database operation failed: $operation (${databaseOperationFailures}/${maxDatabaseFailures})")
            
            if (databaseOperationFailures >= maxDatabaseFailures) {
                // Circuit breaker trips - disable accumulation temporarily
                isAccumulationDisabled = true
                accumulationMetrics.circuitBreakerTrips++
                
                Log.e(TAG, "🚨 CIRCUIT BREAKER TRIPPED: Disabling accumulation after $databaseOperationFailures failures")
                Log.e(TAG, "   This is a production safeguard to prevent data corruption")
                
                // PRODUCTION ALERTING: Circuit breaker tripped - critical alert
                productionAlertingService.alertCircuitBreakerTrip(
                    operation = operation,
                    failureCount = databaseOperationFailures,
                    reason = "Database operation failures exceeded threshold ($maxDatabaseFailures)"
                )
                
                // Auto-recovery after delay
                viewModelScope.launch {
                    delay(30_000L) // 30 second recovery window
                    if (databaseOperationFailures < maxDatabaseFailures / 2) {
                        isAccumulationDisabled = false
                        Log.i(TAG, "✅ CIRCUIT BREAKER RECOVERED: Re-enabling accumulation")
                    }
                }
            } else {
                // Alert on database failures even if circuit breaker hasn't tripped yet
                if (databaseOperationFailures > 1) {
                    productionAlertingService.alertDatabaseFailure(
                        operation = operation,
                        exception = Exception("Database operation failure #$databaseOperationFailures"),
                        retryCount = databaseOperationFailures,
                        criticalData = true
                    )
                }
            }
        }
    }
    
    /**
     * EMERGENCY KILL SWITCH - Disable accumulation if critical issues detected
     */
    fun disableAccumulationEmergency(reason: String) {
        isAccumulationDisabled = true
        Log.e(TAG, "🚨 EMERGENCY: Accumulation disabled - $reason")
        Log.e(TAG, "   Call enableAccumulation() to re-enable after fixing the issue")
    }
    
    fun enableAccumulation() {
        isAccumulationDisabled = false
        databaseOperationFailures = 0
        Log.i(TAG, "✅ Accumulation manually re-enabled")
    }
    
    /**
     * PRODUCTION METRICS - Log detailed accumulation statistics
     */
    private fun logProductionMetrics() {
        val metrics = accumulationMetrics
        val successRate = if (metrics.totalAccumulationAttempts > 0) {
            (metrics.successfulAccumulations * 100) / metrics.totalAccumulationAttempts
        } else 0
        
        Log.i(TAG, """
            📊 ACCUMULATION METRICS:
            - Total attempts: ${metrics.totalAccumulationAttempts}
            - Success rate: ${successRate}%
            - State mismatches: ${metrics.stateMismatchWarnings}
            - Race conditions: ${metrics.raceConditionsDetected}
            - Circuit breaker trips: ${metrics.circuitBreakerTrips}
            - Currently disabled: $isAccumulationDisabled
        """.trimIndent())
    }
    
    fun getAccumulationHealthReport(): String {
        val metrics = accumulationMetrics
        val successRate = if (metrics.totalAccumulationAttempts > 0) {
            (metrics.successfulAccumulations * 100) / metrics.totalAccumulationAttempts
        } else 100
        
        val health = when {
            isAccumulationDisabled -> "🚨 DISABLED"
            successRate >= 95 -> "✅ HEALTHY"
            successRate >= 85 -> "⚠️ DEGRADED"
            else -> "🚨 CRITICAL"
        }
        
        return """
            Accumulation System Health: $health
            Success Rate: ${successRate}%
            Database Failures: ${databaseOperationFailures}/${maxDatabaseFailures}
            State Mismatches: ${metrics.stateMismatchWarnings}
            Race Conditions: ${metrics.raceConditionsDetected}
        """.trimIndent()
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Cancel all jobs for clean lifecycle management
        translationJob?.cancel()
        activeMessageUpdateJob?.cancel()
        finalizationJob?.cancel() // Cancel professional finalization
        
        Log.d(TAG, "OptimizedTranslationViewModel cleared - all jobs cancelled")
        Log.i(TAG, getDetailedStatistics())
    }
}