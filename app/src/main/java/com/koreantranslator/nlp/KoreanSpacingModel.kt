package com.koreantranslator.nlp

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Korean Word Spacing Model using TensorFlow Lite
 * 
 * This model implements character-level sequence labeling for Korean word segmentation.
 * It uses a bidirectional LSTM architecture trained on large Korean corpora.
 * 
 * Model Architecture:
 * - Input: Character sequence (up to 128 characters)
 * - Embedding layer for Korean characters
 * - Bidirectional LSTM layers
 * - Output: Binary labels (0: no space, 1: space after character)
 */
class KoreanSpacingModel(
    private val context: Context,
    private val interpreter: Interpreter
) {
    
    companion object {
        private const val TAG = "KoreanSpacingModel"
        
        // Model parameters
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val CHAR_VOCAB_SIZE = 5000 // Common Korean characters + special tokens
        private const val PAD_TOKEN = 0
        private const val UNK_TOKEN = 1
        private const val START_TOKEN = 2
        private const val END_TOKEN = 3
        
        // Inference parameters
        private const val BATCH_SIZE = 1
        private const val SPACE_THRESHOLD = 0.5f // Threshold for space prediction
        
        // Character mappings
        private val KOREAN_CHARS = buildKoreanCharMap()
        
        /**
         * Build character to index mapping for Korean characters
         */
        private fun buildKoreanCharMap(): Map<Char, Int> {
            val charMap = mutableMapOf<Char, Int>()
            
            // Special tokens
            charMap['\u0000'] = PAD_TOKEN // Padding
            charMap['\uFFFD'] = UNK_TOKEN // Unknown
            
            // Korean syllables (가-힣)
            var idx = 4
            for (c in '\uAC00'..'\uD7AF') {
                if (idx >= CHAR_VOCAB_SIZE) break
                charMap[c] = idx++
            }
            
            // Korean Jamo (ㄱ-ㅣ)
            for (c in '\u3131'..'\u318E') {
                if (idx >= CHAR_VOCAB_SIZE) break
                charMap[c] = idx++
            }
            
            // Common punctuation and numbers
            val commonChars = "0123456789.,!?~@#$%^&*()[]{}:;'\"\\/-+=_ \n\t"
            for (c in commonChars) {
                if (idx >= CHAR_VOCAB_SIZE) break
                charMap[c] = idx++
            }
            
            // English letters (for mixed text)
            for (c in 'a'..'z') {
                if (idx >= CHAR_VOCAB_SIZE) break
                charMap[c] = idx++
            }
            for (c in 'A'..'Z') {
                if (idx >= CHAR_VOCAB_SIZE) break
                charMap[c] = idx++
            }
            
            return charMap
        }
    }
    
    /**
     * Segment Korean text by adding appropriate spaces
     */
    fun segment(text: String): SegmentationResult {
        if (text.isBlank()) {
            return SegmentationResult(text, text, 1.0f, emptyList())
        }
        
        try {
            // Preprocess text
            val processedText = preprocessText(text)
            
            // Convert to character indices
            val inputIndices = textToIndices(processedText)
            
            // Prepare input tensor
            val inputBuffer = prepareInputBuffer(inputIndices)
            
            // Prepare output tensor
            val outputBuffer = ByteBuffer.allocateDirect(4 * MAX_SEQUENCE_LENGTH)
                .order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Process output to get space predictions
            val spacePredictions = processOutput(outputBuffer, processedText.length)
            
            // Apply spacing based on predictions
            val segmentedText = applySpacing(processedText, spacePredictions)
            
            // Calculate confidence
            val confidence = calculateConfidence(spacePredictions)
            
            Log.d(TAG, "Segmentation complete - Input: ${text.take(30)}... Output: ${segmentedText.take(30)}...")
            
            return SegmentationResult(
                originalText = text,
                segmentedText = segmentedText,
                confidence = confidence,
                spacePredictions = spacePredictions
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during segmentation", e)
            // Return original text on error
            return SegmentationResult(text, text, 0.0f, emptyList())
        }
    }
    
    /**
     * ENHANCED: Intelligent preprocessing that preserves correct spacing while fixing broken patterns
     */
    private fun preprocessText(text: String): String {
        // Check if text has broken word patterns that need aggressive re-segmentation
        val hasBrokenPatterns = detectBrokenWordPatterns(text)
        
        return if (hasBrokenPatterns) {
            // Aggressive preprocessing for broken patterns
            Log.d(TAG, "Broken patterns detected, removing all spacing for reconstruction: $text")
            text
                .replace(Regex("\\s+"), "") // Remove ALL spaces for complete reconstruction
                .take(MAX_SEQUENCE_LENGTH - 2)
        } else {
            // Conservative preprocessing - only clean up excessive spacing
            Log.d(TAG, "Clean text detected, preserving most spacing: $text")
            text
                .replace(Regex("\\s{3,}"), " ") // Only fix 3+ consecutive spaces
                .replace(Regex("([\uac00-\ud7a3])\\s{2,}([\uac00-\ud7a3])"), "$1 $2") // Fix double spaces in Korean
                .take(MAX_SEQUENCE_LENGTH - 2)
        }
    }
    
    /**
     * NEW: Detect broken word patterns that need aggressive re-segmentation
     */
    private fun detectBrokenWordPatterns(text: String): Boolean {
        val brokenPatterns = listOf(
            // Korean syllables with spaces that shouldn't be there
            Regex("[가-힣]\\s+[가-힣]\\s+[가-힣]"), // 3+ syllables with spaces
            
            // Known broken word fragments
            Regex("괜\\s+"), // 괜 as fragment
            Regex("\\s+찮"), // 찮 as fragment
            Regex("나기\\s+"), // 나기 as fragment
            Regex("\\s+귀찮"), // 귀찮 as fragment
            
            // Verb endings separated by space
            Regex("\\s+습니다"),
            Regex("\\s+세요"),
            Regex("\\s+어요"),
            Regex("\\s+아요"),
            
            // Common words broken by space
            Regex("안\\s+녕"),
            Regex("감\\s+사"),
            Regex("죄\\s+송")
        )
        
        return brokenPatterns.any { it.containsMatchIn(text) }
    }
    
    /**
     * Convert text to character indices
     */
    private fun textToIndices(text: String): IntArray {
        val indices = IntArray(MAX_SEQUENCE_LENGTH)
        
        // Add start token
        indices[0] = START_TOKEN
        
        // Convert characters to indices
        for (i in text.indices) {
            val charIndex = i + 1
            if (charIndex >= MAX_SEQUENCE_LENGTH - 1) break
            
            val char = text[i]
            indices[charIndex] = KOREAN_CHARS[char] ?: UNK_TOKEN
        }
        
        // Add end token if there's room
        val endIndex = min(text.length + 1, MAX_SEQUENCE_LENGTH - 1)
        indices[endIndex] = END_TOKEN
        
        // Rest remains as PAD_TOKEN (0)
        
        return indices
    }
    
    /**
     * Prepare input buffer for TensorFlow Lite
     */
    private fun prepareInputBuffer(indices: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * MAX_SEQUENCE_LENGTH)
            .order(ByteOrder.nativeOrder())
        
        for (idx in indices) {
            buffer.putFloat(idx.toFloat())
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * Process model output to get space predictions
     */
    private fun processOutput(outputBuffer: ByteBuffer, textLength: Int): List<Float> {
        outputBuffer.rewind()
        
        val predictions = mutableListOf<Float>()
        for (i in 0 until min(textLength, MAX_SEQUENCE_LENGTH)) {
            val prediction = outputBuffer.getFloat()
            predictions.add(prediction)
        }
        
        return predictions
    }
    
    /**
     * ENHANCED: Apply spacing based on model predictions with broken pattern awareness
     */
    private fun applySpacing(text: String, predictions: List<Float>): String {
        if (text.isEmpty() || predictions.isEmpty()) {
            return text
        }
        
        // Check if we're dealing with broken patterns (affects threshold)
        val hasBrokenPatterns = detectBrokenWordPatterns(text)
        val adaptiveThreshold = if (hasBrokenPatterns) {
            SPACE_THRESHOLD * 0.7f // Lower threshold for broken patterns
        } else {
            SPACE_THRESHOLD
        }
        
        val result = StringBuilder()
        
        for (i in text.indices) {
            result.append(text[i])
            
            // Check if we should add a space after this character
            if (i < predictions.size && i < text.length - 1) {
                val spaceProbability = predictions[i]
                
                if (spaceProbability >= adaptiveThreshold) {
                    // Enhanced rule-based validation with broken pattern awareness
                    if (shouldAddSpaceEnhanced(text, i, spaceProbability, hasBrokenPatterns)) {
                        result.append(' ')
                    }
                }
            }
        }
        
        // Post-process to fix any remaining known broken patterns
        var finalResult = result.toString().trim()
        if (hasBrokenPatterns) {
            finalResult = postProcessBrokenPatterns(finalResult)
        }
        
        return finalResult
    }
    
    /**
     * ENHANCED: Rule-based validation for space insertion with broken pattern awareness
     * Combines ML predictions with linguistic rules and broken pattern detection
     */
    private fun shouldAddSpaceEnhanced(text: String, position: Int, probability: Float, hasBrokenPatterns: Boolean): Boolean {
        val currentChar = text[position]
        val nextChar = if (position + 1 < text.length) text[position + 1] else null
        
        // Never add space before punctuation
        if (nextChar != null && nextChar in ".,!?;:)]}") {
            return false
        }
        
        // Never add space after opening punctuation
        if (currentChar in "([{") {
            return false
        }
        
        // For broken patterns, be more aggressive about adding spaces
        if (hasBrokenPatterns) {
            // Check if this position would separate a known broken word fragment
            if (wouldSeparateBrokenFragment(text, position)) {
                return false // Don't add space - this would break a word further
            }
            
            // Lower threshold for broken patterns
            if (probability > 0.5f) {
                return true
            }
        }
        
        // High confidence predictions override rules
        if (probability > 0.8f) {
            return true
        }
        
        // Medium confidence - apply more rules
        if (probability > 0.6f) {
            // Check for Korean particles that should attach
            val remainingText = text.substring(position + 1)
            val particles = listOf("은", "는", "이", "가", "을", "를", "에", "에서", "부터", "까지", "도", "만")
            
            for (particle in particles) {
                if (remainingText.startsWith(particle)) {
                    return false // Don't add space before particles
                }
            }
            
            return true
        }
        
        // Low confidence - be conservative
        return false
    }
    
    /**
     * NEW: Check if adding a space at this position would separate a known broken word fragment
     */
    private fun wouldSeparateBrokenFragment(text: String, position: Int): Boolean {
        // Get surrounding context
        val contextStart = kotlin.math.max(0, position - 3)
        val contextEnd = kotlin.math.min(text.length, position + 4)
        val context = text.substring(contextStart, contextEnd)
        
        // Known fragments that should not be separated
        val fragmentPatterns = listOf(
            "괜찮", "괜아", "아찮", "찮요",
            "나가기", "나기", "기귀", "귀찮",
            "안녕하", "안녕", "녕하", "하세요",
            "감사합", "감사", "사합", "합니다",
            "죄송합", "죄송", "송합"
        )
        
        // Check if the current position is within a known fragment
        for (fragment in fragmentPatterns) {
            if (context.contains(fragment)) {
                val fragmentStart = context.indexOf(fragment)
                val absoluteFragmentStart = contextStart + fragmentStart
                val absoluteFragmentEnd = absoluteFragmentStart + fragment.length
                
                // If the space position is within the fragment, don't add space
                if (position >= absoluteFragmentStart && position < absoluteFragmentEnd) {
                    Log.d(TAG, "Preventing space separation of fragment '$fragment' at position $position")
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * NEW: Post-process to fix any remaining broken patterns after spacing
     */
    private fun postProcessBrokenPatterns(text: String): String {
        var processed = text
        
        // Fix any remaining broken patterns that the ML model might have missed
        val finalFixes = mapOf(
            Regex("괜\\s+찮\\s*아요") to "괜찮아요",
            Regex("나\\s*가\\s*기\\s+귀찮\\s*아요") to "나가기 귀찮아요",
            Regex("안\\s*녕\\s+하세요") to "안녕하세요",
            Regex("감\\s*사\\s+합니다") to "감사합니다",
            Regex("죄\\s*송\\s+합니다") to "죄송합니다"
        )
        
        finalFixes.forEach { (pattern, replacement) ->
            val before = processed
            processed = pattern.replace(processed, replacement)
            if (before != processed) {
                Log.d(TAG, "Post-processing fix applied: '${pattern.pattern}' -> '$replacement'")
            }
        }
        
        return processed
    }
    
    /**
     * Calculate overall confidence of segmentation
     */
    private fun calculateConfidence(predictions: List<Float>): Float {
        if (predictions.isEmpty()) return 0.0f
        
        // Calculate average distance from threshold
        // Higher distance = higher confidence
        var totalConfidence = 0.0f
        for (pred in predictions) {
            val distance = kotlin.math.abs(pred - SPACE_THRESHOLD)
            totalConfidence += distance
        }
        
        // Normalize to 0-1 range
        val avgConfidence = totalConfidence / predictions.size
        return (avgConfidence * 2).coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Process text in chunks for long documents
     */
    fun segmentLongText(text: String): SegmentationResult {
        if (text.length <= MAX_SEQUENCE_LENGTH) {
            return segment(text)
        }
        
        // Split into overlapping chunks
        val chunkSize = MAX_SEQUENCE_LENGTH - 20 // Leave room for overlap
        val overlap = 10
        
        val chunks = mutableListOf<String>()
        var startIdx = 0
        
        while (startIdx < text.length) {
            val endIdx = min(startIdx + chunkSize, text.length)
            chunks.add(text.substring(startIdx, endIdx))
            startIdx += chunkSize - overlap
        }
        
        // Process each chunk
        val segmentedChunks = mutableListOf<String>()
        var totalConfidence = 0.0f
        
        for (chunk in chunks) {
            val result = segment(chunk)
            segmentedChunks.add(result.segmentedText)
            totalConfidence += result.confidence
        }
        
        // Merge chunks (handle overlaps)
        val mergedText = mergeChunks(segmentedChunks, overlap)
        
        return SegmentationResult(
            originalText = text,
            segmentedText = mergedText,
            confidence = totalConfidence / chunks.size,
            spacePredictions = emptyList() // Too long to return all predictions
        )
    }
    
    /**
     * Merge segmented chunks handling overlaps
     */
    private fun mergeChunks(chunks: List<String>, overlapSize: Int): String {
        if (chunks.isEmpty()) return ""
        if (chunks.size == 1) return chunks[0]
        
        val result = StringBuilder(chunks[0])
        
        for (i in 1 until chunks.size) {
            val chunk = chunks[i]
            // Skip overlap characters
            if (chunk.length > overlapSize) {
                result.append(chunk.substring(overlapSize))
            }
        }
        
        return result.toString()
    }
    
    /**
     * Release model resources
     */
    fun release() {
        try {
            // Interpreter is managed by TFLiteModelManager
            // Just clear any local resources
            Log.d(TAG, "Korean spacing model resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
    
    /**
     * Result of text segmentation
     */
    data class SegmentationResult(
        val originalText: String,
        val segmentedText: String,
        val confidence: Float,
        val spacePredictions: List<Float>
    )
}