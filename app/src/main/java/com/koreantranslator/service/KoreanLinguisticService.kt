package com.koreantranslator.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for analyzing Korean linguistic patterns and improving translation accuracy
 * by identifying sentence-ending particles, idiomatic expressions, and contextual markers
 */
@Singleton
class KoreanLinguisticService @Inject constructor() {
    
    companion object {
        private const val TAG = "KoreanLinguistic"
        
        // Sentence-ending particles and their semantic functions
        private val SENTENCE_ENDINGS = mapOf(
            "잖아요" to SentenceEndingInfo(
                meaning = "reminder/assertion",
                translation_hint = "as you know, I told you, didn't I",
                tone = "assertive/reminding"
            ),
            "잖아" to SentenceEndingInfo(
                meaning = "reminder/assertion (informal)",
                translation_hint = "I told you, you know",
                tone = "assertive/casual"
            ),
            "네요" to SentenceEndingInfo(
                meaning = "mild surprise/realization",
                translation_hint = "I see, oh",
                tone = "surprised/acknowledging"
            ),
            "거든요" to SentenceEndingInfo(
                meaning = "providing explanation",
                translation_hint = "you see, because",
                tone = "explanatory"
            ),
            "거든" to SentenceEndingInfo(
                meaning = "providing explanation (informal)",
                translation_hint = "you see, because",
                tone = "explanatory/casual"
            ),
            "군요" to SentenceEndingInfo(
                meaning = "new realization",
                translation_hint = "I see, ah",
                tone = "realizing"
            ),
            "구나" to SentenceEndingInfo(
                meaning = "new realization (informal)",
                translation_hint = "I see, ah",
                tone = "realizing/casual"
            ),
            "더라고요" to SentenceEndingInfo(
                meaning = "reporting past observation",
                translation_hint = "I noticed that, I found that",
                tone = "reporting"
            ),
            "더라고" to SentenceEndingInfo(
                meaning = "reporting past observation (informal)",
                translation_hint = "I noticed, I found",
                tone = "reporting/casual"
            ),
            "나요" to SentenceEndingInfo(
                meaning = "soft question",
                translation_hint = "is it?, do you?",
                tone = "questioning/polite"
            ),
            "가요" to SentenceEndingInfo(
                meaning = "soft question",
                translation_hint = "is it?, does it?",
                tone = "questioning/polite"
            ),
            "죠" to SentenceEndingInfo(
                meaning = "seeking confirmation",
                translation_hint = "right?, isn't it?",
                tone = "confirming"
            ),
            "지요" to SentenceEndingInfo(
                meaning = "seeking confirmation (formal)",
                translation_hint = "right?, isn't it?",
                tone = "confirming/formal"
            ),
            "는데요" to SentenceEndingInfo(
                meaning = "soft contrast/background",
                translation_hint = "but, though",
                tone = "contrasting/soft"
            ),
            "ㄹ까요" to SentenceEndingInfo(
                meaning = "suggestion/wondering",
                translation_hint = "shall we?, I wonder",
                tone = "suggesting/wondering"
            ),
            "을까요" to SentenceEndingInfo(
                meaning = "suggestion/wondering",
                translation_hint = "shall we?, I wonder",
                tone = "suggesting/wondering"
            )
        )
        
        // Idiomatic expressions that need special handling
        private val IDIOMATIC_EXPRESSIONS = mapOf(
            // Original expressions
            "제가 그랬잖아요" to "I told you",
            "내가 그랬잖아" to "I told you",
            "그럴 수도 있죠" to "That could be",
            "그럴 수도 있지" to "That could be",
            "아니거든요" to "Actually, no",
            "아니거든" to "Actually, no",
            "그런 거 같아요" to "I think so",
            "그런 것 같아요" to "I think so",
            "어떻게 해요" to "What should I do",
            "어떡해" to "What should I do",
            "괜찮아요" to "It's okay",
            "괜찮아" to "It's okay",
            "알겠어요" to "I understand",
            "알겠습니다" to "I understand",
            "모르겠어요" to "I don't know",
            "모르겠습니다" to "I don't know",
            "맞아요" to "That's right",
            "맞습니다" to "That's correct",
            "그래요" to "I see",
            "그렇습니다" to "That's right",
            "저기요" to "Excuse me",
            "실례합니다" to "Excuse me",
            "죄송합니다" to "I'm sorry",
            "미안해요" to "I'm sorry",
            "고맙습니다" to "Thank you",
            "감사합니다" to "Thank you",
            "안녕하세요" to "Hello",
            "안녕히 가세요" to "Goodbye (to person leaving)",
            "안녕히 계세요" to "Goodbye (to person staying)",
            "잘 먹겠습니다" to "Thank you for the meal (before eating)",
            "잘 먹었습니다" to "Thank you for the meal (after eating)",
            "수고하세요" to "Keep up the good work",
            "수고하셨습니다" to "Thank you for your hard work",
            
            // Extended common expressions
            "뭐해요" to "What are you doing",
            "뭐해" to "What are you doing",
            "뭐하세요" to "What are you doing (formal)",
            "진짜요?" to "Really?",
            "정말요?" to "Really?",
            "진짜?" to "Really?",
            "정말?" to "Really?",
            "대박" to "Awesome",
            "헐" to "Oh my",
            "아싸" to "Yes!",
            "화이팅" to "Fighting!",
            "파이팅" to "Fighting!",
            "아이고" to "Oh my",
            "어머" to "Oh my",
            "어머나" to "Oh my goodness",
            "설마" to "No way",
            "역시" to "As expected",
            "그러니까요" to "I know, right",
            "그러니까" to "That's why",
            "그러게요" to "I know",
            "그러게" to "I told you so",
            "아무튼" to "Anyway",
            "어쨌든" to "Anyway",
            "하여튼" to "Anyway",
            
            // Expressions with context
            "별로예요" to "Not really",
            "별로" to "Not really",
            "그냥요" to "Just because",
            "그냥" to "Just",
            "그저 그래요" to "So-so",
            "그저 그래" to "So-so",
            "뭐 어때요" to "So what",
            "뭐 어때" to "So what",
            "상관없어요" to "I don't care",
            "상관없어" to "I don't care",
            "신경 안 써요" to "I don't mind",
            "신경 안 써" to "I don't mind",
            
            // Surprise and realization
            "말도 안 돼" to "No way",
            "말도 안 돼요" to "That's impossible",
            "믿을 수 없어요" to "I can't believe it",
            "믿을 수 없어" to "I can't believe it",
            "놀랍네요" to "That's surprising",
            "신기하네요" to "That's amazing",
            "신기해요" to "That's interesting",
            
            // Agreement and disagreement
            "당연하죠" to "Of course",
            "당연해요" to "Of course",
            "물론이죠" to "Of course",
            "물론이에요" to "Of course",
            "아닌데요" to "That's not right",
            "아닌데" to "That's not it",
            "그게 아니라" to "It's not that, but",
            "그게 아니고" to "It's not that, but"
        )
        
        // Internet slang and abbreviated expressions
        private val INTERNET_SLANG = mapOf(
            "ㅋㅋㅋ" to "lol",
            "ㅋㅋ" to "haha",
            "ㅎㅎ" to "hehe",
            "ㅎㅎㅎ" to "hehe",
            "ㅠㅠ" to "*crying*",
            "ㅜㅜ" to "*crying*",
            "ㅠ.ㅠ" to "*tears*",
            "ㅜ.ㅜ" to "*tears*",
            "ㅇㅇ" to "yeah/okay",
            "ㄴㄴ" to "no no",
            "ㄱㅅ" to "thanks",
            "ㅈㅅ" to "sorry",
            "ㅊㅋ" to "congrats",
            "ㅅㄱ" to "good work",
            "ㄱㄱ" to "let's go",
            "ㅂㅂ" to "bye bye",
            "ㄷㄷ" to "*shudder*",
            "ㅁㄹ" to "don't know",
            "ㅇㅋ" to "okay",
            "ㄱㅊ" to "it's fine",
            "ㅇㅈ" to "I admit",
            "ㄹㅇ" to "for real",
            "ㅆㅂ" to "*curse*",
            "ㅅㅂ" to "*curse*",
            "존맛" to "so delicious",
            "존잘" to "so handsome/pretty",
            "레알" to "for real",
            "ㄹㅇㅋㅋ" to "lol for real",
            "개웃김" to "so funny",
            "개좋아" to "love it",
            "쩐다" to "awesome",
            "쩔어" to "it's lit",
            "오지다" to "amazing",
            "오진다" to "it's amazing"
        )
        
        // Contextual verb patterns that affect meaning
        private val VERB_PATTERNS = mapOf(
            Regex("그[렇랬]([다니까잖])") to VerbPatternInfo(
                base_meaning = "to be so/to do so",
                context_modifier = "Past action or state being referenced"
            ),
            Regex("있([다니까])") to VerbPatternInfo(
                base_meaning = "to exist/to have",
                context_modifier = "State of existence or possession"
            ),
            Regex("없([다니까])") to VerbPatternInfo(
                base_meaning = "to not exist/to not have",
                context_modifier = "State of non-existence or lack"
            ),
            Regex("하([다니까])") to VerbPatternInfo(
                base_meaning = "to do",
                context_modifier = "Action being performed"
            )
        )
    }
    
    data class SentenceEndingInfo(
        val meaning: String,
        val translation_hint: String,
        val tone: String
    )
    
    data class VerbPatternInfo(
        val base_meaning: String,
        val context_modifier: String
    )
    
    data class LinguisticAnalysis(
        val originalText: String,
        val sentenceEnding: SentenceEndingInfo? = null,
        val idiomaticExpression: String? = null,
        val verbPattern: VerbPatternInfo? = null,
        val suggestedTranslation: String? = null,
        val translationHints: List<String> = emptyList(),
        val confidence: Float = 0.0f
    )
    
    /**
     * Analyze Korean text for linguistic patterns that affect translation
     */
    fun analyzeText(koreanText: String): LinguisticAnalysis {
        val text = koreanText.trim()
        val hints = mutableListOf<String>()
        var confidence = 0.5f
        
        // Check for internet slang first (highest priority for modern communication)
        val slangMatch = INTERNET_SLANG[text]
        if (slangMatch != null) {
            Log.d(TAG, "Found internet slang: $text -> $slangMatch")
            return LinguisticAnalysis(
                originalText = text,
                idiomaticExpression = slangMatch,
                suggestedTranslation = slangMatch,
                translationHints = listOf("Internet slang/abbreviation detected"),
                confidence = 0.98f
            )
        }
        
        // Check for exact idiomatic expression match
        val idiomaticMatch = IDIOMATIC_EXPRESSIONS[text]
        if (idiomaticMatch != null) {
            Log.d(TAG, "Found exact idiomatic match: $text -> $idiomaticMatch")
            return LinguisticAnalysis(
                originalText = text,
                idiomaticExpression = idiomaticMatch,
                suggestedTranslation = idiomaticMatch,
                confidence = 0.95f
            )
        }
        
        // Check for sentence-ending particles
        var sentenceEnding: SentenceEndingInfo? = null
        for ((ending, info) in SENTENCE_ENDINGS) {
            if (text.endsWith(ending)) {
                sentenceEnding = info
                hints.add("Sentence ending '$ending' indicates: ${info.meaning}")
                hints.add("Translation should convey: ${info.translation_hint}")
                
                // CRITICAL FIX: Sentence particles should have high confidence for proper context handling
                // These fundamentally change meaning and should override base translations
                confidence = if (ending in listOf("잖아요", "잖아", "거든요", "거든")) {
                    0.92f  // High confidence for meaning-critical particles
                } else {
                    confidence + 0.3f  // Increased boost for other particles
                }
                
                Log.d(TAG, "Detected sentence ending: $ending with confidence: $confidence")
                break
            }
        }
        
        // Check for verb patterns
        var verbPattern: VerbPatternInfo? = null
        for ((pattern, info) in VERB_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                verbPattern = info
                hints.add("Verb pattern detected: ${info.base_meaning}")
                confidence += 0.1f
                Log.d(TAG, "Detected verb pattern: ${pattern.pattern}")
                break
            }
        }
        
        // Special case: Check for "그렇다" + "잖아요" pattern
        if (text.contains("그랬") && text.endsWith("잖아요")) {
            hints.add("CRITICAL: '그랬잖아요' pattern - means 'I/you did that (as you know)' NOT negation")
            confidence = 0.9f
            
            // Provide specific translation based on subject
            val suggestedTranslation = when {
                text.startsWith("제가") || text.startsWith("내가") -> "I told you"
                text.startsWith("네가") || text.startsWith("니가") || text.startsWith("당신이") -> "You told me"
                text.startsWith("그가") || text.startsWith("그녀가") -> "He/She told us"
                else -> "I/You/They said that"
            }
            
            return LinguisticAnalysis(
                originalText = text,
                sentenceEnding = sentenceEnding,
                verbPattern = verbPattern,
                suggestedTranslation = suggestedTranslation,
                translationHints = hints,
                confidence = confidence
            )
        }
        
        // Check for partial idiomatic expressions
        for ((expression, translation) in IDIOMATIC_EXPRESSIONS) {
            if (text.contains(expression.take(expression.length / 2))) {
                hints.add("Partial match with idiomatic expression: $expression -> $translation")
                confidence += 0.05f
            }
        }
        
        // ENHANCED: Provide suggested translations for critical sentence particles
        var suggestedTranslation: String? = null
        if (sentenceEnding != null && confidence >= 0.9f) {
            // For high-confidence sentence particles, provide contextual translation hints
            suggestedTranslation = when {
                // Critical particles that fundamentally change meaning
                text.endsWith("잖아요") || text.endsWith("잖아") -> {
                    when {
                        text.startsWith("제가") || text.startsWith("내가") -> "I told you"
                        text.startsWith("네가") || text.startsWith("니가") -> "You said that"
                        text.contains("그랬") -> "I/You/They said that (as you know)"
                        else -> null // Let ML Kit handle with hint
                    }
                }
                text.endsWith("거든요") || text.endsWith("거든") -> {
                    when {
                        text.contains("바쁘") -> "I'm busy (you see)"
                        text.contains("아니") -> "Actually, no"
                        text.contains("좋") -> "It's good (you see)"
                        else -> null // Let ML Kit handle with hint
                    }
                }
                else -> null
            }
            
            if (suggestedTranslation != null) {
                Log.d(TAG, "Providing suggested translation for critical particle: $suggestedTranslation")
            }
        }
        
        return LinguisticAnalysis(
            originalText = text,
            sentenceEnding = sentenceEnding,
            verbPattern = verbPattern,
            suggestedTranslation = suggestedTranslation,
            translationHints = hints,
            confidence = minOf(confidence, 1.0f)
        )
    }
    
    /**
     * Pre-process Korean text to add linguistic markers for better translation
     */
    fun preprocessForTranslation(koreanText: String): String {
        val analysis = analyzeText(koreanText)
        
        // If we have high confidence in a specific translation, return it with markers
        if (analysis.suggestedTranslation != null && analysis.confidence > 0.8f) {
            Log.d(TAG, "High confidence preprocessing: ${analysis.suggestedTranslation}")
            return koreanText // Return original, the translation service will use the analysis
        }
        
        // Add linguistic markers as comments (these will be used by the translator)
        val markers = mutableListOf<String>()
        
        analysis.sentenceEnding?.let {
            markers.add("[ENDING: ${it.meaning}]")
        }
        
        analysis.verbPattern?.let {
            markers.add("[VERB: ${it.base_meaning}]")
        }
        
        if (markers.isNotEmpty()) {
            Log.d(TAG, "Added linguistic markers: ${markers.joinToString()}")
        }
        
        return koreanText
    }
    
    /**
     * Post-process translation to ensure linguistic patterns were properly handled
     */
    fun postProcessTranslation(
        originalKorean: String,
        translation: String,
        analysis: LinguisticAnalysis? = null
    ): String {
        val linguisticAnalysis = analysis ?: analyzeText(originalKorean)
        
        // If we have a high-confidence suggested translation and the current translation
        // seems wrong, use our suggestion
        if (linguisticAnalysis.suggestedTranslation != null && 
            linguisticAnalysis.confidence > 0.85f) {
            
            // Check if the translation seems incorrect
            val isLikelyWrong = when {
                originalKorean.contains("그랬잖아요") && translation.contains("did not", ignoreCase = true) -> true
                originalKorean.contains("그랬잖아요") && translation.contains("didn't", ignoreCase = true) -> true
                originalKorean.endsWith("잖아요") && !translation.contains(Regex("(know|told|said|remember)", RegexOption.IGNORE_CASE)) -> true
                else -> false
            }
            
            if (isLikelyWrong) {
                Log.d(TAG, "Correcting likely mistranslation: $translation -> ${linguisticAnalysis.suggestedTranslation}")
                return linguisticAnalysis.suggestedTranslation
            }
        }
        
        // Apply sentence ending tone adjustments if needed
        linguisticAnalysis.sentenceEnding?.let { ending ->
            when (ending.tone) {
                "assertive/reminding" -> {
                    // Ensure the translation conveys reminder/assertion
                    if (!translation.contains(Regex("(told|said|know|remember)", RegexOption.IGNORE_CASE))) {
                        Log.d(TAG, "Adding assertive tone marker")
                        // Don't modify, let Gemini handle it with the improved prompt
                    }
                }
                "questioning/polite" -> {
                    // Ensure questions end with question marks
                    if (!translation.endsWith("?") && ending.meaning.contains("question")) {
                        return "$translation?"
                    }
                }
            }
        }
        
        return translation
    }
    
    /**
     * Get translation hints for a given Korean text
     */
    fun getTranslationHints(koreanText: String): List<String> {
        val analysis = analyzeText(koreanText)
        return analysis.translationHints
    }
}