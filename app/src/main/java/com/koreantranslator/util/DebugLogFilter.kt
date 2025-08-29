package com.koreantranslator.util

import android.util.Log
import com.koreantranslator.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime debug log filter that suppresses excessive Firebase ML Kit and TensorFlow logging
 * This provides cleaner logcat output during development while preserving critical information
 */
object DebugLogFilter {
    
    private const val TAG = "DebugLogFilter"
    
    // Statistics tracking
    private var filteredLogsCount = AtomicLong(0)
    private var allowedLogsCount = AtomicLong(0)
    
    private var isInitialized = false
    
    /**
     * Initialize the debug log filter system
     * Call this early in Application.onCreate() for debug builds
     */
    fun initialize() {
        if (!BuildConfig.DEBUG) {
            Log.d(TAG, "Skipping debug log filter in release build")
            return
        }
        
        try {
            Log.i(TAG, "Initializing debug log filter for cleaner logcat output...")
            
            // Set system properties to reduce Firebase Transport verbosity
            System.setProperty("firebase.logging.level", "WARN")
            System.setProperty("firebase.transport.logging.level", "ERROR")
            System.setProperty("com.google.android.datatransport.runtime.logging.level", "ERROR")
            
            // Reduce TensorFlow Lite verbosity
            System.setProperty("tflite.logging.level", "ERROR")
            System.setProperty("xnnpack.logging.level", "NONE")
            
            isInitialized = true
            
            Log.i(TAG, "✓ Debug log filter initialized successfully")
            Log.i(TAG, "Firebase Transport logs will be suppressed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize debug log filter", e)
        }
    }
    
    /**
     * Custom Log.d that applies filtering
     */
    fun d(tag: String, msg: String) {
        if (shouldAllowLog(tag, msg)) {
            Log.d(tag, msg)
            allowedLogsCount.incrementAndGet()
        } else {
            filteredLogsCount.incrementAndGet()
        }
    }
    
    /**
     * Custom Log.i that applies filtering
     */
    fun i(tag: String, msg: String) {
        if (shouldAllowLog(tag, msg)) {
            Log.i(tag, msg)
            allowedLogsCount.incrementAndGet()
        } else {
            filteredLogsCount.incrementAndGet()
        }
    }
    
    /**
     * Custom Log.v that applies filtering (verbose logs are more aggressively filtered)
     */
    fun v(tag: String, msg: String) {
        // Verbose logs are heavily filtered - only allow our app's logs
        if (tag.startsWith("com.koreantranslator") || tag in IMPORTANT_TAGS) {
            Log.v(tag, msg)
            allowedLogsCount.incrementAndGet()
        } else {
            filteredLogsCount.incrementAndGet()
        }
    }
    
    /**
     * Custom Log.w that applies minimal filtering (warnings should usually be shown)
     */
    fun w(tag: String, msg: String) {
        if (isFirebaseTransportNoise(tag, msg)) {
            filteredLogsCount.incrementAndGet()
        } else {
            Log.w(tag, msg)
            allowedLogsCount.incrementAndGet()
        }
    }
    
    /**
     * Custom Log.e that never filters (errors should always be shown)
     */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
        allowedLogsCount.incrementAndGet()
    }
    
    /**
     * Important tags that should always be allowed through
     */
    private val IMPORTANT_TAGS = setOf(
        "OptimizedTranslationVM",
        "TranslationManager",
        "TranslationScreen",
        "SonioxStreaming",
        "GeminiApiService",
        "MLKitTranslation",
        "KoreanNLP",
        "KoreanTranslatorApp"
    )
    
    /**
     * Determine if a log should be allowed through the filter
     */
    private fun shouldAllowLog(tag: String, message: String): Boolean {
        // Always allow our app's important logs
        if (tag in IMPORTANT_TAGS || tag.startsWith("com.koreantranslator")) {
            return true
        }
        
        // Use LoggingOptimizer's filtering logic
        return LoggingOptimizer.shouldLogMessage(tag, message)
    }
    
    /**
     * Check if this is Firebase Transport noise that should be completely blocked
     */
    private fun isFirebaseTransportNoise(tag: String, message: String): Boolean {
        val transportTags = setOf("TransportRuntime", "TransportR", "EventStore", "oScheduler")
        
        // Block specific Firebase Transport patterns
        if (transportTags.any { tag.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // Block specific noisy message patterns
        val noisePatterns = setOf(
            "Storing event with priority=VERY_LOW",
            "Upload for context TransportContext",
            "is already scheduled. Returning",
            "FIREBASE_ML_SDK for destination cct"
        )
        
        return noisePatterns.any { pattern -> message.contains(pattern) }
    }
    
    /**
     * Get filtering statistics for debugging
     */
    fun getFilteringStats(): FilteringStats {
        return FilteringStats(
            filteredCount = filteredLogsCount.get(),
            allowedCount = allowedLogsCount.get(),
            isInitialized = isInitialized,
            filteringRatio = if (allowedLogsCount.get() > 0) {
                filteredLogsCount.get().toDouble() / (filteredLogsCount.get() + allowedLogsCount.get())
            } else {
                0.0
            }
        )
    }
    
    /**
     * Reset filtering statistics
     */
    fun resetStats() {
        filteredLogsCount.set(0)
        allowedLogsCount.set(0)
    }
    
    /**
     * Log current filtering statistics
     */
    fun logFilteringStats() {
        if (!isInitialized) return
        
        val stats = getFilteringStats()
        Log.i(TAG, "Debug Log Filtering Stats:")
        Log.i(TAG, "  - Logs filtered: ${stats.filteredCount}")
        Log.i(TAG, "  - Logs allowed: ${stats.allowedCount}")
        Log.i(TAG, "  - Filtering ratio: ${(stats.filteringRatio * 100).toInt()}%")
        
        if (stats.filteredCount > 0) {
            Log.i(TAG, "✓ Successfully filtering noise logs for cleaner debugging")
        }
    }
    
    /**
     * Data class for filtering statistics
     */
    data class FilteringStats(
        val filteredCount: Long,
        val allowedCount: Long,
        val isInitialized: Boolean,
        val filteringRatio: Double
    )
}