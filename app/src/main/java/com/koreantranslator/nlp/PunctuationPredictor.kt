package com.koreantranslator.nlp

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Korean Punctuation Prediction Model
 * 
 * Predicts punctuation marks (period, comma, question mark, exclamation) 
 * based on context and sentence structure.
 * 
 * Model Architecture:
 * - Input: Word sequence with context window
 * - Bidirectional LSTM for context understanding
 * - Multi-class output: none, comma, period, question, exclamation
 */
class PunctuationPredictor(
    private val context: Context,
    private val interpreter: Interpreter
) {
    
    companion object {
        private const val TAG = "PunctuationPredictor"
        
        // Model parameters
        private const val MAX_WORDS = 50 // Maximum words in a sequence
        private const val WORD_EMBEDDING_SIZE = 128
        private const val CONTEXT_WINDOW = 5 // Words before and after for context
        
        // Punctuation classes
        private const val PUNCT_NONE = 0
        private const val PUNCT_COMMA = 1
        private const val PUNCT_PERIOD = 2
        private const val PUNCT_QUESTION = 3
        private const val PUNCT_EXCLAMATION = 4
        
        // Thresholds for each punctuation type
        private const val COMMA_THRESHOLD = 0.6f
        private const val PERIOD_THRESHOLD = 0.7f
        private const val QUESTION_THRESHOLD = 0.65f
        private const val EXCLAMATION_THRESHOLD = 0.75f
        
        // Korean sentence endings that indicate punctuation
        private val QUESTION_ENDINGS = setOf(
            "까", "니", "나요", "까요", "는가", "을까", "ㄹ까", 
            "는지", "을지", "ㄹ지", "던가", "나", "냐"
        )
        
        private val STATEMENT_ENDINGS = setOf(
            "다", "요", "습니다", "ㅂ니다", "네요", "군요", "는다",
            "ㄴ다", "란다", "더라", "거든", "는데", "지만"
        )
        
        private val EXCLAMATION_ENDINGS = setOf(
            "아", "야", "어라", "거라", "자", "네", "구나", "군"
        )
        
        // Common comma positions (conjunctions and connectives)
        private val COMMA_TRIGGERS = setOf(
            "그리고", "그러나", "하지만", "그래서", "그런데", "따라서",
            "그러므로", "또한", "또", "게다가", "그렇지만", "물론",
            "왜냐하면", "만약", "만일", "비록", "아무리"
        )
    }
    
    /**
     * Predict and add punctuation to text
     */
    fun predict(text: String): PunctuationResult {
        if (text.isBlank()) {
            return PunctuationResult(text, text, 1.0f, emptyList())
        }
        
        try {
            // Split text into words
            val words = text.split(Regex("\\s+"))
            if (words.isEmpty()) {
                return PunctuationResult(text, text, 1.0f, emptyList())
            }
            
            // Get punctuation predictions for each word position
            val predictions = predictPunctuation(words)
            
            // Apply punctuation based on predictions and rules
            val punctuatedText = applyPunctuation(words, predictions)
            
            // Calculate overall confidence
            val confidence = calculateConfidence(predictions)
            
            Log.d(TAG, "Punctuation added - Input: ${text.take(50)}... Output: ${punctuatedText.take(50)}...")
            
            return PunctuationResult(
                originalText = text,
                punctuatedText = punctuatedText,
                confidence = confidence,
                predictions = predictions
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during punctuation prediction", e)
            // Return original text on error
            return PunctuationResult(text, text, 0.0f, emptyList())
        }
    }
    
    /**
     * Predict punctuation for each word position
     */
    private fun predictPunctuation(words: List<String>): List<PunctuationPrediction> {
        val predictions = mutableListOf<PunctuationPrediction>()
        
        // Process words in chunks that fit the model
        val chunks = words.chunked(MAX_WORDS)
        
        for (chunk in chunks) {
            val chunkPredictions = predictChunk(chunk)
            predictions.addAll(chunkPredictions)
        }
        
        // Apply rule-based refinements
        refineWithRules(words, predictions)
        
        return predictions
    }
    
    /**
     * Predict punctuation for a chunk of words
     */
    private fun predictChunk(words: List<String>): List<PunctuationPrediction> {
        // Prepare input for the model
        val inputBuffer = prepareInput(words)
        
        // Prepare output buffer (5 classes per word)
        val outputSize = words.size * 5 * 4 // 5 classes, 4 bytes per float
        val outputBuffer = ByteBuffer.allocateDirect(outputSize)
            .order(ByteOrder.nativeOrder())
        
        // Run inference
        interpreter.run(inputBuffer, outputBuffer)
        
        // Process output
        return processOutput(outputBuffer, words.size)
    }
    
    /**
     * Prepare input buffer for the model
     */
    private fun prepareInput(words: List<String>): ByteBuffer {
        val bufferSize = MAX_WORDS * WORD_EMBEDDING_SIZE * 4
        val buffer = ByteBuffer.allocateDirect(bufferSize)
            .order(ByteOrder.nativeOrder())
        
        // Convert words to embeddings (simplified - real impl would use proper embeddings)
        for (i in 0 until MAX_WORDS) {
            if (i < words.size) {
                // Simple hash-based embedding for demo
                val word = words[i]
                val hash = word.hashCode()
                
                for (j in 0 until WORD_EMBEDDING_SIZE) {
                    val value = ((hash + j) % 256) / 256.0f
                    buffer.putFloat(value)
                }
            } else {
                // Padding
                for (j in 0 until WORD_EMBEDDING_SIZE) {
                    buffer.putFloat(0.0f)
                }
            }
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * Process model output to get punctuation predictions
     */
    private fun processOutput(outputBuffer: ByteBuffer, numWords: Int): List<PunctuationPrediction> {
        outputBuffer.rewind()
        
        val predictions = mutableListOf<PunctuationPrediction>()
        
        for (i in 0 until numWords) {
            // Read 5 class probabilities
            val probs = FloatArray(5)
            for (j in 0..4) {
                probs[j] = outputBuffer.getFloat()
            }
            
            // Find the highest probability class
            var maxProb = probs[0]
            var maxClass = 0
            for (j in 1..4) {
                if (probs[j] > maxProb) {
                    maxProb = probs[j]
                    maxClass = j
                }
            }
            
            predictions.add(PunctuationPrediction(
                punctuationType = maxClass,
                probability = maxProb,
                allProbabilities = probs.toList()
            ))
        }
        
        return predictions
    }
    
    /**
     * Refine predictions using Korean grammar rules
     */
    private fun refineWithRules(words: List<String>, predictions: MutableList<PunctuationPrediction>) {
        for (i in words.indices) {
            val word = words[i]
            val isLastWord = i == words.size - 1
            val nextWord = if (i < words.size - 1) words[i + 1] else null
            
            // Check for question endings
            if (endsWithQuestionMarker(word)) {
                if (i < predictions.size) {
                    predictions[i] = predictions[i].copy(
                        punctuationType = PUNCT_QUESTION,
                        probability = maxOf(predictions[i].probability, QUESTION_THRESHOLD)
                    )
                }
            }
            
            // Check for statement endings
            if (endsWithStatementMarker(word) && isLastWord) {
                if (i < predictions.size && predictions[i].punctuationType == PUNCT_NONE) {
                    predictions[i] = predictions[i].copy(
                        punctuationType = PUNCT_PERIOD,
                        probability = maxOf(predictions[i].probability, PERIOD_THRESHOLD)
                    )
                }
            }
            
            // Check for exclamation endings
            if (endsWithExclamationMarker(word)) {
                if (i < predictions.size) {
                    predictions[i] = predictions[i].copy(
                        punctuationType = PUNCT_EXCLAMATION,
                        probability = maxOf(predictions[i].probability, EXCLAMATION_THRESHOLD)
                    )
                }
            }
            
            // Check for comma positions
            if (nextWord != null && COMMA_TRIGGERS.contains(nextWord)) {
                if (i < predictions.size) {
                    predictions[i] = predictions[i].copy(
                        punctuationType = PUNCT_COMMA,
                        probability = maxOf(predictions[i].probability, COMMA_THRESHOLD)
                    )
                }
            }
            
            // Ensure last word has ending punctuation
            if (isLastWord && i < predictions.size) {
                if (predictions[i].punctuationType == PUNCT_NONE) {
                    // Default to period if no other punctuation detected
                    predictions[i] = predictions[i].copy(
                        punctuationType = PUNCT_PERIOD,
                        probability = 0.8f
                    )
                }
            }
        }
    }
    
    /**
     * Check if word ends with question marker
     */
    private fun endsWithQuestionMarker(word: String): Boolean {
        return QUESTION_ENDINGS.any { word.endsWith(it) }
    }
    
    /**
     * Check if word ends with statement marker
     */
    private fun endsWithStatementMarker(word: String): Boolean {
        return STATEMENT_ENDINGS.any { word.endsWith(it) }
    }
    
    /**
     * Check if word ends with exclamation marker
     */
    private fun endsWithExclamationMarker(word: String): Boolean {
        return EXCLAMATION_ENDINGS.any { word.endsWith(it) }
    }
    
    /**
     * Apply punctuation to text based on predictions
     */
    private fun applyPunctuation(
        words: List<String>,
        predictions: List<PunctuationPrediction>
    ): String {
        val result = StringBuilder()
        
        for (i in words.indices) {
            result.append(words[i])
            
            if (i < predictions.size) {
                val pred = predictions[i]
                
                when (pred.punctuationType) {
                    PUNCT_COMMA -> {
                        if (pred.probability >= COMMA_THRESHOLD) {
                            result.append(",")
                        }
                    }
                    PUNCT_PERIOD -> {
                        if (pred.probability >= PERIOD_THRESHOLD) {
                            result.append(".")
                        }
                    }
                    PUNCT_QUESTION -> {
                        if (pred.probability >= QUESTION_THRESHOLD) {
                            result.append("?")
                        }
                    }
                    PUNCT_EXCLAMATION -> {
                        if (pred.probability >= EXCLAMATION_THRESHOLD) {
                            result.append("!")
                        }
                    }
                }
            }
            
            // Add space if not last word
            if (i < words.size - 1) {
                result.append(" ")
            }
        }
        
        return result.toString()
    }
    
    /**
     * Calculate overall confidence
     */
    private fun calculateConfidence(predictions: List<PunctuationPrediction>): Float {
        if (predictions.isEmpty()) return 0.0f
        
        val avgProbability = predictions.map { it.probability }.average().toFloat()
        return avgProbability
    }
    
    /**
     * Process streaming text with partial punctuation
     */
    fun predictStreaming(text: String, isComplete: Boolean): PunctuationResult {
        // For streaming, only add punctuation if we detect a clear sentence boundary
        // or if the stream is marked as complete
        
        val words = text.split(Regex("\\s+"))
        if (words.isEmpty()) {
            return PunctuationResult(text, text, 1.0f, emptyList())
        }
        
        val lastWord = words.last()
        
        // Check if last word suggests sentence end
        val shouldPunctuate = isComplete || 
            endsWithStatementMarker(lastWord) ||
            endsWithQuestionMarker(lastWord) ||
            endsWithExclamationMarker(lastWord)
        
        return if (shouldPunctuate) {
            predict(text)
        } else {
            // Return text without final punctuation for incomplete streams
            PunctuationResult(text, text, 0.5f, emptyList())
        }
    }
    
    /**
     * Release model resources
     */
    fun release() {
        try {
            // Interpreter is managed by TFLiteModelManager
            Log.d(TAG, "Punctuation predictor resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
    
    /**
     * Punctuation prediction for a word position
     */
    data class PunctuationPrediction(
        val punctuationType: Int,
        val probability: Float,
        val allProbabilities: List<Float>
    )
    
    /**
     * Result of punctuation prediction
     */
    data class PunctuationResult(
        val originalText: String,
        val punctuatedText: String,
        val confidence: Float,
        val predictions: List<PunctuationPrediction>
    )
}