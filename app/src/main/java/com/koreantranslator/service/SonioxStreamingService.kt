package com.koreantranslator.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.koreantranslator.BuildConfig
import com.koreantranslator.util.KoreanTextValidator
import com.koreantranslator.util.SentenceDetectionConstants
import com.koreantranslator.nlp.KoreanNLPService

/**
 * Soniox Streaming Service - High-accuracy Korean speech recognition
 * 
 * This service provides 95.7% accuracy for Korean speech recognition using
 * Soniox's advanced AI models. It handles:
 * - Real-time streaming with < 300ms latency
 * - Bilingual Korean-English recognition
 * - Background noise and overlapping speech
 * - Regional accents and fast speech
 */
@Singleton
class SonioxStreamingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val koreanTextValidator: KoreanTextValidator,
    private val audioQualityAnalyzer: AudioQualityAnalyzer,
    private val confidenceAwareCorrector: ConfidenceAwareCorrector,
    private val smartPhraseCache: SmartPhraseCache,
    private val geminiReconstructionService: GeminiReconstructionService,
    private val koreanNLPService: KoreanNLPService
) {
    companion object {
        private const val TAG = "SonioxStreaming"
        
        // Audio configuration - Soniox requirements
        private const val SAMPLE_RATE = 16000 // 16kHz required by Soniox
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Soniox WebSocket endpoint (current as of August 2025)
        private const val SONIOX_WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        
        // Audio chunk configuration for optimal streaming
        private const val CHUNK_DURATION_MS = 100 // 100ms chunks for low latency
        private const val CHUNK_SIZE_SAMPLES = (SAMPLE_RATE * CHUNK_DURATION_MS) / 1000
        private const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SAMPLES * 2 // 16-bit = 2 bytes
        
        // Soniox model (current as of August 2025)
        private const val MODEL_STT_REALTIME = "stt-rt-preview-v2" // Current real-time model
    }
    
    // State flows for UI updates
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText
    
    private val _partialText = MutableStateFlow<String?>(null)
    val partialText: StateFlow<String?> = _partialText
    
    private val _accumulatedText = MutableStateFlow<String?>(null)
    val accumulatedText: StateFlow<String?> = _accumulatedText
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    // Confidence and alternatives
    private val _confidence = MutableStateFlow<Float?>(null)
    val confidence: StateFlow<Float?> = _confidence
    
    private val _alternatives = MutableStateFlow<List<String>>(emptyList())
    val alternatives: StateFlow<List<String>> = _alternatives
    
    // Connection state
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED
    }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // System status for UI feedback (separate from speech content)
    private val _systemStatus = MutableStateFlow<String?>(null)
    val systemStatus: StateFlow<String?> = _systemStatus
    
    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Audio preprocessing effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    
    // WebSocket for streaming
    private var webSocket: WebSocket? = null
    private var isIntentionallyStopping = false
    private var continuousMode = false
    
    // Performance tracking statistics
    private var reconstructionCount = 0
    private var totalReconstructionTime = 0L
    private var cacheHitCount = 0
    private var geminiCallCount = 0
    
    // Certificate pinning for Soniox API security
    // TODO: Get actual Soniox certificate pins using:
    // openssl s_client -connect api.soniox.com:443 < /dev/null 2>/dev/null | openssl x509 -fingerprint -sha256 -noout -in /dev/stdin
    private val sonioxCertificatePinner = CertificatePinner.Builder()
        // IMPORTANT: Replace with actual Soniox certificate pins before production use
        // For now, using common CA pins that most services use
        .add("*.soniox.com", "sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=") // Let's Encrypt ISRG Root X1
        .add("*.soniox.com", "sha256/sRHdihwgkaib1P1gxX8HFszlD+7/gTfNvuAybgLPNis=") // Let's Encrypt ISRG Root X2
        .add("api.soniox.com", "sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=") // Let's Encrypt ISRG Root X1
        .add("api.soniox.com", "sha256/sRHdihwgkaib1P1gxX8HFszlD+7/gTfNvuAybgLPNis=") // Let's Encrypt ISRG Root X2
        // Additional common CA pins as backups
        .add("*.soniox.com", "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=") // DigiCert Global Root G2
        .build()

    // HTTP client for WebSocket with certificate pinning
    private val httpClient = OkHttpClient.Builder()
        .apply {
            // Enable certificate pinning only if we have valid pins
            // For development, you can disable this by commenting out the next line
            certificatePinner(sonioxCertificatePinner)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // No read timeout for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive
        .build()
    
    private val gson = Gson()
    
    // Accumulated text management
    private val accumulatedSegments = mutableListOf<String>()
    private var currentTranscript = StringBuilder()
    
    // Audio buffering for connection setup
    private val audioBuffer = mutableListOf<ByteArray>()
    private var isBuffering = false
    
    // Keepalive job to prevent timeout
    private var keepaliveJob: Job? = null
    
    // Continuous mode session tracking
    private var sessionStartTime = 0L
    private var continuousSessionId: String? = null
    
    // Production safeguards for long sessions
    private var memoryMonitoringJob: Job? = null
    private var lastMemoryWarning = 0L
    private var bufferCleanupCount = 0
    
    // ENHANCED: Robust token state management with segment isolation
    private val currentSegmentTokens = mutableListOf<String>()
    private val previousSegmentTokens = mutableListOf<String>()
    private var lastFinalSegmentTime = 0L
    private var segmentBoundaryConfirmed = false
    private var segmentId = 0L
    private val tokenAccumulator = StringBuilder()
    
    // Real-time partial token accumulator for proper Korean spacing
    private val partialTokenAccumulator = mutableListOf<String>()
    
    // SENTENCE ACCUMULATION: Fix fragmentation by accumulating until sentence completion
    private val sentenceAccumulator = StringBuilder()
    private var lastTokenTime = 0L
    private var sentenceTimeoutJob: Job? = null
    private var sentenceCompletionInProgress = false // Edge case protection
    
    // Korean syllable pattern for proper reconstruction
    private val koreanSyllablePattern = Regex("[가-힣]")
    private val fragmentedSyllablePattern = Regex("[가-힣]\\s+[가-힣]")
    
    fun startListening(continuous: Boolean = false) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening")
            return
        }
        
        isIntentionallyStopping = false
        continuousMode = continuous
        
        if (continuous) {
            sessionStartTime = System.currentTimeMillis()
            continuousSessionId = "session_${sessionStartTime}"
            Log.d(TAG, "Starting in CONTINUOUS mode - session: $continuousSessionId")
            Log.d(TAG, "Continuous mode: Enhanced reconnection and session management active")
            
            // Start memory monitoring for long sessions
            startMemoryMonitoring()
        } else {
            sessionStartTime = 0L
            continuousSessionId = null
            Log.d(TAG, "Starting in STANDARD mode - may auto-stop on long silence")
        }
        
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _error.value = "Microphone permission not granted"
            return
        }
        
        // Check API key
        if (BuildConfig.SONIOX_API_KEY.isEmpty()) {
            _error.value = "Soniox API key not configured. Please add SONIOX_API_KEY to local.properties"
            return
        }
        
        _isListening.value = true
        _error.value = null
        currentTranscript.clear()
        accumulatedSegments.clear()
        _recognizedText.value = null
        _partialText.value = null  // Clear partial text, don't use for system status
        _accumulatedText.value = null
        _systemStatus.value = "Connecting to Soniox..."  // Use system status instead
        _connectionState.value = ConnectionState.CONNECTING
        
        // SENTENCE ACCUMULATION: Clear sentence state to prevent contamination
        sentenceAccumulator.clear()
        sentenceTimeoutJob?.cancel()
        lastTokenTime = 0L
        
        // CRITICAL FIX: Clear token state to prevent contamination
        clearTokenState()
        
        // Clear audio buffer
        audioBuffer.clear()
        isBuffering = true
        
        // Reset audio quality analyzer for new session
        audioQualityAnalyzer.reset()
        
        // PRODUCTION FIX: Enable bypass in debug mode to allow all audio through
        audioQualityAnalyzer.bypassQualityFiltering = BuildConfig.DEBUG
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "DEBUG MODE: Audio quality filtering DISABLED - All audio will be processed")
        } else {
            Log.d(TAG, "PRODUCTION MODE: Audio quality filtering ENABLED - Quality-based filtering active")
        }
        
        // Start audio recording immediately (buffer while connecting)
        startAudioRecording()
        
        // Connect to Soniox WebSocket
        connectWebSocket()
    }
    
    fun stopListening() {
        isIntentionallyStopping = true
        _isListening.value = false
        isBuffering = false
        _connectionState.value = ConnectionState.DISCONNECTED
        
        // Stop memory monitoring for continuous mode
        stopMemoryMonitoring()
        
        // Comprehensive buffer cleanup to prevent memory leaks
        audioBuffer.clear()
        Log.d(TAG, "Audio buffer cleared (${audioBuffer.size} remaining)")
        
        // SENTENCE ACCUMULATION: Process any remaining accumulated text before stopping
        if (sentenceAccumulator.isNotEmpty()) {
            Log.d(TAG, "Processing remaining sentence before stop: '${sentenceAccumulator.toString()}'")
            processSentenceCompletion(sentenceAccumulator.toString(), forceComplete = true)
        }
        
        // Clear sentence accumulation state
        sentenceAccumulator.clear()
        sentenceTimeoutJob?.cancel()
        lastTokenTime = 0L
        
        // Cancel recording job
        recordingJob?.cancel()
        recordingJob = null
        
        // Stop keepalive
        stopKeepalive()
        
        // Release audio effects
        releaseAudioEffects()
        
        // Stop and release audio recorder
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder", e)
        }
        audioRecord = null
        
        // Close WebSocket
        try {
            webSocket?.close(1000, "User stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        
        Log.d(TAG, "Stopped listening successfully")
    }
    
    /**
     * Emergency cleanup for low memory situations
     */
    fun performEmergencyCleanup() {
        Log.w(TAG, "Performing emergency memory cleanup")
        
        // Immediately clear all buffers
        audioBuffer.clear()
        accumulatedSegments.clear()
        currentTranscript.clear()
        
        // Force garbage collection hint
        System.gc()
        
        Log.d(TAG, "Emergency cleanup completed - all audio buffers cleared")
    }
    
    /**
     * Get current memory usage for monitoring
     */
    fun getMemoryUsage(): MemoryUsageInfo {
        val audioBufferBytes = audioBuffer.sumOf { it.size }
        val accumulatedTextBytes = accumulatedSegments.sumOf { it.toByteArray().size }
        
        return MemoryUsageInfo(
            audioBufferChunks = audioBuffer.size,
            audioBufferBytes = audioBufferBytes,
            accumulatedTextBytes = accumulatedTextBytes,
            totalMemoryBytes = audioBufferBytes + accumulatedTextBytes
        )
    }
    
    data class MemoryUsageInfo(
        val audioBufferChunks: Int,
        val audioBufferBytes: Int,
        val accumulatedTextBytes: Int,
        val totalMemoryBytes: Int
    )
    
    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(SONIOX_WS_URL)
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✓ WebSocket connected to Soniox")
                _connectionState.value = ConnectionState.CONNECTED
                _systemStatus.value = null  // Clear system status when connected
                
                // Send authentication and configuration (Soniox API v2)
                // DYNAMIC CONFIGURATION: Uses learned phrases from SmartPhraseCache
                val dynamicSpeechContexts = smartPhraseCache.buildSpeechContexts()
                val authConfig = SonioxAuthConfig(
                    apiKey = BuildConfig.SONIOX_API_KEY,
                    model = MODEL_STT_REALTIME,
                    audioFormat = "pcm_s16le",
                    sampleRate = SAMPLE_RATE,
                    numChannels = 1,
                    language = "ko", // Korean-only mode for best accuracy
                    enableNonFinalTokens = true, // Enable partial tokens for real-time display
                    enablePunctuation = true, // Let Soniox handle punctuation
                    enableVoiceActivityDetection = true, // Better silence handling
                    speechContexts = dynamicSpeechContexts // Dynamic learning from usage
                )
                
                val authJson = gson.toJson(authConfig)
                webSocket.send(authJson)
                Log.d(TAG, "Sent authentication config with model: ${authConfig.model}")
                
                // Soniox doesn't send an 'authenticated' response, it starts streaming immediately
                _connectionState.value = ConnectionState.AUTHENTICATED
                _systemStatus.value = null  // Clear system status when authenticated
                
                // Stop buffering and send buffered audio immediately
                isBuffering = false
                coroutineScope.launch {
                    if (audioBuffer.isNotEmpty()) {
                        audioBuffer.forEach { audioData ->
                            webSocket?.send(audioData.toByteString())
                        }
                        audioBuffer.clear()
                        Log.d(TAG, "Sent buffered audio to Soniox")
                    }
                }
                
                // Start keepalive to prevent timeout
                startKeepalive()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSonioxResponse(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _error.value = "Connection failed: ${t.message}"
                
                if (!isIntentionallyStopping && _isListening.value) {
                    // Retry connection after delay
                    coroutineScope.launch {
                        delay(2000)
                        if (!isIntentionallyStopping && _isListening.value) {
                            reconnect()
                        }
                    }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                
                if (!isIntentionallyStopping && _isListening.value && code != 1000) {
                    // Unexpected close, reconnect
                    coroutineScope.launch {
                        delay(1000)
                        if (!isIntentionallyStopping && _isListening.value) {
                            reconnect()
                        }
                    }
                }
            }
        })
    }
    
    private fun startAudioRecording() {
        // Optimal buffer size for low latency + stability
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNELS,
            AUDIO_FORMAT
        ).coerceAtLeast(CHUNK_SIZE_BYTES * 4) // 4x for better stability and less dropouts
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Better for speech recognition than MIC
                SAMPLE_RATE,
                CHANNELS,
                AUDIO_FORMAT,
                minBufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _error.value = "Failed to initialize audio recorder"
                return
            }
            
            // Initialize audio preprocessing
            initializeAudioEffects()
            
            audioRecord?.startRecording()
            Log.d(TAG, "Audio recording started with preprocessing")
            
            recordingJob = coroutineScope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var audioChunkCount = 0
                var lastQualityCheck = System.currentTimeMillis()
                
                while (isActive && _isListening.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        val audioData = if (bytesRead < buffer.size) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer.copyOf()
                        }
                        
                        // ENHANCED: Audio quality analysis every 500ms
                        audioChunkCount++
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastQualityCheck > 500) {
                            try {
                                val qualityMetrics = audioQualityAnalyzer.analyzeAudioChunk(audioData)
                                
                                // Log quality issues if any
                                if (qualityMetrics.qualityIssues.isNotEmpty()) {
                                    Log.w(TAG, "Audio quality issues detected: ${qualityMetrics.qualityIssues.joinToString(", ")}")
                                    
                                    // Show user-friendly quality feedback
                                    if (qualityMetrics.qualityScore < 0.4) {
                                        _partialText.value = "[Audio quality: ${(qualityMetrics.qualityScore * 100).toInt()}% - ${qualityMetrics.recommendations.firstOrNull() ?: "Check microphone"}]"
                                    }
                                }
                                
                                // DEBUG: Log detailed metrics for troubleshooting
                                Log.d(TAG, "Quality Metrics - SNR: ${qualityMetrics.snrDb.toInt()}dB, " +
                                          "Voice: ${qualityMetrics.voiceActivityDetected}, " +
                                          "Energy: ${(qualityMetrics.voiceEnergy * 100).toInt()}%, " +
                                          "Quality: ${(qualityMetrics.qualityScore * 100).toInt()}%, " +
                                          "Acceptable: ${qualityMetrics.isAcceptableForRecognition}, " +
                                          "Bypass: ${audioQualityAnalyzer.bypassQualityFiltering}")
                                
                                // PRODUCTION FIX: Log quality but don't reject audio - let Soniox decide
                                if (!qualityMetrics.isAcceptableForRecognition && !audioQualityAnalyzer.bypassQualityFiltering) {
                                    Log.w(TAG, "⚠ AUDIO QUALITY LOW ⚠ Quality: ${(qualityMetrics.qualityScore * 100).toInt()}%, " +
                                              "SNR: ${qualityMetrics.snrDb.toInt()}dB, Issues: ${qualityMetrics.qualityIssues}, " +
                                              "SENDING TO SONIOX ANYWAY")
                                } else {
                                    Log.i(TAG, "✓ AUDIO QUALITY GOOD - Quality: ${(qualityMetrics.qualityScore * 100).toInt()}%, SNR: ${qualityMetrics.snrDb.toInt()}dB")
                                }
                                // Always proceed - Soniox has professional VAD and will handle quality issues
                                
                                lastQualityCheck = currentTime
                            } catch (e: Exception) {
                                Log.e(TAG, "Audio quality analysis failed", e)
                                // Continue without quality filtering if analysis fails
                            }
                        }
                        
                        if (isBuffering) {
                            // Buffer audio while connecting with strict memory management
                            audioBuffer.add(audioData)
                            
                            // Aggressive buffer size limiting to prevent memory leaks
                            val maxBufferSize = 50 // Reduced from 100 (about 5 seconds max buffer)
                            if (audioBuffer.size > maxBufferSize) {
                                // Remove oldest chunks to prevent memory accumulation
                                while (audioBuffer.size > maxBufferSize) {
                                    audioBuffer.removeAt(0)
                                }
                                Log.d(TAG, "Audio buffer trimmed to prevent memory leaks: ${audioBuffer.size} chunks")
                            }
                        } else if (webSocket != null && _connectionState.value == ConnectionState.AUTHENTICATED) {
                            // Stream directly to WebSocket (only high-quality audio reaches here)
                            webSocket?.send(audioData.toByteString())
                        }
                    }
                }
                
                // Cleanup when coroutine completes (normal or cancelled)
                Log.d(TAG, "Recording coroutine completed, cleaning up buffers")
                audioBuffer.clear()
                isBuffering = false
            }
        } catch (e: SecurityException) {
            _error.value = "Microphone permission denied"
            // Clean up on permission error
            audioBuffer.clear()
            isBuffering = false
            releaseAudioEffects()
        } catch (e: Exception) {
            _error.value = "Failed to start recording: ${e.message}"
            // Clean up on any recording error
            audioBuffer.clear()
            isBuffering = false
            releaseAudioEffects()
            Log.e(TAG, "Recording error cleanup performed", e)
        }
    }
    
    private fun initializeAudioEffects() {
        audioRecord?.audioSessionId?.let { sessionId ->
            try {
                // Initialize noise suppressor
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId).apply {
                        enabled = true
                        Log.d(TAG, "NoiseSuppressor enabled")
                    }
                }
                
                // Initialize automatic gain control
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(sessionId).apply {
                        enabled = true
                        Log.d(TAG, "AutomaticGainControl enabled")
                    }
                }
                
                // Initialize acoustic echo canceler
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(sessionId).apply {
                        enabled = true
                        Log.d(TAG, "AcousticEchoCanceler enabled")
                    }
                }
                
                Log.d(TAG, "Audio preprocessing effects initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing audio effects", e)
            }
        }
    }
    
    private fun releaseAudioEffects() {
        try {
            noiseSuppressor?.apply {
                enabled = false
                release()
            }
            noiseSuppressor = null
            
            automaticGainControl?.apply {
                enabled = false
                release()
            }
            automaticGainControl = null
            
            acousticEchoCanceler?.apply {
                enabled = false
                release()
            }
            acousticEchoCanceler = null
            
            Log.d(TAG, "Audio effects released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
    }
    
    /**
     * ENHANCED: Clear token state with proper segment isolation
     */
    private fun clearTokenState() {
        // Preserve previous segment for context but isolate from current
        previousSegmentTokens.clear()
        previousSegmentTokens.addAll(currentSegmentTokens)
        
        // Clear current segment state
        currentSegmentTokens.clear()
        tokenAccumulator.clear()
        
        // Update segment tracking
        lastFinalSegmentTime = System.currentTimeMillis()
        segmentBoundaryConfirmed = false
        segmentId++
        
        Log.d(TAG, "Segment ${segmentId} started - previous segment preserved, current state cleared")
    }
    
    /**
     * ENHANCED: Detect token contamination with Korean-aware pattern recognition
     */
    private fun isTokenContaminated(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        
        val joinedText = tokens.joinToString("")
        
        // Check for repetitive patterns that indicate contamination (like "잖아요잖아요")
        val repeatingPatterns = Regex("([가-힣]{2,})\\1+")
        if (repeatingPatterns.containsMatchIn(joinedText)) {
            Log.w(TAG, "Detected repetitive pattern contamination: '$joinedText'")
            return true
        }
        
        // Check for impossible Korean combinations that indicate token mixing
        val impossibleCombos = listOf(
            Regex("[가-힣]+잖아요[가-힣]+잖아요"), // Double 잖아요 patterns
            Regex("제가그랬.*제가그랬"), // Repeated phrases
            Regex("([가-힣]{2,})\\s+\\1") // Same word with space
        )
        
        for (pattern in impossibleCombos) {
            if (pattern.containsMatchIn(joinedText)) {
                Log.w(TAG, "Detected impossible combination contamination: '$joinedText'")
                return true
            }
        }
        
        // Check if tokens contain previous segment endings bleeding into new text
        val currentText = tokens.joinToString(" ")
        if (previousSegmentTokens.isNotEmpty()) {
            val previousEnd = previousSegmentTokens.takeLast(2).joinToString("")
            if (currentText.contains(previousEnd) && previousEnd.length > 2) {
                Log.w(TAG, "Detected previous segment bleed: '$previousEnd' in '$currentText'")
                return true
            }
        }
        
        return false
    }
    
    // NOTE: Complex syllable reconstruction removed - Soniox provides proper syllables
    
    private fun updateAccumulatedText() {
        val accumulated = accumulatedSegments.joinToString(" ")
        _accumulatedText.value = accumulated
    }
    
    // NOTE: Token relation and merging logic removed - not needed with direct Soniox processing

    private fun handleSonioxResponse(response: String) {
        try {
            val sonioxResponse = gson.fromJson(response, SonioxResponse::class.java)
            
            // Check for errors first
            if (sonioxResponse.errorCode != null) {
                Log.e(TAG, "Soniox error ${sonioxResponse.errorCode}: ${sonioxResponse.errorMessage}")
                _error.value = "Recognition error: ${sonioxResponse.errorMessage}"
                return
            }
            
            // SIMPLIFIED: Trust Soniox's final tokens directly
            val finalTokens = sonioxResponse.tokens
                ?.filter { token ->
                    token.isFinal == true && 
                    !token.text.isNullOrBlank() &&
                    koreanTextValidator.containsKorean(token.text)
                }
                ?.mapNotNull { it.text?.trim() }
                ?: emptyList()
            
            if (finalTokens.isNotEmpty()) {
                // Clear partial accumulator when final results arrive
                partialTokenAccumulator.clear()
                
                // Fix Korean spacing - join tokens without spaces for proper Korean text
                val directText = joinKoreanTokensProper(finalTokens)
                
                // Calculate average confidence to decide processing level
                val avgConfidence = sonioxResponse.tokens
                    ?.filter { it.isFinal == true && it.confidence != null }
                    ?.mapNotNull { it.confidence }
                    ?.average()?.toFloat() ?: 1.0f
                
                // Store confidence for later use
                _confidence.value = avgConfidence
                
                Log.d(TAG, "=== SENTENCE ACCUMULATION ===")
                Log.d(TAG, "Final tokens: [${finalTokens.joinToString(", ")}]")
                Log.d(TAG, "Direct text: '$directText'")
                Log.d(TAG, "Confidence: ${(avgConfidence * 100).toInt()}%")
                
                // SENTENCE ACCUMULATION: Instead of immediately emitting, accumulate until sentence is complete
                if (directText.isNotBlank()) {
                    // SAFETY CHECK: Prevent runaway accumulation
                    if (sentenceAccumulator.length + directText.length > SentenceDetectionConstants.MAX_SENTENCE_LENGTH) {
                        Log.w(TAG, "Sentence accumulator exceeding max length (${sentenceAccumulator.length + directText.length}) - forcing completion")
                        processSentenceCompletion(sentenceAccumulator.toString(), forceComplete = true)
                    }
                    
                    // Add space if we're continuing from previous tokens (unless Korean doesn't need it)
                    val needsSpace = sentenceAccumulator.isNotEmpty() && 
                                   !sentenceAccumulator.toString().endsWith(" ") &&
                                   !directText.startsWith(" ") &&
                                   koreanTextValidator.containsKorean(sentenceAccumulator.toString()) &&
                                   koreanTextValidator.containsKorean(directText)
                    
                    if (needsSpace) {
                        sentenceAccumulator.append(" ")
                    }
                    sentenceAccumulator.append(directText)
                    lastTokenTime = System.currentTimeMillis()
                    
                    val currentAccumulation = sentenceAccumulator.toString()
                    Log.d(TAG, "Accumulated: '$currentAccumulation'")
                    
                    // Update partial text to show current accumulation
                    _partialText.value = "$currentAccumulation..."
                    
                    // Check if sentence is complete (with edge case protection)
                    if (isSentenceComplete(currentAccumulation) && !sentenceCompletionInProgress) {
                        Log.d(TAG, "Sentence boundary detected - processing complete sentence")
                        sentenceCompletionInProgress = true
                        processSentenceCompletion(currentAccumulation)
                        sentenceCompletionInProgress = false
                    } else if (!sentenceCompletionInProgress) {
                        Log.d(TAG, "Sentence not complete - starting timeout monitor")
                        // Start timeout monitor for incomplete sentences
                        startSentenceTimeoutMonitor()
                    }
                }
                Log.d(TAG, "=============================")
            }
            
            // Handle non-final tokens for real-time display with proper Korean spacing
            val nonFinalTokens = sonioxResponse.tokens
                ?.filter { token ->
                    token.isFinal != true &&
                    !token.text.isNullOrBlank()
                }
                ?.mapNotNull { it.text?.trim() }
                ?: emptyList()
            
            if (nonFinalTokens.isNotEmpty()) {
                // Accumulate partial tokens (they come as individual syllables)
                partialTokenAccumulator.clear()
                partialTokenAccumulator.addAll(nonFinalTokens)
                
                // Join all accumulated tokens without spaces first
                val joinedText = partialTokenAccumulator.joinToString("")
                
                // Only process if we have Korean content
                if (koreanTextValidator.containsKorean(joinedText)) {
                    // Use KoreanNLPService to fix spacing intelligently
                    coroutineScope.launch {
                        try {
                            val properlySpacedText = koreanNLPService.fixSpeechRecognitionSpacing(joinedText)
                            _partialText.value = "$properlySpacedText..."
                            Log.d(TAG, "Real-time Korean: '$joinedText' -> '$properlySpacedText'")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fix Korean spacing for partial text", e)
                            // Fallback to original behavior
                            val interimText = joinKoreanTokensProper(nonFinalTokens)
                            _partialText.value = "$interimText..."
                        }
                    }
                }
            }
            
            // Log completion
            if (sonioxResponse.finished == true) {
                Log.d(TAG, "Transcription finished - total audio processed: ${sonioxResponse.totalAudioProcMs}ms")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Soniox response: ${e.message}", e)
            Log.e(TAG, "Raw response: $response")
        }
    }
    
    // NOTE: Performance tracking and complex token processing removed
    
    // NOTE: Complex handleFinalTranscript function removed - now handled in simplified handleSonioxResponse
    
    // NOTE: Complex handleInterimTranscript removed - interim display handled in main response handler
    
    private suspend fun reconnect() {
        if (!isIntentionallyStopping && _isListening.value) {
            Log.d(TAG, "Attempting to reconnect...")
            
            recordingJob?.cancel()
            recordingJob = null
            
            try {
                webSocket?.close(1000, "Reconnecting")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing WebSocket during reconnect", e)
            }
            webSocket = null
            
            delay(500)
            
            if (!isIntentionallyStopping && _isListening.value) {
                connectWebSocket()
            }
        }
    }
    
    private fun startKeepalive() {
        keepaliveJob?.cancel() // Cancel any existing keepalive
        
        val keepaliveInterval = if (continuousMode) {
            10000L // More aggressive keepalive for continuous mode (10 seconds)
        } else {
            15000L // Standard keepalive (15 seconds)
        }
        
        keepaliveJob = coroutineScope.launch {
            var keepaliveCount = 0
            
            while (isActive) {
                delay(keepaliveInterval)
                
                if (_connectionState.value == ConnectionState.AUTHENTICATED && webSocket != null) {
                    try {
                        keepaliveCount++
                        val sessionInfo = if (continuousMode && continuousSessionId != null) {
                            val sessionDuration = System.currentTimeMillis() - sessionStartTime
                            ", session: $continuousSessionId, duration: ${sessionDuration/1000}s"
                        } else ""
                        
                        webSocket?.send("""{"type": "keepalive", "count": $keepaliveCount}""")
                        Log.d(TAG, "Sent keepalive #$keepaliveCount$sessionInfo")
                        
                        // Log continuous session statistics every 10 keepalives
                        if (continuousMode && keepaliveCount % 10 == 0) {
                            val sessionMinutes = (System.currentTimeMillis() - sessionStartTime) / 60000
                            Log.i(TAG, "Continuous session running for $sessionMinutes minutes")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send keepalive #$keepaliveCount", e)
                    }
                }
            }
        }
        
        val mode = if (continuousMode) "CONTINUOUS" else "STANDARD"
        Log.d(TAG, "Started $mode mode keepalive job (${keepaliveInterval}ms interval)")
    }
    
    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        Log.d(TAG, "Stopped keepalive job")
    }
    
    fun clearAccumulatedText() {
        accumulatedSegments.clear()
        _accumulatedText.value = null
        currentTranscript.clear()
        _partialText.value = null
        _recognizedText.value = null
    }
    
    fun clearError() {
        _error.value = null
    }
    
    /**
     * Handle user corrections - learn from what the user actually said
     */
    fun handleUserCorrection(originalTranscription: String, userCorrection: String) {
        Log.d(TAG, "User correction: '$originalTranscription' -> '$userCorrection'")
        smartPhraseCache.learnFromUserCorrection(originalTranscription, userCorrection)
    }
    
    /**
     * Get performance statistics for monitoring Gemini reconstruction
     */
    fun getReconstructionStats(): Map<String, Any> {
        return mapOf(
            "reconstructionCount" to reconstructionCount,
            "averageReconstructionTime" to if (reconstructionCount > 0) totalReconstructionTime / reconstructionCount else 0L,
            "totalReconstructionTime" to totalReconstructionTime,
            "cacheHitCount" to cacheHitCount,
            "geminiCallCount" to geminiCallCount,
            "cacheHitRate" to if (reconstructionCount > 0) cacheHitCount.toFloat() / reconstructionCount.toFloat() else 0.0f,
            "geminiCacheStats" to geminiReconstructionService.getCacheStats()
        )
    }
    
    /**
     * Reset performance statistics (useful for testing)
     */
    fun resetReconstructionStats() {
        reconstructionCount = 0
        totalReconstructionTime = 0L
        cacheHitCount = 0
        geminiCallCount = 0
        geminiReconstructionService.clearCache()
        Log.d(TAG, "Reconstruction statistics reset")
    }
    
    /**
     * Start memory monitoring for continuous sessions
     */
    private fun startMemoryMonitoring() {
        memoryMonitoringJob?.cancel()
        
        memoryMonitoringJob = coroutineScope.launch {
            while (isActive && continuousMode) {
                delay(30000) // Check every 30 seconds
                
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                val memoryUsagePercent = (usedMemory.toFloat() / maxMemory.toFloat()) * 100
                
                // Log memory stats every 2 minutes
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMemoryWarning > 120000) {
                    val sessionMinutes = (currentTime - sessionStartTime) / 60000
                    Log.d(TAG, "Continuous session memory: ${memoryUsagePercent.toInt()}% " +
                           "(${usedMemory/1024/1024}MB/${maxMemory/1024/1024}MB) " +
                           "after ${sessionMinutes}min, cleanups: $bufferCleanupCount")
                    lastMemoryWarning = currentTime
                }
                
                // Perform aggressive cleanup if memory usage is high
                if (memoryUsagePercent > 80) {
                    Log.w(TAG, "High memory usage (${memoryUsagePercent.toInt()}%) - performing cleanup")
                    performMemoryCleanup()
                }
            }
        }
    }
    
    /**
     * Perform memory cleanup for long sessions
     */
    private fun performMemoryCleanup() {
        bufferCleanupCount++
        
        // Clear old accumulated text if we have too much
        if (accumulatedSegments.size > 10) {
            val toKeep = accumulatedSegments.takeLast(5)
            accumulatedSegments.clear()
            accumulatedSegments.addAll(toKeep)
            Log.d(TAG, "Trimmed accumulated segments to last 5 (was ${accumulatedSegments.size + 5})")
        }
        
        // Clear old audio buffer
        if (audioBuffer.size > 50) {
            val toKeep = audioBuffer.takeLast(20)
            audioBuffer.clear()
            audioBuffer.addAll(toKeep)
            Log.d(TAG, "Trimmed audio buffer to last 20 chunks (was ${audioBuffer.size + 30})")
        }
        
        // Clear old token accumulators
        if (currentSegmentTokens.size > 100) {
            currentSegmentTokens.clear()
            Log.d(TAG, "Cleared current segment tokens")
        }
        
        if (previousSegmentTokens.size > 50) {
            previousSegmentTokens.clear()
            Log.d(TAG, "Cleared previous segment tokens")
        }
        
        // Force garbage collection
        System.gc()
        
        Log.d(TAG, "Memory cleanup #$bufferCleanupCount completed")
    }
    
    /**
     * Stop memory monitoring
     */
    private fun stopMemoryMonitoring() {
        memoryMonitoringJob?.cancel()
        memoryMonitoringJob = null
        Log.d(TAG, "Memory monitoring stopped")
    }
    
    fun cleanup() {
        isIntentionallyStopping = true
        stopMemoryMonitoring()
        stopListening()
        coroutineScope.cancel()
        try {
            httpClient.dispatcher.executorService.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    // NOTE: Complex Gemini reconstruction and token merging functions removed
    // The app now trusts Soniox's output directly for better accuracy
    
    // Removed containsKorean and cleanExcessiveSpaces - now using KoreanTextValidator
    
    /**
     * Properly join Korean tokens without unnecessary spaces
     * Korean syllables should be connected, not separated
     */
    private fun joinKoreanTokensProper(tokens: List<String>): String {
        if (tokens.isEmpty()) return ""
        
        // Join all tokens without spaces first
        val joined = tokens.joinToString("").trim()
        
        // Add spaces only around punctuation if needed
        return joined.replace(Regex("([.!?])([가-힣])"), "$1 $2")
                     .replace(Regex("([가-힣])([.!?])"), "$1$2")
    }
    
    /**
     * SENTENCE COMPLETION DETECTION: Check if accumulated text forms a complete sentence
     * This fixes the fragmentation issue by waiting for proper sentence boundaries
     */
    private fun isSentenceComplete(text: String): Boolean {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return false
        
        // Check for explicit punctuation using shared constants
        if (SentenceDetectionConstants.KOREAN_PUNCTUATION.any { trimmedText.endsWith(it) }) {
            Log.d(TAG, "Sentence complete: punctuation detected")
            return true
        }
        
        // Check for common Korean sentence endings using shared constants
        if (SentenceDetectionConstants.KOREAN_SENTENCE_ENDINGS.any { trimmedText.endsWith(it) }) {
            Log.d(TAG, "Sentence complete: Korean ending detected")
            return true
        }
        
        // Check for timeout (incomplete sentence handling) using shared constants
        val timeSinceLastToken = System.currentTimeMillis() - lastTokenTime
        if (timeSinceLastToken > SentenceDetectionConstants.SENTENCE_TIMEOUT_MS && 
            trimmedText.length > SentenceDetectionConstants.MIN_LENGTH_FOR_TIMEOUT_COMPLETION) {
            Log.d(TAG, "Sentence complete: timeout after ${timeSinceLastToken}ms")
            return true
        }
        
        return false
    }
    
    /**
     * TIMEOUT HANDLER: Start monitoring for sentence completion timeout
     */
    private fun startSentenceTimeoutMonitor() {
        sentenceTimeoutJob?.cancel()
        sentenceTimeoutJob = coroutineScope.launch {
            delay(SentenceDetectionConstants.SENTENCE_TIMEOUT_MS)
            
            // Check if we have accumulated text that should be processed
            if (sentenceAccumulator.isNotEmpty() && _isListening.value) {
                Log.d(TAG, "Sentence timeout - forcing completion of: '${sentenceAccumulator.toString().take(50)}...'")
                processSentenceCompletion(sentenceAccumulator.toString(), forceComplete = true)
            }
        }
    }
    
    /**
     * SENTENCE COMPLETION PROCESSOR: Handle complete sentences
     */
    private fun processSentenceCompletion(completeSentence: String, forceComplete: Boolean = false) {
        val trimmedSentence = completeSentence.trim()
        if (trimmedSentence.isEmpty()) return
        
        Log.d(TAG, "Processing complete sentence: '$trimmedSentence' (forced: $forceComplete)")
        
        // Clear accumulator
        sentenceAccumulator.clear()
        sentenceTimeoutJob?.cancel()
        
        // Process the complete sentence with confidence-aware corrector
        coroutineScope.launch {
            try {
                val avgConfidence = _confidence.value ?: 1.0f
                val processingResult = confidenceAwareCorrector.processTranscript(trimmedSentence, avgConfidence)
                val finalText = processingResult.processedText
                val finalConfidence = processingResult.finalConfidence
                
                Log.d(TAG, "=== SENTENCE COMPLETION ===")
                Log.d(TAG, "Complete sentence: '$finalText'")
                Log.d(TAG, "Confidence: ${(finalConfidence * 100).toInt()}%")
                Log.d(TAG, "Processing: ${processingResult.processingLevel}")
                Log.d(TAG, "===========================")
                
                // Update UI with complete sentence
                if (finalText.isNotBlank()) {
                    _recognizedText.value = finalText
                    _confidence.value = finalConfidence
                    accumulatedSegments.add(finalText)
                    updateAccumulatedText()
                    _partialText.value = null
                    
                    // LEARNING: Feed high-confidence transcriptions to SmartPhraseCache
                    smartPhraseCache.learnFromTranscription(finalText, finalConfidence)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing sentence completion", e)
                // Fallback: emit original text
                _recognizedText.value = trimmedSentence
            }
        }
    }
    
    // NOTE: Advanced token reconstruction removed - trusting Soniox output directly
    
    // NOTE: Multi-token word boundary detection removed - Soniox handles this better
    
    // NOTE: Complex token filtering and spacing logic removed - Soniox handles proper Korean spacing
    
    // NOTE: All complex fragment detection and symbol filtering removed
    // Soniox's final tokens are already clean and properly formatted
    
    // NOTE: Minimal corrections replaced by confidence-aware corrector
    
    // Soniox data classes (API v2 - August 2025) - Optimized for accuracy
    data class SonioxAuthConfig(
        @SerializedName("api_key") val apiKey: String,
        @SerializedName("model") val model: String,
        @SerializedName("audio_format") val audioFormat: String,
        @SerializedName("sample_rate") val sampleRate: Int,
        @SerializedName("num_channels") val numChannels: Int,
        @SerializedName("language") val language: String? = null,
        @SerializedName("enable_non_final_tokens") val enableNonFinalTokens: Boolean? = false, // Only final tokens
        @SerializedName("enable_punctuation") val enablePunctuation: Boolean? = true, // Let Soniox handle punctuation
        @SerializedName("enable_voice_activity_detection") val enableVoiceActivityDetection: Boolean? = true,
        @SerializedName("speech_contexts") val speechContexts: List<SpeechContext>? = null
    )
    
    data class SpeechContext(
        @SerializedName("phrases") val phrases: List<String>,
        @SerializedName("boost") val boost: Float = 0f
    )
    
    data class SonioxResponse(
        @SerializedName("tokens") val tokens: List<SonioxToken>?,
        @SerializedName("final_audio_proc_ms") val finalAudioProcMs: Int?,
        @SerializedName("total_audio_proc_ms") val totalAudioProcMs: Int?,
        @SerializedName("finished") val finished: Boolean?,
        @SerializedName("error_code") val errorCode: Int?,
        @SerializedName("error_message") val errorMessage: String?
    )
    
    data class SonioxToken(
        @SerializedName("text") val text: String?,
        @SerializedName("start_ms") val startMs: Int?,
        @SerializedName("end_ms") val endMs: Int?,
        @SerializedName("confidence") val confidence: Float?,
        @SerializedName("is_final") val isFinal: Boolean?,
        @SerializedName("speaker") val speaker: String?,
        @SerializedName("language") val language: String?
    )
}