package com.koreantranslator.service

import android.media.audiofx.Visualizer
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * AudioQualityAnalyzer - Analyzes audio quality to improve speech recognition
 * 
 * This service helps identify poor audio conditions that lead to recognition failures,
 * symbols, and garbled text output from Soniox. It provides:
 * - Real-time audio quality metrics
 * - Voice activity detection
 * - Background noise analysis
 * - Signal-to-noise ratio estimation
 * - Quality recommendations for better recognition
 */
@Singleton
class AudioQualityAnalyzer @Inject constructor() {
    
    companion object {
        private const val TAG = "AudioQualityAnalyzer"
        
        // EMERGENCY FIX: Relaxed thresholds to get audio flowing to Soniox
        private const val MIN_SNR_DB = 3.0 // LOWERED: Let audio through with basic SNR
        private const val MIN_VOICE_ENERGY = 0.01 // LOWERED: Accept very quiet speech
        private const val MAX_CLIPPING_RATIO = 0.10 // INCREASED: More tolerant of clipping
        private const val MIN_VOICE_FREQUENCY_RANGE = 100.0 // Wider frequency range
        private const val MAX_VOICE_FREQUENCY_RANGE = 4000.0 // Wider frequency range
        
        // EMERGENCY: Much lower thresholds to allow audio through
        private const val MIN_ACCEPTABLE_QUALITY = 0.30 // LOWERED: 30% minimum quality
        private const val MIN_ACCEPTABLE_SNR = 2.0 // LOWERED: Very low SNR acceptance
        private const val MIN_VOICE_ACTIVITY_RATIO = 0.3 // Require significant voice content
        
        // Analysis window parameters
        private const val ANALYSIS_WINDOW_SIZE = 1024
        private const val OVERLAP_RATIO = 0.5
        private const val ANALYSIS_RATE_MS = 100 // Analyze every 100ms
    }
    
    // Real-time metrics with proper initialization
    private var currentSNR = 0.0
    private var currentNoiseLevel = 0.0
    private var currentVoiceEnergy = 0.0
    private var clippingRatio = 0.0
    private var voiceActivityDetected = false
    
    // PRODUCTION FIX: Add calibration state
    private var isCalibrated = false
    private var calibrationSamples = 0
    private var calibrationEnergySum = 0.0
    private val calibrationPeriodSamples = 50 // ~5 seconds at 100ms chunks
    
    // EMERGENCY: Bypass mode for testing
    var bypassQualityFiltering = false
    
    // Historical data for trending
    private val recentSNRValues = mutableListOf<Double>()
    private val recentEnergyValues = mutableListOf<Double>()
    private val recentNoiseValues = mutableListOf<Double>()
    private val maxHistorySize = 50 // Keep last 5 seconds of data
    
    /**
     * Analyze a chunk of audio data and return quality metrics
     */
    fun analyzeAudioChunk(audioData: ByteArray): AudioQualityMetrics {
        try {
            // Convert byte array to short array (16-bit PCM)
            val audioSamples = convertBytesToShorts(audioData)
            
            // Calculate basic audio metrics
            val rmsEnergy = calculateRMSEnergy(audioSamples)
            val peakAmplitude = calculatePeakAmplitude(audioSamples)
            val zeroCrossingRate = calculateZeroCrossingRate(audioSamples)
            val spectralCentroid = calculateSpectralCentroid(audioSamples)
            
            // Voice activity detection (energy-based)
            voiceActivityDetected = detectVoiceActivity(rmsEnergy, zeroCrossingRate)
            
            // PRODUCTION FIX: Proper noise floor calibration and SNR calculation
            if (!isCalibrated) {
                // Calibration phase: collect ambient noise samples
                calibrationSamples++
                calibrationEnergySum += rmsEnergy
                
                if (calibrationSamples >= calibrationPeriodSamples) {
                    // Set noise floor as average of calibration samples
                    currentNoiseLevel = calibrationEnergySum / calibrationSamples
                    isCalibrated = true
                    Log.d(TAG, "Audio calibration complete. Noise floor: ${String.format("%.6f", currentNoiseLevel)}")
                }
                
                // During calibration, use simple defaults
                currentVoiceEnergy = rmsEnergy
                currentSNR = 10.0 // Default SNR during calibration
            } else {
                // Post-calibration: update noise level conservatively during silence
                if (!voiceActivityDetected && rmsEnergy < currentNoiseLevel * 2.0) {
                    // Only update noise floor if current energy is reasonably low
                    currentNoiseLevel = 0.9 * currentNoiseLevel + 0.1 * rmsEnergy
                }
                
                currentVoiceEnergy = rmsEnergy
                
                // Proper SNR calculation with minimum noise floor
                val effectiveNoiseLevel = maxOf(currentNoiseLevel, 0.0001) // Prevent division by zero
                val snrRatio = currentVoiceEnergy / effectiveNoiseLevel
                currentSNR = 20 * log10(maxOf(snrRatio, 0.01)) // Prevent log(0)
            }
            
            // Calculate clipping ratio
            clippingRatio = calculateClippingRatio(audioSamples)
            
            // Update historical data
            updateHistoricalData()
            
            // Generate overall quality assessment
            val qualityScore = assessOverallQuality()
            val qualityIssues = identifyQualityIssues()
            val recommendations = generateRecommendations(qualityIssues)
            
            val metrics = AudioQualityMetrics(
                snrDb = currentSNR,
                voiceEnergy = currentVoiceEnergy,
                noiseLevel = currentNoiseLevel,
                clippingRatio = clippingRatio,
                voiceActivityDetected = voiceActivityDetected,
                spectralCentroid = spectralCentroid,
                zeroCrossingRate = zeroCrossingRate,
                qualityScore = qualityScore,
                qualityIssues = qualityIssues,
                recommendations = recommendations,
                isAcceptableForRecognition = isAudioAcceptableForRecognition(qualityScore)
            )
            
            val acceptabilityReason = getAcceptabilityReason(qualityScore)
            Log.d(TAG, "Audio quality: SNR=${currentSNR.toInt()}dB, Voice=${voiceActivityDetected}, Quality=${(qualityScore * 100).toInt()}% - $acceptabilityReason")
            
            return metrics
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing audio quality", e)
            return AudioQualityMetrics.defaultMetrics()
        }
    }
    
    /**
     * Convert byte array (PCM 16-bit) to short array
     */
    private fun convertBytesToShorts(audioData: ByteArray): ShortArray {
        val shortArray = ShortArray(audioData.size / 2)
        for (i in shortArray.indices) {
            val byteIndex = i * 2
            shortArray[i] = (audioData[byteIndex].toInt() and 0xFF or 
                           (audioData[byteIndex + 1].toInt() shl 8)).toShort()
        }
        return shortArray
    }
    
    /**
     * Calculate Root Mean Square energy of the audio signal
     */
    private fun calculateRMSEnergy(audioSamples: ShortArray): Double {
        if (audioSamples.isEmpty()) return 0.0
        
        var sum = 0.0
        for (sample in audioSamples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / audioSamples.size) / Short.MAX_VALUE
    }
    
    /**
     * Calculate peak amplitude (max absolute value)
     */
    private fun calculatePeakAmplitude(audioSamples: ShortArray): Double {
        if (audioSamples.isEmpty()) return 0.0
        
        var maxAmp = 0
        for (sample in audioSamples) {
            maxAmp = maxOf(maxAmp, abs(sample.toInt()))
        }
        return maxAmp.toDouble() / Short.MAX_VALUE
    }
    
    /**
     * Calculate zero crossing rate (indicator of voice vs noise)
     */
    private fun calculateZeroCrossingRate(audioSamples: ShortArray): Double {
        if (audioSamples.size < 2) return 0.0
        
        var crossings = 0
        for (i in 1 until audioSamples.size) {
            if ((audioSamples[i] >= 0) != (audioSamples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (audioSamples.size - 1)
    }
    
    /**
     * Estimate spectral centroid (brightness of the sound)
     */
    private fun calculateSpectralCentroid(audioSamples: ShortArray): Double {
        if (audioSamples.size < ANALYSIS_WINDOW_SIZE) return 0.0
        
        // Simple spectral centroid estimation using autocorrelation
        var weightedSum = 0.0
        var magnitudeSum = 0.0
        
        // Use a simplified frequency analysis
        for (i in 1 until minOf(audioSamples.size, ANALYSIS_WINDOW_SIZE)) {
            val magnitude = abs(audioSamples[i].toDouble())
            val frequency = i.toDouble() * 16000.0 / ANALYSIS_WINDOW_SIZE // Assuming 16kHz sample rate
            
            weightedSum += frequency * magnitude
            magnitudeSum += magnitude
        }
        
        return if (magnitudeSum > 0.0) weightedSum / magnitudeSum else 0.0
    }
    
    /**
     * PRODUCTION FIX: Proper energy-based voice activity detection
     */
    private fun detectVoiceActivity(energy: Double, zcr: Double): Boolean {
        if (!isCalibrated) {
            // During calibration, assume no voice to establish baseline
            return false
        }
        
        // Simple but effective energy-based VAD with adaptive threshold
        val energyThreshold = currentNoiseLevel * 3.0 + MIN_VOICE_ENERGY // Dynamic threshold
        val hasEnergy = energy > energyThreshold
        
        // Zero crossing rate helps distinguish voice from steady noise
        val hasVoiceZCR = zcr > 0.01 && zcr < 0.4 // Expanded range for Korean speech
        
        // Combine energy and ZCR with preference for energy
        return hasEnergy && hasVoiceZCR
    }
    
    /**
     * Calculate ratio of clipped samples (indicates over-amplification)
     */
    private fun calculateClippingRatio(audioSamples: ShortArray): Double {
        if (audioSamples.isEmpty()) return 0.0
        
        val clippingThreshold = (Short.MAX_VALUE * 0.95).toInt()
        val clippedSamples = audioSamples.count { abs(it.toInt()) > clippingThreshold }
        
        return clippedSamples.toDouble() / audioSamples.size
    }
    
    /**
     * Update historical data for trend analysis
     */
    private fun updateHistoricalData() {
        recentSNRValues.add(currentSNR)
        recentEnergyValues.add(currentVoiceEnergy)
        
        // Keep only recent data
        if (recentSNRValues.size > maxHistorySize) {
            recentSNRValues.removeAt(0)
        }
        if (recentEnergyValues.size > maxHistorySize) {
            recentEnergyValues.removeAt(0)
        }
    }
    
    /**
     * Assess overall audio quality on a scale of 0.0 to 1.0
     */
    private fun assessOverallQuality(): Double {
        var qualityScore = 1.0
        
        // Factor 1: Signal-to-Noise Ratio (30% weight)
        val snrScore = when {
            currentSNR >= 20.0 -> 1.0
            currentSNR >= 15.0 -> 0.8
            currentSNR >= 10.0 -> 0.6
            currentSNR >= 5.0 -> 0.4
            else -> 0.2
        }
        qualityScore *= (0.7 + 0.3 * snrScore)
        
        // Factor 2: Clipping (20% weight)
        val clippingScore = when {
            clippingRatio <= 0.01 -> 1.0
            clippingRatio <= 0.05 -> 0.7
            clippingRatio <= 0.10 -> 0.4
            else -> 0.1
        }
        qualityScore *= (0.8 + 0.2 * clippingScore)
        
        // Factor 3: Voice energy (20% weight)
        val energyScore = when {
            currentVoiceEnergy >= 0.3 -> 1.0
            currentVoiceEnergy >= 0.1 -> 0.7
            currentVoiceEnergy >= 0.05 -> 0.4
            else -> 0.2
        }
        qualityScore *= (0.8 + 0.2 * energyScore)
        
        // Factor 4: Consistency (10% weight) - how stable the metrics are
        val consistencyScore = if (recentSNRValues.size > 5) {
            val snrVariance = calculateVariance(recentSNRValues)
            when {
                snrVariance <= 5.0 -> 1.0
                snrVariance <= 10.0 -> 0.8
                snrVariance <= 20.0 -> 0.6
                else -> 0.4
            }
        } else {
            0.8 // Default for insufficient data
        }
        qualityScore *= (0.9 + 0.1 * consistencyScore)
        
        return qualityScore.coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculate variance of a list of values
     */
    private fun calculateVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        
        val mean = values.average()
        val sumSquaredDiffs = values.sumOf { (it - mean) * (it - mean) }
        return sumSquaredDiffs / values.size
    }
    
    /**
     * Identify specific quality issues
     */
    private fun identifyQualityIssues(): List<AudioQualityIssue> {
        val issues = mutableListOf<AudioQualityIssue>()
        
        if (currentSNR < MIN_SNR_DB) {
            issues.add(AudioQualityIssue.LOW_SNR)
        }
        
        if (clippingRatio > MAX_CLIPPING_RATIO) {
            issues.add(AudioQualityIssue.AUDIO_CLIPPING)
        }
        
        if (currentVoiceEnergy < MIN_VOICE_ENERGY && voiceActivityDetected) {
            issues.add(AudioQualityIssue.LOW_VOLUME)
        }
        
        if (currentNoiseLevel > 0.2) {
            issues.add(AudioQualityIssue.HIGH_BACKGROUND_NOISE)
        }
        
        if (!voiceActivityDetected && currentVoiceEnergy > 0.05) {
            issues.add(AudioQualityIssue.UNCLEAR_SPEECH)
        }
        
        return issues
    }
    
    /**
     * Generate recommendations to improve audio quality
     */
    private fun generateRecommendations(issues: List<AudioQualityIssue>): List<String> {
        val recommendations = mutableListOf<String>()
        
        for (issue in issues) {
            when (issue) {
                AudioQualityIssue.LOW_SNR -> {
                    recommendations.add("Move to a quieter environment or closer to the microphone")
                    recommendations.add("Enable noise cancellation if available")
                }
                AudioQualityIssue.AUDIO_CLIPPING -> {
                    recommendations.add("Reduce microphone gain or speak more softly")
                    recommendations.add("Move slightly away from the microphone")
                }
                AudioQualityIssue.LOW_VOLUME -> {
                    recommendations.add("Speak louder or move closer to the microphone")
                    recommendations.add("Increase microphone sensitivity if possible")
                }
                AudioQualityIssue.HIGH_BACKGROUND_NOISE -> {
                    recommendations.add("Move to a quieter location")
                    recommendations.add("Close windows/doors to reduce external noise")
                }
                AudioQualityIssue.UNCLEAR_SPEECH -> {
                    recommendations.add("Speak more clearly and at a steady pace")
                    recommendations.add("Ensure microphone is not obstructed")
                }
                AudioQualityIssue.ANALYSIS_FAILED -> {
                    recommendations.add("Check microphone connection")
                    recommendations.add("Try restarting the recording session")
                }
            }
        }
        
        return recommendations.distinct()
    }
    
    /**
     * Get current audio quality summary
     */
    fun getCurrentQualitySummary(): String {
        return "SNR: ${currentSNR.toInt()}dB, Voice: ${if (voiceActivityDetected) "Yes" else "No"}, " +
               "Quality: ${(assessOverallQuality() * 100).toInt()}%"
    }
    
    /**
     * PRODUCTION FIX: Reset analyzer state with calibration reset
     */
    fun reset() {
        recentSNRValues.clear()
        recentEnergyValues.clear()
        recentNoiseValues.clear()
        currentSNR = 0.0
        currentNoiseLevel = 0.0
        currentVoiceEnergy = 0.0
        clippingRatio = 0.0
        voiceActivityDetected = false
        
        // Reset calibration state
        isCalibrated = false
        calibrationSamples = 0
        calibrationEnergySum = 0.0
        
        Log.d(TAG, "Audio quality analyzer reset - Starting calibration phase")
    }
    
    /**
     * PRODUCTION FIX: Confidence-based audio acceptance with calibration awareness
     */
    private fun isAudioAcceptableForRecognition(qualityScore: Double): Boolean {
        // BYPASS MODE: Accept all audio for testing
        if (bypassQualityFiltering) {
            return true
        }
        
        // During calibration, accept all audio to allow Soniox to handle quality
        if (!isCalibrated) {
            return true
        }
        
        // PRODUCTION: More reasonable acceptance criteria
        if (qualityScore < MIN_ACCEPTABLE_QUALITY) {
            return false
        }
        
        // PRODUCTION: More lenient SNR for real-world conditions
        if (currentSNR < -10.0) { // Very low threshold for harsh environments
            return false
        }
        
        // PRODUCTION: Accept very quiet speech
        if (currentVoiceEnergy < 0.0001) { // Extremely low threshold
            return false
        }
        
        // PRODUCTION: Higher tolerance for clipping
        if (clippingRatio > 0.20) { // 20% clipping tolerance
            return false
        }
        
        // Always accept - let Soniox's professional VAD make final decisions
        return true
    }
    
    /**
     * PRODUCTION FIX: Enhanced acceptability reason with calibration status
     */
    private fun getAcceptabilityReason(qualityScore: Double): String {
        if (!isCalibrated) {
            return "CALIBRATING (${calibrationSamples}/${calibrationPeriodSamples} samples)"
        }
        
        val reasons = mutableListOf<String>()
        
        if (qualityScore < MIN_ACCEPTABLE_QUALITY) {
            reasons.add("Quality too low (${(qualityScore * 100).toInt()}% < ${(MIN_ACCEPTABLE_QUALITY * 100).toInt()}%)")
        }
        
        if (currentSNR < MIN_ACCEPTABLE_SNR) {
            reasons.add("SNR too low (${currentSNR.toInt()}dB < ${MIN_ACCEPTABLE_SNR.toInt()}dB)")
        }
        
        if (currentVoiceEnergy < MIN_VOICE_ENERGY) {
            reasons.add("Voice energy too low (${(currentVoiceEnergy * 100).toInt()}% < ${(MIN_VOICE_ENERGY * 100).toInt()}%)")
        }
        
        if (clippingRatio > MAX_CLIPPING_RATIO) {
            reasons.add("Too much clipping (${(clippingRatio * 100).toInt()}% > ${(MAX_CLIPPING_RATIO * 100).toInt()}%)")
        }
        
        if (!voiceActivityDetected) {
            reasons.add("No voice activity detected")
        }
        
        return if (reasons.isEmpty()) {
            "ACCEPTABLE for recognition"
        } else {
            "REJECTED: ${reasons.joinToString(", ")}"
        }
    }
    
    // Data classes
    data class AudioQualityMetrics(
        val snrDb: Double,
        val voiceEnergy: Double,
        val noiseLevel: Double,
        val clippingRatio: Double,
        val voiceActivityDetected: Boolean,
        val spectralCentroid: Double,
        val zeroCrossingRate: Double,
        val qualityScore: Double,
        val qualityIssues: List<AudioQualityIssue>,
        val recommendations: List<String>,
        val isAcceptableForRecognition: Boolean
    ) {
        companion object {
            fun defaultMetrics() = AudioQualityMetrics(
                snrDb = 0.0,
                voiceEnergy = 0.0,
                noiseLevel = 0.0,
                clippingRatio = 0.0,
                voiceActivityDetected = false,
                spectralCentroid = 0.0,
                zeroCrossingRate = 0.0,
                qualityScore = 0.5,
                qualityIssues = listOf(AudioQualityIssue.ANALYSIS_FAILED),
                recommendations = listOf("Check microphone connection"),
                isAcceptableForRecognition = false
            )
        }
    }
    
    enum class AudioQualityIssue {
        LOW_SNR,
        AUDIO_CLIPPING,
        LOW_VOLUME,
        HIGH_BACKGROUND_NOISE,
        UNCLEAR_SPEECH,
        ANALYSIS_FAILED
    }
}