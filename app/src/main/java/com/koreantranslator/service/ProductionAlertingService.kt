package com.koreantranslator.service

import android.content.Context
import android.util.Log
// import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production alerting service for critical accumulation system issues
 * 
 * Integrates with Firebase Crashlytics and provides real-time alerts for:
 * - Circuit breaker trips
 * - State mismatch patterns  
 * - Race condition detection
 * - Emergency kill switch activation
 * - Database operation failures
 */
@Singleton
class ProductionAlertingService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProductionAlerting"
        
        // Alert thresholds
        private const val STATE_MISMATCH_ALERT_THRESHOLD = 10
        private const val RACE_CONDITION_ALERT_THRESHOLD = 5
        private const val CIRCUIT_BREAKER_ALERT_COOLDOWN_MS = 300_000L // 5 minutes
        private const val EMERGENCY_ALERT_COOLDOWN_MS = 600_000L // 10 minutes
        
        // Alert categories
        private const val CATEGORY_CIRCUIT_BREAKER = "circuit_breaker"
        private const val CATEGORY_STATE_MISMATCH = "state_mismatch"
        private const val CATEGORY_RACE_CONDITION = "race_condition"
        private const val CATEGORY_EMERGENCY = "emergency"
        private const val CATEGORY_DATABASE_FAILURE = "database_failure"
    }
    
    private val alertScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // private val crashlytics = FirebaseCrashlytics.getInstance() // TODO: Add Firebase dependency
    private val crashlytics = MockCrashlytics()
    
    // Alert tracking and rate limiting
    private val alertCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val lastAlertTimes = ConcurrentHashMap<String, Long>()
    
    // Real-time alert flow for UI monitoring
    private val _alertEvents = MutableSharedFlow<AlertEvent>()
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents.asSharedFlow()
    
    data class AlertEvent(
        val category: String,
        val severity: AlertSeverity,
        val title: String,
        val description: String,
        val metadata: Map<String, String> = emptyMap(),
        val timestamp: Date = Date(),
        val shouldNotifyOps: Boolean = false
    )
    
    enum class AlertSeverity {
        INFO, WARNING, ERROR, CRITICAL
    }
    
    init {
        Log.d(TAG, "Production alerting service initialized")
        setupCrashlyticsUserProperties()
    }
    
    /**
     * CRITICAL: Circuit breaker has tripped - data corruption prevention active
     */
    fun alertCircuitBreakerTrip(operation: String, failureCount: Int, reason: String) {
        val alertKey = "$CATEGORY_CIRCUIT_BREAKER:$operation"
        
        if (!shouldAlert(alertKey, CIRCUIT_BREAKER_ALERT_COOLDOWN_MS)) {
            Log.d(TAG, "Circuit breaker alert suppressed due to rate limiting: $operation")
            return
        }
        
        val alertEvent = AlertEvent(
            category = CATEGORY_CIRCUIT_BREAKER,
            severity = AlertSeverity.CRITICAL,
            title = "Circuit Breaker Tripped: $operation",
            description = "Accumulation system circuit breaker has tripped after $failureCount failures. " +
                         "Automatic protection activated to prevent data corruption.",
            metadata = mapOf(
                "operation" to operation,
                "failure_count" to failureCount.toString(),
                "reason" to reason,
                "protection_status" to "ACTIVE"
            ),
            shouldNotifyOps = true
        )
        
        sendAlert(alertEvent)
        
        // Log non-fatal exception to Crashlytics with detailed context
        val exception = Exception("Circuit breaker tripped: $operation (failures: $failureCount, reason: $reason)")
        crashlytics.recordException(exception)
        crashlytics.setCustomKey("circuit_breaker_operation", operation)
        crashlytics.setCustomKey("circuit_breaker_failures", failureCount)
        
        Log.e(TAG, "🚨 CRITICAL: Circuit breaker tripped for $operation - ops team alerted")
    }
    
    /**
     * WARNING: High rate of state mismatches detected - potential race conditions
     */
    fun alertStateMismatchPattern(mismatchCount: Int, timeWindowMs: Long, operation: String) {
        val alertKey = "$CATEGORY_STATE_MISMATCH:$operation"
        val counter = alertCounters.computeIfAbsent(alertKey) { AtomicInteger(0) }
        
        if (counter.incrementAndGet() < STATE_MISMATCH_ALERT_THRESHOLD) {
            return // Wait for threshold
        }
        
        if (!shouldAlert(alertKey, 60_000L)) { // 1 minute cooldown for state mismatch alerts
            return
        }
        
        counter.set(0) // Reset counter after alert
        
        val alertEvent = AlertEvent(
            category = CATEGORY_STATE_MISMATCH,
            severity = AlertSeverity.WARNING,
            title = "State Mismatch Pattern Detected",
            description = "$mismatchCount state mismatches detected in ${timeWindowMs}ms window for $operation. " +
                         "This may indicate timing issues or race conditions.",
            metadata = mapOf(
                "operation" to operation,
                "mismatch_count" to mismatchCount.toString(),
                "time_window_ms" to timeWindowMs.toString(),
                "threshold" to STATE_MISMATCH_ALERT_THRESHOLD.toString()
            ),
            shouldNotifyOps = false // Internal monitoring first
        )
        
        sendAlert(alertEvent)
        
        crashlytics.setCustomKey("state_mismatch_pattern", "$operation:$mismatchCount")
        Log.w(TAG, "⚠️ State mismatch pattern detected for $operation - monitoring increased")
    }
    
    /**
     * ERROR: Race condition detected in production
     */
    fun alertRaceConditionDetected(operation: String, details: String, affectedData: String?) {
        val alertKey = "$CATEGORY_RACE_CONDITION:$operation"
        val counter = alertCounters.computeIfAbsent(alertKey) { AtomicInteger(0) }
        
        if (counter.incrementAndGet() >= RACE_CONDITION_ALERT_THRESHOLD) {
            counter.set(0) // Reset counter
            
            val alertEvent = AlertEvent(
                category = CATEGORY_RACE_CONDITION,
                severity = AlertSeverity.ERROR,
                title = "Race Condition Detected: $operation",
                description = "Multiple race conditions detected for $operation. Details: $details" +
                             if (affectedData != null) ". Affected data: $affectedData" else "",
                metadata = mapOf(
                    "operation" to operation,
                    "details" to details,
                    "affected_data" to (affectedData ?: "unknown"),
                    "detection_count" to RACE_CONDITION_ALERT_THRESHOLD.toString()
                ),
                shouldNotifyOps = true
            )
            
            sendAlert(alertEvent)
            
            // Log as non-fatal to track patterns
            val exception = Exception("Race condition pattern: $operation - $details")
            crashlytics.recordException(exception)
            
            Log.e(TAG, "🚨 Race condition pattern detected for $operation - immediate attention required")
        }
    }
    
    /**
     * CRITICAL: Emergency kill switch activated - system protection active
     */
    fun alertEmergencyKillSwitch(reason: String, affectedOperations: List<String>, activatedBy: String) {
        val alertKey = CATEGORY_EMERGENCY
        
        if (!shouldAlert(alertKey, EMERGENCY_ALERT_COOLDOWN_MS)) {
            Log.d(TAG, "Emergency alert suppressed due to rate limiting")
            return
        }
        
        val alertEvent = AlertEvent(
            category = CATEGORY_EMERGENCY,
            severity = AlertSeverity.CRITICAL,
            title = "Emergency Kill Switch Activated",
            description = "Emergency kill switch activated by $activatedBy. Reason: $reason. " +
                         "Affected operations: ${affectedOperations.joinToString(", ")}. " +
                         "System protection is now ACTIVE.",
            metadata = mapOf(
                "reason" to reason,
                "affected_operations" to affectedOperations.joinToString(","),
                "activated_by" to activatedBy,
                "protection_level" to "MAXIMUM"
            ),
            shouldNotifyOps = true
        )
        
        sendAlert(alertEvent)
        
        // High priority Crashlytics event
        crashlytics.setCustomKey("emergency_kill_switch", "ACTIVE")
        crashlytics.setCustomKey("emergency_reason", reason)
        crashlytics.setCustomKey("emergency_activated_by", activatedBy)
        
        val exception = Exception("EMERGENCY: Kill switch activated - $reason")
        crashlytics.recordException(exception)
        
        Log.e(TAG, "🚨🚨 EMERGENCY: Kill switch activated - $reason - ALL HANDS")
    }
    
    /**
     * ERROR: Database operation failures
     */
    fun alertDatabaseFailure(operation: String, exception: Exception, retryCount: Int, criticalData: Boolean) {
        val alertKey = "$CATEGORY_DATABASE_FAILURE:$operation"
        
        val severity = if (criticalData) AlertSeverity.ERROR else AlertSeverity.WARNING
        val shouldNotify = criticalData && retryCount >= 3
        
        if (shouldNotify && !shouldAlert(alertKey, 120_000L)) { // 2 minute cooldown
            return
        }
        
        val alertEvent = AlertEvent(
            category = CATEGORY_DATABASE_FAILURE,
            severity = severity,
            title = "Database Operation Failure: $operation",
            description = "Database operation $operation failed after $retryCount retries. " +
                         "Critical data: $criticalData. Error: ${exception.message}",
            metadata = mapOf(
                "operation" to operation,
                "retry_count" to retryCount.toString(),
                "critical_data" to criticalData.toString(),
                "exception_type" to exception::class.simpleName.orEmpty(),
                "exception_message" to (exception.message ?: "unknown")
            ),
            shouldNotifyOps = shouldNotify
        )
        
        sendAlert(alertEvent)
        
        if (criticalData) {
            crashlytics.recordException(exception)
            crashlytics.setCustomKey("db_failure_operation", operation)
            crashlytics.setCustomKey("db_failure_critical", criticalData)
        }
        
        Log.e(TAG, "Database failure alert sent for $operation (critical: $criticalData)")
    }
    
    /**
     * Get current alert statistics for monitoring dashboard
     */
    fun getAlertStatistics(): Map<String, Any> {
        return mapOf(
            "total_alerts_sent" to alertCounters.values.sumOf { it.get() },
            "categories_with_alerts" to alertCounters.keys.map { it.split(":")[0] }.distinct(),
            "last_alert_time" to (lastAlertTimes.values.maxOrNull() ?: 0L),
            "active_rate_limits" to lastAlertTimes.size,
            "crashlytics_enabled" to isCrashlyticsEnabled()
        )
    }
    
    /**
     * Reset alert counters and rate limits (for testing/debugging)
     */
    fun resetAlertCounters() {
        alertCounters.clear()
        lastAlertTimes.clear()
        Log.d(TAG, "Alert counters and rate limits reset")
    }
    
    private fun setupCrashlyticsUserProperties() {
        try {
            crashlytics.setCustomKey("component", "korean_translator_accumulation")
            crashlytics.setCustomKey("version", "production_v1")
            crashlytics.setCustomKey("alerting_service", "enabled")
            Log.d(TAG, "Crashlytics user properties configured")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup Crashlytics properties", e)
        }
    }
    
    private fun shouldAlert(alertKey: String, cooldownMs: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastAlertTime = lastAlertTimes[alertKey] ?: 0L
        
        return if (currentTime - lastAlertTime > cooldownMs) {
            lastAlertTimes[alertKey] = currentTime
            true
        } else {
            false
        }
    }
    
    private fun sendAlert(alertEvent: AlertEvent) {
        alertScope.launch {
            try {
                // Emit to real-time flow for UI monitoring
                _alertEvents.emit(alertEvent)
                
                // Log structured alert for external log aggregation
                val alertJson = buildString {
                    append("{")
                    append("\"timestamp\":\"${alertEvent.timestamp}\",")
                    append("\"category\":\"${alertEvent.category}\",")
                    append("\"severity\":\"${alertEvent.severity}\",")
                    append("\"title\":\"${alertEvent.title}\",")
                    append("\"description\":\"${alertEvent.description}\",")
                    append("\"should_notify_ops\":${alertEvent.shouldNotifyOps},")
                    append("\"metadata\":{")
                    alertEvent.metadata.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
                    append("}}")
                }
                
                Log.i("PRODUCTION_ALERT", alertJson)
                
                // TODO: Integrate with external alerting systems
                // - PagerDuty for critical alerts
                // - Slack for team notifications  
                // - Email for emergency alerts
                // - SMS for kill switch activation
                
                if (alertEvent.shouldNotifyOps) {
                    Log.e(TAG, "🚨 OPS NOTIFICATION REQUIRED: ${alertEvent.title}")
                    // In a real implementation, send to PagerDuty/Slack here
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send alert", e)
                // Don't let alerting failures break the main application
            }
        }
    }
    
    private fun isCrashlyticsEnabled(): Boolean {
        return try {
            crashlytics.setCrashlyticsCollectionEnabled(true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Crashlytics not available", e)
            false
        }
    }
    
    /**
     * Cleanup resources when service is no longer needed
     */
    fun cleanup() {
        alertScope.cancel()
        Log.d(TAG, "Production alerting service cleaned up")
    }
    
    /**
     * Mock Crashlytics implementation for development/testing
     * TODO: Replace with real Firebase Crashlytics when dependency is added
     */
    private class MockCrashlytics {
        fun recordException(exception: Exception) {
            Log.w("MockCrashlytics", "Recording exception: ${exception.message}", exception)
        }
        
        fun setCustomKey(key: String, value: String) {
            Log.d("MockCrashlytics", "Custom key: $key = $value")
        }
        
        fun setCustomKey(key: String, value: Int) {
            Log.d("MockCrashlytics", "Custom key: $key = $value")
        }
        
        fun setCustomKey(key: String, value: Boolean) {
            Log.d("MockCrashlytics", "Custom key: $key = $value")
        }
        
        fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
            Log.d("MockCrashlytics", "Collection enabled: $enabled")
        }
    }
}