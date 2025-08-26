package com.koreantranslator.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.*

@Entity(
    tableName = "translation_messages",
    indices = [
        Index(value = ["timestamp"], name = "index_timestamp"),
        Index(value = ["conversationId"], name = "index_conversation_id"),
        Index(value = ["originalText"], name = "index_original_text"),
        Index(value = ["translatedText"], name = "index_translated_text")
    ]
)
data class TranslationMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val originalText: String,
    val translatedText: String,
    val timestamp: Date = Date(),
    val confidence: Float = 0f,
    val isEnhanced: Boolean = false,
    val translationEngine: TranslationEngine = TranslationEngine.ML_KIT,
    val conversationId: String? = null,  // Groups messages in same conversation
    val segmentNumber: Int = 0,  // Order within conversation
    val isPartOfContinuous: Boolean = false  // Marks continuous recording segments
)

enum class TranslationEngine {
    ML_KIT,
    GEMINI_FLASH,
    HYBRID
}

data class TranslationResponse(
    val translatedText: String,
    val confidence: Float,
    val engine: TranslationEngine,
    val isEnhanced: Boolean = false,
    val modelInfo: String? = null  // Specific model name (e.g., "gemini-2.5-flash")
)

/**
 * Enhanced translation states for better UI feedback and state management
 */
sealed class TranslationState {
    object Idle : TranslationState()
    
    data class Translating(
        val originalText: String
    ) : TranslationState()
    
    data class Enhancing(
        val originalText: String  
    ) : TranslationState()
    
    data class Success(
        val originalText: String,
        val translatedText: String,
        val engine: TranslationEngine,
        val confidence: Float,
        val fromCache: Boolean = false,
        val isPartial: Boolean = false,
        val wasEnhanced: Boolean = false,
        val processingTimeMs: Long = 0L
    ) : TranslationState()
    
    data class Error(
        val originalText: String,
        val message: String,
        val canRetry: Boolean = true,
        val fallbackAvailable: Boolean = false
    ) : TranslationState()
}