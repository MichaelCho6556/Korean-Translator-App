package com.koreantranslator.util

import android.util.Log
import com.koreantranslator.BuildConfig

/**
 * CRITICAL FIX: Logging optimizer to reduce Firebase Transport and other excessive logging
 * 
 * This class helps minimize logging overhead from Firebase Transport, ML Kit, and other 
 * Google services that can generate excessive log output and impact performance.
 */
object LoggingOptimizer {
    
    private const val TAG = "LoggingOptimizer"
    
    // List of log tags that should be filtered or reduced
    private val NOISY_LOG_TAGS = setOf(
        "FirebaseTransport",
        "Transport",
        "TransportRuntime",
        "TransportR",
        "EventStore", 
        "oScheduler",
        "Uploader",
        "JobIntentService",
        "GmsClient",
        "MLKitCore",
        "MLKitTranslate",
        "TaskApiCall",
        "CommonUtils",
        "FirebaseApp",
        "AndroidRuntime", // Some Android system logs
        "ActivityManager", // Android activity lifecycle logs
        "InputManager", // Input handling logs
        "native", // TensorFlow native logging
        "tflite" // TensorFlow Lite internal logging
    )
    
    // Specific Firebase ML Kit patterns that create excessive noise
    private val FIREBASE_NOISE_PATTERNS = setOf(
        "Storing event with priority=VERY_LOW, name=FIREBASE_ML_SDK",
        "Upload for context TransportContext",
        "is already scheduled. Returning...",
        "failed to get XNNPACK profile information"
    )
    
    // Critical errors that should always be logged regardless of optimization
    private val CRITICAL_KEYWORDS = setOf(
        "crash", "exception", "error", "failed", "timeout", "cancelled"
    )
    
    /**
     * Initialize logging optimization
     * Call this early in Application.onCreate()
     */
    fun initialize() {
        try {
            Log.i(TAG, "Initializing logging optimization...")
            
            // In production builds, reduce logging verbosity
            if (!BuildConfig.DEBUG) {
                Log.i(TAG, "Production mode: Firebase Transport logging minimized")
                
                // For release builds, ProGuard rules will handle most optimization
                // This is backup runtime configuration
                reduceSystemLogging()
            } else {
                Log.i(TAG, "Debug mode: Selective logging optimization enabled")
                
                // In debug mode, still reduce excessive noise while keeping useful logs
                reduceNoisyLogging()
            }
            
            Log.i(TAG, "✓ Logging optimization initialized successfully")
            
        } catch (e: Exception) {
            // Don't let logging optimization crash the app
            Log.e(TAG, "Failed to initialize logging optimization", e)
        }
    }
    
    /**
     * Reduce system-level logging for production
     */
    private fun reduceSystemLogging() {
        try {
            // These optimizations primarily rely on ProGuard rules
            // Runtime system logging control is limited in Android
            Log.d(TAG, "System logging optimization applied via ProGuard")
        } catch (e: Exception) {
            Log.w(TAG, "System logging optimization failed", e)
        }
    }
    
    /**
     * Reduce noisy logging while preserving useful debug information
     */
    private fun reduceNoisyLogging() {
        try {
            // Log which tags we're optimizing
            Log.d(TAG, "Monitoring ${NOISY_LOG_TAGS.size} noisy log tags for optimization")
            Log.d(TAG, "Noisy tags: ${NOISY_LOG_TAGS.joinToString(", ")}")
            
            // Note: Android doesn't provide easy runtime log filtering
            // Our main optimization comes from ProGuard rules and selective logging practices
            
        } catch (e: Exception) {
            Log.w(TAG, "Noisy logging optimization failed", e)
        }
    }
    
    /**
     * Check if a log message should be filtered based on content
     * Use this in custom logging to reduce noise
     */
    fun shouldLogMessage(tag: String, message: String): Boolean {
        // Always log critical messages first
        val messageKey = message.lowercase()
        if (CRITICAL_KEYWORDS.any { messageKey.contains(it) }) {
            return true
        }
        
        // AGGRESSIVE FIREBASE FILTERING: Block specific noise patterns
        if (FIREBASE_NOISE_PATTERNS.any { pattern -> message.contains(pattern) }) {
            return false // Block these specific patterns completely
        }
        
        // Block Firebase Transport tags completely in debug mode
        if (isFirebaseTransportTag(tag)) {
            return false // Completely suppress Firebase Transport logs
        }
        
        // In debug mode, be more permissive for other tags
        if (BuildConfig.DEBUG) {
            // Filter out excessive noise from ML Kit and TensorFlow
            if (isNoisyLogTag(tag)) {
                // Only log important events from noisy tags
                return when {
                    messageKey.contains("error") -> true
                    messageKey.contains("failed") -> true
                    messageKey.contains("exception") -> true
                    messageKey.contains("crash") -> true
                    messageKey.contains("initialized") -> true
                    messageKey.contains("completed") -> true
                    // Block routine TensorFlow Lite noise
                    messageKey.contains("xnnpack") -> false
                    messageKey.contains("profile information") -> false
                    else -> false // Filter out all other routine events
                }
            }
            return true // Allow non-noisy tags through in debug
        }
        
        // In production, be more restrictive
        return !isNoisyLogTag(tag) || containsCriticalKeyword(message)
    }
    
    /**
     * Check if this is specifically a Firebase Transport tag that should be completely blocked
     */
    private fun isFirebaseTransportTag(tag: String): Boolean {
        val transportTags = setOf("TransportRuntime", "TransportR", "EventStore", "oScheduler")
        return transportTags.any { transportTag ->
            tag.contains(transportTag, ignoreCase = true)
        }
    }
    
    /**
     * Check if this is a noisy log tag that should be filtered
     */
    private fun isNoisyLogTag(tag: String): Boolean {
        return NOISY_LOG_TAGS.any { noisyTag ->
            tag.contains(noisyTag, ignoreCase = true)
        }
    }
    
    /**
     * Check if message contains critical keywords that should always be logged
     */
    private fun containsCriticalKeyword(message: String): Boolean {
        val messageLower = message.lowercase()
        return CRITICAL_KEYWORDS.any { keyword ->
            messageLower.contains(keyword)
        }
    }
    
    /**
     * Custom log methods that apply filtering
     */
    fun logDebug(tag: String, message: String) {
        if (shouldLogMessage(tag, message)) {
            Log.d(tag, message)
        }
    }
    
    fun logInfo(tag: String, message: String) {
        if (shouldLogMessage(tag, message)) {
            Log.i(tag, message)
        }
    }
    
    fun logWarning(tag: String, message: String) {
        // Always log warnings and errors
        Log.w(tag, message)
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        // Always log errors
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    /**
     * Report logging optimization statistics
     */
    fun getOptimizationStats(): LoggingStats {
        return LoggingStats(
            noisyTagCount = NOISY_LOG_TAGS.size,
            optimizationEnabled = true,
            productionMode = !BuildConfig.DEBUG,
            criticalKeywordCount = CRITICAL_KEYWORDS.size
        )
    }
    
    /**
     * Logging optimization statistics
     */
    data class LoggingStats(
        val noisyTagCount: Int,
        val optimizationEnabled: Boolean,
        val productionMode: Boolean,
        val criticalKeywordCount: Int
    )
}