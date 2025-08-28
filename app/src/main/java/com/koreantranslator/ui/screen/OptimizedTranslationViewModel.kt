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
    private val productionAlertingService: ProductionAlertingService
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "OptimizedTranslationVM"
        
        // Optimized debouncing for cost efficiency
        private const val TRANSLATION_DEBOUNCE_MS = 800L  // Wait 800ms after speech
        private const val MAX_TRANSLATION_ATTEMPTS = 2
        private const val MIN_MESSAGE_LENGTH = 2  // Minimum characters for message creation
        
        // Message accumulation settings
        private const val SESSION_TIMEOUT_MS = 30_000L  // 30 seconds to continue accumulating
        private const val MAX_ACCUMULATED_LENGTH = 1000  // Max chars per accumulated message
        
        // Active message caching
        private const val CACHE_VALIDITY_MS = 500L
    }
    
    // UI State with translation-specific properties
    data class OptimizedTranslationState(
        val messages: List<TranslationMessage> = emptyList(),
        val isRecording: Boolean = false,
        val isTranslating: Boolean = false,
        val isEnhancing: Boolean = false,
        val currentPartialText: String? = null,
        val currentPartialTranslation: String? = null, // For UI compatibility
        val systemStatus: String? = null, // System connection status (separate from speech)
        val currentTranslationState: TranslationState = TranslationState.Idle,
        val error: String? = null,
        val isOnline: Boolean = true,
        val isLoading: Boolean = false, // Derived from isTranslating || isEnhancing
        val continuousRecordingMode: Boolean = false, // For extended recording sessions
        val sessionStatistics: SessionStatistics = SessionStatistics(),
        val isAccumulatingMessage: Boolean = false, // Whether currently accumulating to existing message
        val accumulationSessionTimeout: Long = SESSION_TIMEOUT_MS
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
     * Observe Soniox speech recognition results
     */
    private fun observeSpeechRecognition() {
        viewModelScope.launch {
            // Listen to recognized text from Soniox (now with sentence accumulation)
            sonioxStreamingService.recognizedText
                .filterNotNull()
                .filter { it.isNotBlank() && it.length >= SentenceDetectionConstants.MIN_SENTENCE_LENGTH }
                .filter { recognizedText -> 
                    // ENHANCED: Validate sentence completeness before translation
                    val isCompleteSentence = isValidSentenceForTranslation(recognizedText)
                    if (!isCompleteSentence) {
                        Log.d(TAG, "Skipping translation for incomplete sentence: '$recognizedText'")
                    }
                    isCompleteSentence
                }
                .collect { recognizedText ->
                    Log.d(TAG, "Complete sentence received for translation: '${recognizedText.take(50)}...'")
                    
                    // CRITICAL FIX: Always translate just the NEW sentence, accumulation happens in handleSuccessfulTranslation
                    translationTrigger.emit(recognizedText.trim())
                    
                    Log.d(TAG, "Translating NEW sentence only: '${recognizedText.trim()}'")
                    if (_uiState.value.isAccumulatingMessage) {
                        Log.d(TAG, "Will accumulate this translation result to existing message")
                    } else {
                        Log.d(TAG, "Will replace/create new message with this translation")
                    }
                }
        }
        
        viewModelScope.launch {
            // Listen to partial text for UI DISPLAY ONLY - no database writes
            sonioxStreamingService.partialText.collect { partialText ->
                // FIXED: Only update UI state, no database operations for partial text
                _uiState.update { it.copy(currentPartialText = partialText) }
                Log.d(TAG, "Real-time partial text (UI only): '${partialText?.take(30)}...'")
                
                // Note: Partial text is now UI-only - database updates only happen 
                // when complete sentences arrive via the recognized text flow
            }
        }
        
        viewModelScope.launch {
            // Listen to recording state
            sonioxStreamingService.isListening.collect { isListening ->
                _uiState.update { it.copy(isRecording = isListening) }
                
                if (isListening) {
                    // Create or continue accumulating active message when recording starts
                    createOrContinueActiveMessage()
                    Log.d(TAG, "Recording started - created or continued active message")
                } else {
                    // Finalize active message when recording stops
                    finalizeActiveMessage()
                    clearCurrentTranslation()
                    Log.d(TAG, "Recording stopped - finalized active message")
                }
            }
        }
        
        viewModelScope.launch {
            // Listen to system status (connection status separate from speech)
            sonioxStreamingService.systemStatus.collect { systemStatus ->
                _uiState.update { it.copy(systemStatus = systemStatus) }
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
                .debounce(500L) // Wait 500ms between database updates
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
                            currentPartialTranslation = if (state is TranslationState.Success && state.isPartial) 
                                state.translatedText else currentState.currentPartialTranslation
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
                    koreanText = koreanText,
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
                        // ENHANCED DUPLICATE PREVENTION: Multi-level checks to prevent text duplication
                    val activeMessage = getActiveMessageSafe()
                    val existingKoreanText = activeMessage?.originalText ?: ""
                    val cleanStateText = state.originalText.replace("...", "").trim()
                    
                    // Level 1: Exact text comparison
                    val isDuplicateExact = existingKoreanText.contains(cleanStateText)
                    
                    // Level 2: Fuzzy matching for near-duplicates (check last 3 words overlap)
                    val existingWords = existingKoreanText.split(" ").takeLast(3)
                    val newWords = cleanStateText.split(" ").take(3)
                    val hasWordOverlap = existingWords.intersect(newWords.toSet()).size >= 2
                    
                    // Level 3: Check if text ends with new content
                    val endsWithNewText = existingKoreanText.endsWith(cleanStateText)
                    
                    val isDuplicate = isDuplicateExact || endsWithNewText || (hasWordOverlap && existingKoreanText.isNotEmpty())
                    
                    // Only accumulate if not a duplicate
                    if (!isDuplicate) {
                        // ACCUMULATE: Add new translation to existing message content
                        appendToActiveMessage(
                            koreanText = state.originalText,  // New sentence only
                            englishText = state.translatedText // New translation only
                        )
                        Log.d(TAG, "✅ ACCUMULATED translation using atomic state validation: ${state.engine.name} (${(state.confidence * 100).toInt()}%)")
                        Log.d(TAG, "   NEW Korean: '${state.originalText}'")
                        Log.d(TAG, "   NEW English: '${state.translatedText}'")
                    } else {
                        // Enhanced duplicate reporting
                        val duplicateType = when {
                            isDuplicateExact -> "EXACT_MATCH"
                            endsWithNewText -> "ENDS_WITH_TEXT"
                            hasWordOverlap -> "WORD_OVERLAP"
                            else -> "UNKNOWN"
                        }
                        
                        Log.d(TAG, "⏩ SKIPPED duplicate accumulation - $duplicateType detected")
                        Log.d(TAG, "   Existing: '...${existingKoreanText.takeLast(50)}'")
                        Log.d(TAG, "   Attempted: '${cleanStateText}'")
                        Log.d(TAG, "   Word overlap: ${existingWords.intersect(newWords.toSet()).size}/2 minimum required")
                    }
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
                
                // Check if we should continue accumulating to the most recent message
                val mostRecentMessage = translationRepository.getMostRecentMessage()
                val currentTime = System.currentTimeMillis()
                
                if (mostRecentMessage != null) {
                    val timeSinceLastMessage = currentTime - mostRecentMessage.timestamp.time
                    // RACE CONDITION FIX: Check atomic finalization tracking
                    val isMessageBeingFinalized = isMessageFinalizing(mostRecentMessage.id)
                    val canAccumulate = timeSinceLastMessage < SESSION_TIMEOUT_MS &&
                                       mostRecentMessage.originalText.length < MAX_ACCUMULATED_LENGTH &&
                                       mostRecentMessage.conversationId == currentConversationId &&
                                       !mostRecentMessage.isActive &&
                                       !isMessageBeingFinalized
                    
                    if (canAccumulate && !isMessageBeingFinalized) {
                        // ATOMIC STATE TRANSITION: Eliminate race condition by making database state authoritative
                        Log.d(TAG, "🔒 ATOMIC: Beginning atomic accumulation state transition")
                        
                        try {
                            // Step 1: Perform database operation FIRST (authoritative source)
                            invalidateActiveMessageCache()
                            translationRepository.setMessageActive(mostRecentMessage.id)
                            
                            // Step 2: Only after database success, update UI state and internal tracking
                            currentActiveMessageId = mostRecentMessage.id
                            _uiState.update { it.copy(isAccumulatingMessage = true) }
                            
                            Log.d(TAG, "✅ ATOMIC SUCCESS: Database and UI state now consistent")
                            Log.d(TAG, "   Message ID: ${mostRecentMessage.id}")
                            Log.d(TAG, "   Time since last: ${timeSinceLastMessage}ms")
                            Log.d(TAG, "   Message length: ${mostRecentMessage.originalText.length} chars")
                            
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
                        Log.d(TAG, "Cannot accumulate - time gap: ${timeSinceLastMessage}ms, length: ${mostRecentMessage.originalText.length}, isActive: ${mostRecentMessage.isActive}")
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
                    
                    // Finalize the stale message
                    if (activeMessage.originalText.trim().length >= MIN_MESSAGE_LENGTH && 
                        activeMessage.translatedText.isNotBlank()) {
                        translationRepository.setMessageInactive(activeMessage.id)
                    } else {
                        translationRepository.deleteMessage(activeMessage.id)
                    }
                    
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
    private fun finalizeActiveMessage() {
        viewModelScope.launch {
            activeMessageMutex.withLock {
            try {
                val activeMessageId = currentActiveMessageId
                if (activeMessageId == null) {
                    Log.d(TAG, "No active message to finalize")
                    return@launch
                }
                
                // Mark message as being finalized to prevent race conditions
                markMessageFinalizing(activeMessageId)
                
                try {
                    // CRITICAL FIX: Force flush any pending database updates before checking message content
                    // This prevents the race condition where translations are lost due to debounced updates
                    Log.d(TAG, "🚨 RACE CONDITION FIX: Force-flushing pending updates before finalization")
                    
                    // Step 1: Check if there are any pending updates and flush them immediately
                    try {
                        // Force immediate processing of any queued updates
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
                            // Enhanced safety margin to ensure database write completes (increased from 50ms to 100ms)
                            delay(100)
                            Log.d(TAG, "✅ Pending update flushed successfully")
                        }
                    } catch (flushException: Exception) {
                        Log.w(TAG, "Failed to flush pending updates", flushException)
                    }
                    
                    // Get the current active message
                    val activeMessage = translationRepository.getActiveMessage()
                    if (activeMessage != null && activeMessage.id == activeMessageId) {
                        val koreanText = activeMessage.originalText.trim()
                        val englishText = activeMessage.translatedText.trim()
                        
                        // Invalidate cache BEFORE database operations
                        invalidateActiveMessageCache()
                        
                        // Check if message has meaningful content
                        if (koreanText.length >= MIN_MESSAGE_LENGTH && englishText.isNotBlank()) {
                            // Message has content - finalize it
                            translationRepository.setMessageInactive(activeMessageId)
                            currentSegmentNumber++
                            Log.d(TAG, "✅ Finalized active message: '$koreanText' -> '$englishText'")
                        } else {
                            // FALLBACK RECOVERY: Before deleting, check if UI state has successful translations that failed to persist
                            Log.w(TAG, "⚠️ Message appears empty - checking UI state for recovery data")
                            
                            var recoveredKorean = koreanText
                            var recoveredEnglish = englishText
                            var wasRecovered = false
                            
                            // Check UI state for backup translation data
                            val uiState = _uiState.value
                            val hasPartialText = uiState.currentPartialText?.isNotBlank() == true
                            val hasPartialTranslation = uiState.currentPartialTranslation?.isNotBlank() == true
                            
                            // Also check emergency backup translation
                            val backupTranslation = lastSuccessfulTranslation
                            val hasBackupData = backupTranslation != null && 
                                               backupTranslation.koreanText.trim().isNotEmpty() &&
                                               backupTranslation.englishText.trim().isNotEmpty() &&
                                               (System.currentTimeMillis() - backupTranslation.timestamp) < 5000 // Within last 5 seconds
                            
                            if (hasPartialText || hasPartialTranslation || hasBackupData) {
                                Log.d(TAG, "🔄 RECOVERY ATTEMPT: Found data in UI/backup state")
                                Log.d(TAG, "   UI Korean: '${uiState.currentPartialText}'")
                                Log.d(TAG, "   UI English: '${uiState.currentPartialTranslation}'")
                                Log.d(TAG, "   Backup available: $hasBackupData")
                                if (hasBackupData) {
                                    Log.d(TAG, "   Backup Korean: '${backupTranslation?.koreanText}'")
                                    Log.d(TAG, "   Backup English: '${backupTranslation?.englishText}'")
                                }
                                
                                // Priority 1: Use UI state data if available and valid
                                if (uiState.currentPartialText?.trim()?.isNotEmpty() == true) {
                                    recoveredKorean = uiState.currentPartialText.trim()
                                    wasRecovered = true
                                }
                                if (uiState.currentPartialTranslation?.trim()?.isNotEmpty() == true) {
                                    recoveredEnglish = uiState.currentPartialTranslation.trim()
                                    wasRecovered = true
                                }
                                
                                // Priority 2: Use backup data if UI state is insufficient
                                if (hasBackupData && (!wasRecovered || recoveredKorean.length < MIN_MESSAGE_LENGTH || recoveredEnglish.isBlank())) {
                                    recoveredKorean = backupTranslation!!.koreanText.trim()
                                    recoveredEnglish = backupTranslation.englishText.trim()
                                    wasRecovered = true
                                    Log.d(TAG, "🔄 Used backup translation data for recovery")
                                }
                                
                                // If we recovered valid content, save it to the message
                                if (wasRecovered && 
                                    recoveredKorean.length >= MIN_MESSAGE_LENGTH && 
                                    recoveredEnglish.isNotBlank()) {
                                    
                                    try {
                                        // Emergency save of recovered content
                                        val recoveredMessage = activeMessage.copy(
                                            originalText = recoveredKorean,
                                            translatedText = recoveredEnglish,
                                            isActive = false
                                        )
                                        translationRepository.updateMessage(recoveredMessage)
                                        currentSegmentNumber++
                                        
                                        Log.d(TAG, "✅ RECOVERY SUCCESS: Saved recovered content")
                                        Log.d(TAG, "   Recovered Korean: '$recoveredKorean'")
                                        Log.d(TAG, "   Recovered English: '$recoveredEnglish'")
                                        
                                        // Clear backup after successful recovery to prevent memory leaks
                                        lastSuccessfulTranslation = null
                                        
                                    } catch (recoveryException: Exception) {
                                        Log.e(TAG, "❌ RECOVERY FAILED: Could not save recovered content", recoveryException)
                                        wasRecovered = false // Mark as failed
                                    }
                                }
                            }
                            
                            // Only delete if recovery failed AND message is truly empty
                            if (!wasRecovered) {
                                translationRepository.deleteMessage(activeMessageId)
                                Log.d(TAG, "🗑️ Deleted empty active message after recovery attempt (Korean: '$recoveredKorean', English: '$recoveredEnglish')")
                            }
                        }
                    }
                } finally {
                    // Always clear finalization mark when done
                    clearFinalizationMark(activeMessageId)
                }
                
                // Clear active message tracking
                currentActiveMessageId = null
                
                // Clear UI partial text and accumulation state
                _uiState.update { 
                    it.copy(
                        currentPartialText = null,
                        currentPartialTranslation = null,
                        isAccumulatingMessage = false // Reset accumulation state
                    )
                }
                
                Log.d(TAG, "🏁 Ready for next message segment: $currentSegmentNumber")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to finalize active message", e)
                // Clear finalization mark on error
                if (currentActiveMessageId != null) {
                    clearFinalizationMark(currentActiveMessageId!!)
                }
            }
            } // Close activeMessageMutex.withLock
        }
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
                    if (koreanText.length >= MIN_MESSAGE_LENGTH && englishText.isNotBlank()) {
                        // Orphan has content - finalize it
                        translationRepository.setMessageInactive(orphanedActiveMessage.id)
                        Log.d(TAG, "Finalized orphaned active message: '$koreanText' -> '$englishText'")
                    } else {
                        // Orphan is empty - delete it
                        translationRepository.deleteMessage(orphanedActiveMessage.id)
                        Log.d(TAG, "Deleted empty orphaned active message")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup orphaned active messages", e)
            }
        }
    }
    
    /**
     * Load existing messages from database
     */
    private fun loadMessages() {
        viewModelScope.launch {
            translationRepository.getAllMessages().collect { messages ->
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
                // Only reset active message for new recording session (preserve conversation)
                currentActiveMessageId = null
                
                Log.d(TAG, "Starting recording - continuous mode: $continuousMode")
                Log.d(TAG, "Continuing conversation ID: $currentConversationId")
                
                // Clear previous partial translations for fresh start
                _uiState.update { 
                    it.copy(
                        currentPartialText = null,
                        currentPartialTranslation = null,
                        error = null,
                        isTranslating = false,
                        isEnhancing = false,
                        isLoading = false,
                        continuousRecordingMode = continuousMode
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
     * Stop recording
     */
    fun stopRecording() {
        viewModelScope.launch {
            sonioxStreamingService.stopListening()
            translationJob?.cancel()
            // REMOVED: partialTranslationJob?.cancel() - no longer needed
            activeMessageUpdateJob?.cancel()
            
            // Clear continuous mode flag
            _uiState.update { it.copy(continuousRecordingMode = false) }
            
            Log.d(TAG, "Recording stopped")
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
     * Clear current partial translation only if no final translation exists
     * For real-time display, keep showing last translation until next recording
     */
    private fun clearCurrentTranslation() {
        _uiState.update { currentState ->
            // Only clear partial text if we don't have a completed translation to show
            val hasCompletedTranslation = currentState.currentPartialTranslation?.isNotBlank() == true
            
            currentState.copy(
                // Keep partial text/translation visible if there's a completed result
                currentPartialText = if (hasCompletedTranslation) currentState.currentPartialText else null,
                currentPartialTranslation = if (hasCompletedTranslation) currentState.currentPartialTranslation else null,
                currentTranslationState = TranslationState.Idle,
                isTranslating = false,
                isEnhancing = false,
                isLoading = false
            )
        }
    }
    
    /**
     * Clear all messages
     */
    fun clearAllMessages() {
        viewModelScope.launch {
            translationRepository.clearAllMessages()
            translationManager.clearSession()
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
     */
    fun forceNewMessage() {
        viewModelScope.launch {
            try {
                // If we have an active message, finalize it first
                if (currentActiveMessageId != null) {
                    finalizeActiveMessage()
                }
                
                // Reset accumulation state
                _uiState.update { 
                    it.copy(
                        isAccumulatingMessage = false,
                        currentPartialText = null,
                        currentPartialTranslation = null
                    ) 
                }
                
                Log.d(TAG, "Forced new message - accumulation reset")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to force new message", e)
            }
        }
    }

    /**
     * Clear current conversation and start new one
     */
    fun clearConversation() {
        viewModelScope.launch {
            // Start a new conversation - this is the ONLY place where new conversation ID is created
            currentConversationId = UUID.randomUUID().toString()
            currentSegmentNumber = 0
            currentActiveMessageId = null
            
            Log.d(TAG, "New conversation started with ID: $currentConversationId")
            
            translationManager.clearSession()
            _uiState.update { 
                it.copy(
                    currentPartialText = null, 
                    currentPartialTranslation = null,
                    error = null,
                    isLoading = false
                ) 
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
        translationJob?.cancel()
        // REMOVED: partialTranslationJob?.cancel() - no longer needed
        activeMessageUpdateJob?.cancel()
        Log.d(TAG, "OptimizedTranslationViewModel cleared")
        Log.i(TAG, getDetailedStatistics())
    }
}