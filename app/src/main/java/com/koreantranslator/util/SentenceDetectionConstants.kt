package com.koreantranslator.util

/**
 * Shared constants for Korean sentence detection and processing
 * Used across speech recognition and translation validation layers
 */
object SentenceDetectionConstants {
    
    /**
     * Common Korean sentence endings that indicate sentence completion
     * Covers formal, informal, and question patterns
     */
    val KOREAN_SENTENCE_ENDINGS = listOf(
        // Formal endings
        "습니다", "입니다",
        
        // Informal polite endings  
        "네요", "어요", "아요", "죠", "는데요", "거예요", "예요", "이에요",
        
        // Action and request endings
        "해요", "세요", "께요", "래요", "대요", "게요",
        
        // Future and intention endings
        "려고요", "을게요", "를게요"
    )
    
    /**
     * Korean punctuation marks that indicate sentence completion
     */
    val KOREAN_PUNCTUATION = listOf(".", "?", "!", "。", "？", "！")
    
    /**
     * Minimum length for a valid sentence (in characters)
     * Prevents processing of single syllables or very short fragments
     */
    const val MIN_SENTENCE_LENGTH = 3
    
    /**
     * Timeout for incomplete sentences (in milliseconds)
     * After this time, incomplete sentences are forced to complete
     */
    const val SENTENCE_TIMEOUT_MS = 1500L
    
    /**
     * Maximum sentence length (in characters) to prevent memory issues
     * Protects against runaway accumulation during long speech sessions
     */
    const val MAX_SENTENCE_LENGTH = 500
    
    /**
     * Minimum length for longer sentences to be considered valid without explicit endings
     * Used for timeout-based completion of potentially valid longer phrases
     */
    const val MIN_LENGTH_FOR_TIMEOUT_COMPLETION = 10
    
    /**
     * Debounce time for partial text updates (in milliseconds)
     * Prevents excessive UI updates from rapid speech recognition fragments
     */
    const val PARTIAL_TEXT_DEBOUNCE_MS = 300L
    
    /**
     * Minimum length for partial text to be displayed
     * Prevents showing single character fragments or meaningless snippets
     */
    const val MIN_PARTIAL_TEXT_LENGTH = 2
    
    /**
     * Throttle interval for partial text updates (in milliseconds)
     * INCREASED: 250ms to reduce UI fragmentation and improve stability
     * Maximum frequency of UI updates during continuous speech
     */
    const val PARTIAL_UPDATE_THROTTLE_MS = 250L
}