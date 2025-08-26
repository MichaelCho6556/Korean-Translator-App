package com.koreantranslator.nlp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and manages the Korean dictionary for NLP processing
 * 
 * Features:
 * - Loads vocabulary from bundled assets
 * - Supports frequency-based word ranking
 * - Handles compound words and phrases
 * - Memory-efficient loading
 */
@Singleton
class KoreanDictionaryLoader @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "KoreanDictionaryLoader"
        
        // Asset file paths
        private const val DICTIONARY_FILE = "korean_dictionary.txt"
        private const val COMPOUNDS_FILE = "korean_compounds.txt"
        private const val FREQUENCY_FILE = "korean_frequency.txt"
        
        // Built-in comprehensive Korean vocabulary
        // This would normally be loaded from a file, but we'll embed it for immediate functionality
        val KOREAN_VOCABULARY = setOf(
            // Basic pronouns and determiners
            "나", "너", "우리", "저", "저희", "당신", "그", "그녀", "이", "그", "저",
            "이것", "그것", "저것", "여기", "거기", "저기", "이곳", "그곳", "저곳",
            "누구", "무엇", "어디", "언제", "왜", "어떻게", "얼마나", "몇", "어느",
            
            // Family and people
            "사람", "사람들", "가족", "부모", "부모님", "아버지", "아빠", "어머니", "엄마",
            "형제", "자매", "형", "누나", "언니", "오빠", "동생", "남동생", "여동생",
            "할아버지", "할머니", "삼촌", "이모", "고모", "아들", "딸", "아이", "아이들",
            "친구", "친구들", "선생님", "학생", "의사", "간호사", "경찰", "군인",
            
            // Body parts
            "몸", "머리", "얼굴", "눈", "코", "입", "귀", "목", "어깨", "팔", "손",
            "손가락", "가슴", "배", "등", "허리", "다리", "무릎", "발", "발가락",
            
            // Time expressions
            "시간", "시", "분", "초", "오늘", "내일", "모레", "어제", "그저께",
            "지금", "이제", "아까", "나중", "먼저", "다음", "전", "후", "동안",
            "아침", "점심", "저녁", "밤", "새벽", "오전", "오후", "낮", "밤중",
            "주", "주말", "평일", "월", "화", "수", "목", "금", "토", "일",
            "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일",
            "일월", "이월", "삼월", "사월", "오월", "유월", "칠월", "팔월", "구월", "시월", "십일월", "십이월",
            "봄", "여름", "가을", "겨울", "년", "해", "달", "날", "날짜",
            
            // Places and locations
            "집", "학교", "회사", "사무실", "병원", "은행", "우체국", "경찰서", "소방서",
            "마트", "시장", "백화점", "가게", "식당", "카페", "술집", "호텔", "모텔",
            "공원", "놀이터", "운동장", "체육관", "수영장", "도서관", "박물관", "미술관",
            "극장", "영화관", "교회", "성당", "절", "사찰", "공항", "역", "터미널",
            "버스정류장", "지하철역", "항구", "나라", "도시", "마을", "동네", "거리",
            "길", "도로", "고속도로", "다리", "터널", "산", "바다", "강", "호수", "섬",
            
            // Transportation
            "차", "자동차", "버스", "택시", "지하철", "기차", "비행기", "배", "자전거",
            "오토바이", "트럭", "승용차", "승합차", "전철", "선박", "요트", "헬리콥터",
            
            // Food and drinks
            "음식", "밥", "국", "찌개", "반찬", "김치", "된장", "고추장", "간장",
            "빵", "과자", "케이크", "아이스크림", "초콜릿", "사탕", "과일", "사과",
            "배", "포도", "딸기", "수박", "참외", "메론", "바나나", "오렌지", "귤",
            "야채", "채소", "배추", "무", "당근", "양파", "마늘", "파", "고추", "오이",
            "토마토", "감자", "고구마", "호박", "가지", "버섯", "콩", "팥",
            "고기", "소고기", "돼지고기", "닭고기", "양고기", "오리고기", "생선",
            "물", "커피", "차", "녹차", "홍차", "우유", "주스", "콜라", "사이다",
            "맥주", "소주", "막걸리", "와인", "위스키", "음료", "음료수",
            
            // Common verbs (stems and full forms)
            "하다", "하", "되다", "되", "있다", "있", "없다", "없", "가다", "가",
            "오다", "오", "보다", "보", "주다", "주", "받다", "받", "먹다", "먹",
            "마시다", "마시", "자다", "자", "일어나다", "일어나", "앉다", "앉",
            "서다", "서", "눕다", "눕", "걷다", "걷", "뛰다", "뛰", "달리다", "달리",
            "말하다", "말하", "이야기하다", "듣다", "듣", "읽다", "읽", "쓰다", "쓰",
            "그리다", "그리", "노래하다", "춤추다", "놀다", "놀", "일하다", "일하",
            "공부하다", "공부하", "배우다", "배우", "가르치다", "가르치", "만나다", "만나",
            "만들다", "만들", "사다", "사", "팔다", "팔", "열다", "열", "닫다", "닫",
            "시작하다", "시작하", "끝나다", "끝나", "멈추다", "정지하다", "계속하다",
            "기다리다", "기다리", "찾다", "찾", "잃다", "잃어버리다", "알다", "알",
            "모르다", "모르", "생각하다", "생각하", "느끼다", "느끼", "믿다", "믿",
            "사랑하다", "사랑하", "좋아하다", "좋아하", "싫어하다", "싫어하",
            "웃다", "웃", "울다", "울", "화내다", "기뻐하다", "슬퍼하다",
            
            // Common adjectives
            "좋다", "좋", "나쁘다", "나쁘", "크다", "크", "작다", "작", "많다", "많",
            "적다", "적", "높다", "높", "낮다", "낮", "길다", "길", "짧다", "짧",
            "넓다", "넓", "좁다", "좁", "두껍다", "두껍", "얇다", "얇", "무겁다", "무겁",
            "가볍다", "가볍", "빠르다", "빠르", "느리다", "느리", "뜨겁다", "뜨겁",
            "차갑다", "차갑", "따뜻하다", "따뜻하", "시원하다", "시원하", "덥다", "덥",
            "춥다", "춥", "어렵다", "어렵", "쉽다", "쉽", "복잡하다", "복잡하",
            "간단하다", "간단하", "재미있다", "재미있", "재미없다", "재미없",
            "예쁘다", "예쁘", "못생기다", "못생기", "잘생기다", "잘생기",
            "아름답다", "아름답", "귀엽다", "귀엽", "멋있다", "멋있", "멋지다", "멋지",
            "새롭다", "새롭", "오래되다", "오래되", "젊다", "젊", "늙다", "늙",
            "건강하다", "건강하", "아프다", "아프", "피곤하다", "피곤하",
            "배고프다", "배고프", "목마르다", "목마르", "배부르다", "배부르",
            
            // Numbers
            "영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구", "십",
            "백", "천", "만", "억", "조", "하나", "둘", "셋", "넷", "다섯",
            "여섯", "일곱", "여덟", "아홉", "열", "스물", "서른", "마흔", "쉰",
            "예순", "일흔", "여든", "아흔", "첫", "둘째", "셋째", "넷째", "다섯째",
            
            // Colors
            "색", "색깔", "빨간색", "빨강", "파란색", "파랑", "노란색", "노랑",
            "초록색", "초록", "검은색", "검정", "하얀색", "하양", "회색", "분홍색",
            "주황색", "보라색", "갈색", "남색", "연두색", "하늘색",
            
            // Weather and nature
            "날씨", "비", "눈", "바람", "구름", "하늘", "태양", "해", "달", "별",
            "무지개", "번개", "천둥", "안개", "이슬", "서리", "얼음", "불", "물",
            "흙", "돌", "모래", "나무", "풀", "꽃", "잎", "가지", "뿌리", "씨앗",
            
            // Technology
            "컴퓨터", "노트북", "키보드", "마우스", "모니터", "프린터", "스캐너",
            "전화", "휴대폰", "스마트폰", "태블릿", "카메라", "텔레비전", "라디오",
            "인터넷", "와이파이", "블루투스", "이메일", "메시지", "앱", "프로그램",
            "소프트웨어", "하드웨어", "데이터", "파일", "폴더", "비밀번호",
            
            // Education
            "교육", "수업", "과목", "국어", "영어", "수학", "과학", "사회", "역사",
            "지리", "물리", "화학", "생물", "음악", "미술", "체육", "시험", "숙제",
            "문제", "답", "질문", "설명", "발표", "토론", "연구", "실험", "결과",
            
            // Business and work
            "일", "직업", "직장", "업무", "프로젝트", "회의", "발표", "보고서",
            "계획", "전략", "목표", "성과", "실적", "매출", "이익", "손실", "투자",
            "계약", "거래", "고객", "직원", "상사", "동료", "부하", "팀", "부서",
            
            // Emotions and feelings
            "감정", "기분", "행복", "슬픔", "기쁨", "분노", "두려움", "놀람",
            "사랑", "미움", "희망", "절망", "자신감", "불안", "스트레스", "평화",
            "만족", "불만", "감사", "미안", "죄송", "부끄러움", "자랑", "질투",
            
            // Common expressions and phrases
            "안녕", "안녕하세요", "안녕하십니까", "안녕히", "감사합니다", "고맙습니다",
            "죄송합니다", "미안합니다", "실례합니다", "잠시만요", "잠깐만요",
            "괜찮아요", "괜찮습니다", "알겠습니다", "알겠어요", "모르겠습니다",
            "모르겠어요", "네", "예", "아니요", "아니오", "맞아요", "맞습니다",
            "틀려요", "틀렸습니다", "그래요", "그렇습니다", "그래", "응", "어",
            
            // Conjunctions and connectives
            "그리고", "그러나", "하지만", "그래서", "그런데", "그러므로", "따라서",
            "왜냐하면", "때문에", "만약", "만일", "비록", "아무리", "그래도",
            "그런데도", "그렇지만", "또한", "또", "게다가", "뿐만", "아니라",
            
            // Additional common words
            "것", "곳", "때", "말", "생각", "마음", "소리", "모습", "이유", "방법",
            "정도", "경우", "문제", "일반", "전체", "부분", "처음", "마지막", "중간",
            "위", "아래", "앞", "뒤", "옆", "안", "밖", "속", "겉", "사이", "가운데"
        )
    }
    
    /**
     * Load the complete dictionary into a Trie
     */
    suspend fun loadDictionary(trie: KoreanTrie) = withContext(Dispatchers.IO) {
        var wordCount = 0
        
        // First, load the built-in vocabulary
        KOREAN_VOCABULARY.forEach { word ->
            trie.insert(word)
            wordCount++
        }
        
        // Try to load additional words from assets if available
        try {
            context.assets.open(DICTIONARY_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.forEachLine { line ->
                        val word = line.trim()
                        if (word.isNotEmpty() && !word.startsWith("#")) {
                            trie.insert(word)
                            wordCount++
                        }
                    }
                }
            }
            Log.i(TAG, "Loaded additional dictionary from assets")
        } catch (e: Exception) {
            Log.d(TAG, "No additional dictionary file found, using built-in vocabulary")
        }
        
        Log.i(TAG, "Dictionary loaded with $wordCount words")
    }
    
    /**
     * Load compound word patterns
     */
    suspend fun loadCompoundPatterns(): Set<String> = withContext(Dispatchers.IO) {
        val patterns = mutableSetOf<String>()
        
        // Built-in compound patterns
        patterns.addAll(setOf(
            // Time compounds
            "오늘밤", "어제밤", "내일밤", "그날밤", "매일밤",
            "오늘아침", "어제아침", "내일아침", "매일아침",
            "오늘저녁", "어제저녁", "내일저녁", "매일저녁",
            "이번주", "지난주", "다음주", "매주", "주말",
            "이번달", "지난달", "다음달", "매달", "매월",
            "올해", "작년", "내년", "매년", "매해",
            
            // Language compounds
            "한국어", "영어", "일본어", "중국어", "프랑스어", "독일어", "스페인어",
            "한국말", "영어말", "일본말", "중국말",
            
            // Nationality compounds
            "한국인", "미국인", "일본인", "중국인", "프랑스인", "독일인",
            "한국사람", "미국사람", "일본사람", "중국사람",
            
            // Place compounds
            "우리나라", "우리집", "우리학교", "우리회사",
            "학교생활", "회사생활", "가정생활", "일상생활",
            
            // Common compounds
            "휴대전화", "휴대폰", "스마트폰", "노트북컴퓨터",
            "자동차", "자전거", "오토바이", "비행기표", "기차표",
            "지하철역", "버스정류장", "버스터미널", "공항터미널",
            "백화점", "할인마트", "편의점", "커피숍", "피시방",
            "놀이공원", "놀이터", "운동장", "체육관", "수영장",
            "생일날", "생일파티", "결혼식", "졸업식", "입학식"
        ))
        
        // Try to load additional patterns from assets
        try {
            context.assets.open(COMPOUNDS_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.forEachLine { line ->
                        val pattern = line.trim()
                        if (pattern.isNotEmpty() && !pattern.startsWith("#")) {
                            patterns.add(pattern)
                        }
                    }
                }
            }
            Log.i(TAG, "Loaded additional compound patterns from assets")
        } catch (e: Exception) {
            Log.d(TAG, "No additional compounds file found, using built-in patterns")
        }
        
        Log.i(TAG, "Loaded ${patterns.size} compound patterns")
        return@withContext patterns
    }
    
    /**
     * Load word frequency data for better segmentation
     */
    suspend fun loadFrequencyData(): Map<String, Int> = withContext(Dispatchers.IO) {
        val frequencyMap = mutableMapOf<String, Int>()
        
        // Common words with high frequency (would be loaded from file)
        val highFrequencyWords = mapOf(
            "은" to 10000, "는" to 10000, "이" to 10000, "가" to 10000,
            "을" to 9000, "를" to 9000, "에" to 8000, "에서" to 7000,
            "의" to 8000, "와" to 6000, "과" to 6000, "도" to 7000,
            "하다" to 9000, "있다" to 9000, "되다" to 8000, "없다" to 7000,
            "그" to 8000, "이" to 8000, "저" to 7000, "것" to 8000,
            "나" to 7000, "우리" to 7000, "너" to 6000, "당신" to 5000
        )
        
        frequencyMap.putAll(highFrequencyWords)
        
        // Try to load from assets
        try {
            context.assets.open(FREQUENCY_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.trim().split("\t")
                        if (parts.size == 2 && !line.startsWith("#")) {
                            val word = parts[0]
                            val frequency = parts[1].toIntOrNull() ?: 1
                            frequencyMap[word] = frequency
                        }
                    }
                }
            }
            Log.i(TAG, "Loaded frequency data from assets")
        } catch (e: Exception) {
            Log.d(TAG, "No frequency file found, using default frequencies")
        }
        
        Log.i(TAG, "Loaded frequency data for ${frequencyMap.size} words")
        return@withContext frequencyMap
    }
}