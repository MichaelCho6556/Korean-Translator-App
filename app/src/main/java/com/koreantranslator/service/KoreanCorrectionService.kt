package com.koreantranslator.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for post-processing Korean speech recognition results
 * to fix common misrecognitions and improve accuracy
 */
@Singleton
class KoreanCorrectionService @Inject constructor() {
    
    companion object {
        private const val TAG = "KoreanCorrection"
        
        // Common misrecognitions and their corrections
        // Format: incorrect -> correct  
        private val COMMON_CORRECTIONS = mapOf(
            // Greetings
            "안아세요" to "안녕하세요",
            "안 나세요" to "안녕하세요",
            "안녕 하세요" to "안녕하세요",
            "안녕하 세요" to "안녕하세요",
            "안녕히세요" to "안녕하세요",
            
            // Thank you variations
            "감사함니다" to "감사합니다",
            "감사 합니다" to "감사합니다",
            "감사해요" to "감사합니다",
            "고맙습니다" to "감사합니다",
            
            // Sorry variations
            "죄송함니다" to "죄송합니다",
            "죄송 합니다" to "죄송합니다",
            "미안함니다" to "미안합니다",
            
            // Common words
            "실례함니다" to "실례합니다",
            "실례 합니다" to "실례합니다",
            "잠시만여" to "잠시만요",
            "잠시 만요" to "잠시만요",
            "잠깐만여" to "잠깐만요",
            
            // Yes/No
            "내" to "네",
            "예" to "네",
            "아니오" to "아니요",
            "아뇨" to "아니요",
            
            // Understanding
            "알겠슴니다" to "알겠습니다",
            "알겠 습니다" to "알겠습니다",
            "알았어요" to "알겠어요",
            
            // Questions
            "뭐에요" to "뭐예요",
            "뭐 에요" to "뭐예요",
            "어떻해요" to "어떻게요",
            "어떡해요" to "어떻게요",
            
            // Common phrases often misrecognized
            "어서오세요" to "어서 오세요",
            "어서 오 세요" to "어서 오세요",
            "괜찮아요" to "괜찮아요",
            "괜찬아요" to "괜찮아요",
            "괜찬 아요" to "괜찮아요",
            "저기요" to "저기요",
            "저 기요" to "저기요",
            "여기요" to "여기요",
            "여 기요" to "여기요",
            "맞아요" to "맞아요",
            "마자요" to "맞아요",
            "그래요" to "그래요",
            "그레요" to "그래요",
            "어디에요" to "어디예요",
            "어디 에요" to "어디예요",
            "뭐라고요" to "뭐라고요",
            "뭐라 고요" to "뭐라고요",
            "모르겠어요" to "모르겠어요",
            "모르겠 어요" to "모르겠어요",
            "도와주세요" to "도와주세요",
            "도와 주세요" to "도와주세요",
            
            // Business/formal expressions
            "수고하세요" to "수고하세요",
            "수고 하세요" to "수고하세요",
            "안녕히가세요" to "안녕히 가세요",
            "안녕히계세요" to "안녕히 계세요",
            "잘부탁드립니다" to "잘 부탁드립니다",
            "잘 부탁 드립니다" to "잘 부탁드립니다"
        )
        
        // Korean phonological rules for accurate recognition
        // These handle the systematic sound changes in Korean
        private val PHONOLOGICAL_RULES = mapOf(
            // Nasalization (비음화)
            // ㄱ → ㅇ before ㄴ/ㅁ
            "국물" to "궁물",
            "한국말" to "한궁말",
            "백만" to "뱅만",
            "학년" to "항년",
            "막내" to "망내",
            
            // ㄷ → ㄴ before ㄴ/ㅁ
            "듣는" to "든는",
            "닫는" to "단는",
            "믿는" to "민는",
            "받는" to "반는",
            "있는" to "인는",
            "없는" to "엄는",
            "같는" to "간는",
            
            // ㅂ → ㅁ before ㄴ/ㅁ
            "법무" to "범무",
            "십년" to "심년",
            "입니다" to "임니다",
            "합니다" to "함니다",
            "갑니다" to "감니다",
            "없니" to "엄니",
            
            // Palatalization (구개음화)
            "같이" to "가치",
            "곧이" to "고지",
            "붙이다" to "부치다",
            "해돋이" to "해도지",
            "굳이" to "구지",
            
            // Assimilation (동화)
            "신라" to "실라",
            "실내" to "실래",
            "설날" to "설랄",
            "일년" to "일련",
            "천리" to "철리",
            "난로" to "날로",
            "원래" to "월래",
            
            // H-deletion (ㅎ탈락)
            "좋아" to "조아",
            "놓아" to "노아",
            "많아" to "마나",
            "싫어" to "시러",
            "않아" to "아나",
            
            // Tensification (경음화)
            "학교" to "학꾜",
            "국가" to "국까",
            "역사" to "역싸",
            "박자" to "박짜",
            "낙지" to "낙찌"
        )
        
        // Phonetic patterns - REDUCED for Soniox high accuracy
        // Only fix the most critical spacing issues
        private val PHONETIC_PATTERNS = listOf(
            // Only normalize multiple spaces and trim
            Pair(Regex("\\s{3,}"), " "), // Only fix 3+ spaces (Soniox rarely has double spaces)
            Pair(Regex("^\\s+|\\s+$"), ""), // Trim leading/trailing spaces
            // DO NOT remove spaces before particles - Soniox handles this correctly
        )
        
        // Context-aware corrections based on surrounding words
        private val CONTEXT_CORRECTIONS = mapOf(
            // If "안녕" appears alone or with wrong suffix, correct it
            Regex("안녕\\s*$") to "안녕하세요",
            Regex("^안녕\\s") to "안녕하세요 ",
            
            // Fix incomplete sentences
            Regex("감사\\s*$") to "감사합니다",
            Regex("죄송\\s*$") to "죄송합니다",
            Regex("실례\\s*$") to "실례합니다"
        )
    }
    
    /**
     * FIXED: Apply corrections to recognized Korean text
     * CRITICAL: Removed high-confidence bypass that was preventing word reconstruction
     */
    fun correctKoreanText(text: String, sourceConfidence: Float = 0.95f): CorrectionResult {
        var correctedText = text
        val corrections = mutableListOf<Correction>()
        var confidenceBoost = 0f
        
        // CRITICAL FIX: Always apply Korean word validation and reconstruction
        // Even high-confidence Soniox output can have broken Korean words
        
        // Step 0: MANDATORY - Check for broken Korean word patterns regardless of confidence
        val hasBrokenWordPatterns = detectBrokenWordPatterns(text)
        if (hasBrokenWordPatterns) {
            Log.d(TAG, "Broken word patterns detected in high-confidence text: $text")
            // Force processing even for high confidence when broken patterns are found
        }
        
        // REMOVED: High-confidence bypass - this was the root cause of the bug
        // Now all text goes through proper Korean validation and reconstruction
        
        // Step 1: Apply phonological rules (enhanced for broken patterns)
        if (sourceConfidence < 0.98f || hasBrokenWordPatterns) {
            PHONOLOGICAL_RULES.forEach { (surface, underlying) ->
                // Check if the recognized text contains the surface form
                // (what was actually pronounced) and might need the underlying form
                // (what should be written)
                if (correctedText.contains(surface)) {
                    correctedText = correctedText.replace(surface, underlying)
                    corrections.add(Correction(surface, underlying, "phonological_rule"))
                    confidenceBoost += 0.08f // Higher boost for phonological corrections
                    Log.d(TAG, "Applied phonological rule: '$surface' -> '$underlying'")
                }
            }
        }
        
        // Step 2: ENHANCED - Apply direct word corrections with broken word patterns
        // Always apply critical Korean word corrections regardless of confidence
        COMMON_CORRECTIONS.forEach { (incorrect, correct) ->
            if (correctedText.contains(incorrect, ignoreCase = true)) {
                val regex = Regex("\\b${Regex.escape(incorrect)}\\b", RegexOption.IGNORE_CASE)
                correctedText = correctedText.replace(regex, correct)
                corrections.add(Correction(incorrect, correct, "common_correction"))
                confidenceBoost += 0.05f // Boost confidence for each correction
                Log.d(TAG, "Corrected: '$incorrect' -> '$correct'")
            }
        }
        
        // NEW: Apply critical broken word pattern fixes
        correctedText = fixBrokenWordPatterns(correctedText, corrections)
        
        // Step 3: Apply phonetic pattern corrections (always for critical spacing issues)
        PHONETIC_PATTERNS.forEach { (pattern, replacement) ->
            val originalText = correctedText
            correctedText = pattern.replace(correctedText, replacement)
            if (originalText != correctedText) {
                corrections.add(Correction(
                    pattern.pattern,
                    replacement,
                    "phonetic_pattern"
                ))
                Log.d(TAG, "Applied phonetic pattern: ${pattern.pattern}")
            }
        }
        
        // Step 4: Apply context-aware corrections
        CONTEXT_CORRECTIONS.forEach { (pattern, replacement) ->
            val originalText = correctedText
            correctedText = pattern.replace(correctedText, replacement)
            if (originalText != correctedText) {
                corrections.add(Correction(
                    pattern.pattern,
                    replacement,
                    "context_correction"
                ))
                confidenceBoost += 0.1f // Higher boost for context corrections
                Log.d(TAG, "Applied context correction: ${pattern.pattern}")
            }
        }
        
        // Step 5: Apply particle corrections
        correctedText = applyParticleCorrections(correctedText)
        
        // Step 6: Clean up formatting
        correctedText = cleanupFormatting(correctedText)
        
        // Calculate confidence adjustment (enhanced logic)
        val wasChanged = text != correctedText
        val confidence = if (wasChanged) {
            // If we made corrections, adjust confidence based on source and corrections
            if (sourceConfidence >= 0.9f && hasBrokenWordPatterns) {
                // High source confidence but had broken patterns - moderate confidence
                minOf(1.0f, 0.88f + confidenceBoost)
            } else {
                // Normal correction confidence boost
                minOf(1.0f, 0.85f + confidenceBoost)
            }
        } else {
            // No corrections needed - keep source confidence
            sourceConfidence
        }
        
        return CorrectionResult(
            originalText = text,
            correctedText = correctedText,
            corrections = corrections,
            confidence = confidence,
            wasChanged = wasChanged
        )
    }
    
    /**
     * Apply Korean particle corrections
     */
    private fun applyParticleCorrections(text: String): String {
        var corrected = text
        
        // Subject particles 은/는, 이/가
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 은"), "$1학은") // consonant + 은
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 는"), "$1학는") // vowel + 는
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 이"), "$1학이") // consonant + 이
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 가"), "$1학가") // vowel + 가
        
        // Object particles 을/를
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 을"), "$1학을") // consonant + 을
        corrected = corrected.replace(Regex("([\u3131-\ud7a3])학 를"), "$1학를") // vowel + 를
        
        // Common particle attachments
        corrected = corrected.replace("도 에", "도에") // also at/to
        corrected = corrected.replace("부 터", "부터") // from
        corrected = corrected.replace("까 지", "까지") // until
        corrected = corrected.replace("만 큼", "만큼") // as much as
        
        return corrected
    }
    
    /**
     * Clean up text formatting - MINIMAL for Soniox
     */
    private fun cleanupFormatting(text: String): String {
        return text
            .replace(Regex("\\s{3,}"), " ") // Only fix 3+ spaces
            .trim() // Remove leading/trailing spaces
            // DO NOT add spaces between Korean and numbers - trust Soniox
    }
    
    /**
     * Check if text likely contains errors based on patterns
     */
    fun detectLikelyErrors(text: String): List<String> {
        val likelyErrors = mutableListOf<String>()
        
        // Check for incomplete words
        if (text.matches(Regex(".*안녕\\s*$"))) {
            likelyErrors.add("Incomplete greeting detected")
        }
        
        // Check for spacing issues
        if (text.contains(Regex("\\s{2,}"))) {
            likelyErrors.add("Multiple spaces detected")
        }
        
        // Check for known problematic patterns
        COMMON_CORRECTIONS.keys.forEach { incorrectPattern ->
            if (text.contains(incorrectPattern)) {
                likelyErrors.add("Common misrecognition pattern: $incorrectPattern")
            }
        }
        
        return likelyErrors
    }
    
    /**
     * Generate alternative suggestions for ambiguous text
     */
    fun generateAlternatives(text: String): List<String> {
        val alternatives = mutableSetOf<String>()
        
        // Generate alternatives based on common confusions
        val confusionPairs = mapOf(
            "네" to listOf("내", "예"),
            "내" to listOf("네", "예"),
            "아니요" to listOf("아니오", "아뇨"),
            "어떻게" to listOf("어떡해", "어떻해"),
            "뭐예요" to listOf("뭐에요", "뭐야")
        )
        
        confusionPairs.forEach { (word, alternativeWords) ->
            if (text.contains(word)) {
                alternativeWords.forEach { alt ->
                    alternatives.add(text.replace(word, alt))
                }
            }
        }
        
        // Also add the original if we have alternatives
        if (alternatives.isNotEmpty()) {
            alternatives.add(text)
        }
        
        return alternatives.toList().take(3) // Return top 3 alternatives
    }
    
    /**
     * NEW: Detect if text contains broken Korean word patterns that need reconstruction
     * This is critical for identifying cases where Soniox has high confidence but words are broken
     */
    private fun detectBrokenWordPatterns(text: String): Boolean {
        // Known broken patterns from the problem analysis
        val brokenPatterns = listOf(
            // Broken versions of "괜찮아요" (it's okay)
            Regex("괜\\s*아\\s*찮\\s*요"),
            Regex("괜\\s+아"),
            Regex("찮\\s+요"),
            Regex("괜아\\s+찮요"),
            
            // Broken versions of "나가기 귀찮아요" (too lazy to go out)
            Regex("나\\s*기\\s*귀찮아\\s*요"),
            Regex("나기\\s*귀찮아\\s*요"),
            Regex("나기귀찮아\\s+요"),
            
            // Broken greetings
            Regex("안\\s*녕\\s*하세요"),
            Regex("안\\s+녕"),
            Regex("녕\\s+하세요"),
            
            // Broken thanks/sorry
            Regex("감\\s*사\\s*합니다"),
            Regex("죄\\s*송\\s*합니다"),
            Regex("감사\\s+합니다"),
            Regex("죄송\\s+합니다"),
            
            // Common courtesy expressions
            Regex("어서\\s+오세요"),
            Regex("수고\\s+하세요"),
            Regex("어\\s+서오세요"),
            Regex("수\\s+고하세요"),
            
            // Spacing issues with verb endings
            Regex("\\s+습니다"),
            Regex("\\s+세요"),
            Regex("\\s+어요"),
            Regex("\\s+아요"),
            Regex("\\s+지요"),
            
            // Single Korean syllables separated by spaces (very common issue)
            Regex("[가-힣]\\s+[가-힣]\\s+[가-힣]"), // 3+ syllables with spaces
            
            // Incomplete word fragments
            Regex("\\s*개\\s+"), // 개 as fragment
            Regex("\\s*녀\\s+"), // 녀 as fragment  
            Regex("\\s*기\\s+"), // 기 as fragment
            Regex("\\s*시\\s+")  // 시 as fragment
        )
        
        // Check if any broken patterns are present
        for (pattern in brokenPatterns) {
            if (pattern.containsMatchIn(text)) {
                Log.d(TAG, "Broken word pattern detected: ${pattern.pattern} in text: $text")
                return true
            }
        }
        
        // Also check for excessive spacing in Korean text
        val koreanWithExcessiveSpaces = Regex("[가-힣]\\s{2,}[가-힣]")
        if (koreanWithExcessiveSpaces.containsMatchIn(text)) {
            Log.d(TAG, "Excessive spacing in Korean text detected: $text")
            return true
        }
        
        return false
    }
    
    /**
     * NEW: Apply fixes for known broken Korean word patterns
     * This function specifically addresses the word segmentation issues
     */
    private fun fixBrokenWordPatterns(text: String, corrections: MutableList<Correction>): String {
        var fixed = text
        
        // Map of broken patterns to correct forms
        val brokenWordFixes = mapOf(
            // Fix "괜찮아요" variations
            Regex("괜\\s*아\\s*찮\\s*요") to "괜찮아요",
            Regex("괜\\s+아\\s*찮\\s*요") to "괜찮아요",
            Regex("괜아\\s+찮\\s*요") to "괜찮아요",
            Regex("괜\\s+아") to "괜아", // Partial fix
            Regex("찮\\s+요") to "찮아요", // Complete the word
            
            // Fix "나가기 귀찮아요" variations
            Regex("나\\s*기\\s*귀찮아\\s*요") to "나가기 귀찮아요",
            Regex("나기\\s*귀찮아\\s*요") to "나가기 귀찮아요",
            Regex("나기귀찮아\\s+요") to "나가기 귀찮아요",
            
            // Fix greetings
            Regex("안\\s*녕\\s*하세요") to "안녕하세요",
            Regex("안\\s+녕\\s*하세요") to "안녕하세요",
            Regex("안녕\\s+하세요") to "안녕하세요",
            
            // Fix thanks and apologies
            Regex("감\\s*사\\s*합니다") to "감사합니다",
            Regex("감사\\s+합니다") to "감사합니다",
            Regex("죄\\s*송\\s*합니다") to "죄송합니다",
            Regex("죄송\\s+합니다") to "죄송합니다",
            
            // Fix courtesy expressions
            Regex("어서\\s+오세요") to "어서오세요",
            Regex("어\\s+서오세요") to "어서오세요",
            Regex("수고\\s+하세요") to "수고하세요",
            Regex("수\\s+고하세요") to "수고하세요",
            
            // Fix verb ending spacing
            Regex("([\uac00-힣]+)\\s+(습니다)") to "$1$2",
            Regex("([\uac00-힣]+)\\s+(세요)") to "$1$2",
            Regex("([\uac00-힣]+)\\s+(어요)") to "$1$2",
            Regex("([\uac00-힣]+)\\s+(아요)") to "$1$2",
            Regex("([\uac00-힣]+)\\s+(지요)") to "$1$2",
            
            // Fix single syllable spacing (most aggressive fix)
            Regex("([가-힣])\\s+([가-힣])\\s+([가-힣])") to "$1$2$3"
        )
        
        // Apply all fixes
        brokenWordFixes.forEach { (pattern, replacement) ->
            val originalText = fixed
            fixed = pattern.replace(fixed, replacement)
            if (originalText != fixed) {
                corrections.add(Correction(
                    pattern.pattern,
                    replacement,
                    "broken_word_fix"
                ))
                Log.d(TAG, "Fixed broken word pattern: '${pattern.pattern}' -> '$replacement'")
            }
        }
        
        return fixed
    }
    
    data class CorrectionResult(
        val originalText: String,
        val correctedText: String,
        val corrections: List<Correction>,
        val confidence: Float,
        val wasChanged: Boolean
    )
    
    data class Correction(
        val from: String,
        val to: String,
        val type: String // common_correction, phonetic_pattern, context_correction
    )
}