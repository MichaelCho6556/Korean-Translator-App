package com.koreantranslator.service

import android.content.Context
import android.util.Log
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production Metrics Service
 * Tracks API usage, costs, performance, and provides alerting
 */
@Singleton
class ProductionMetricsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProductionMetrics"
        
        // Cost configuration (in cents)
        private const val ML_KIT_COST_PER_CALL = 0.0 // Free
        private const val GEMINI_COST_PER_1K_CHARS = 0.01 // $0.0001 per call
        
        // Alert thresholds
        private const val DAILY_COST_ALERT_CENTS = 100 // $1.00
        private const val HOURLY_API_CALL_ALERT = 100
        private const val ERROR_RATE_ALERT_PERCENT = 10
        private const val LATENCY_ALERT_MS = 3000
        
        // Reporting intervals
        private const val METRICS_REPORT_INTERVAL_MS = 60_000L // 1 minute
        private const val COST_CHECK_INTERVAL_MS = 300_000L // 5 minutes
    }
    
    // Metrics storage
    private val apiCallMetrics = APICallMetrics()
    private val performanceMetrics = PerformanceMetrics()
    private val costMetrics = CostMetrics()
    private val errorMetrics = ErrorMetrics()
    
    // Alert system
    private val _alerts = MutableSharedFlow<MetricAlert>()
    val alerts: SharedFlow<MetricAlert> = _alerts.asSharedFlow()
    
    // Monitoring jobs
    private var metricsReportJob: Job? = null
    private var costMonitorJob: Job? = null
    
    init {
        startMetricsReporting()
        startCostMonitoring()
    }
    
    /**
     * API Call Metrics
     */
    class APICallMetrics {
        private val totalCalls = AtomicInteger(0)
        private val callsByEngine = ConcurrentHashMap<TranslationEngine, AtomicInteger>()
        private val callsByHour = ConcurrentHashMap<Int, AtomicInteger>() // Hour of day -> count
        private val lastHourCalls = AtomicInteger(0)
        private var lastHourTimestamp = System.currentTimeMillis()
        
        fun recordCall(engine: TranslationEngine) {
            totalCalls.incrementAndGet()
            callsByEngine.getOrPut(engine) { AtomicInteger(0) }.incrementAndGet()
            
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            callsByHour.getOrPut(hour) { AtomicInteger(0) }.incrementAndGet()
            
            // Track calls in last hour
            val now = System.currentTimeMillis()
            if (now - lastHourTimestamp > 3600_000) {
                lastHourCalls.set(0)
                lastHourTimestamp = now
            }
            lastHourCalls.incrementAndGet()
        }
        
        fun getMetrics() = APIMetricsSnapshot(
            totalCalls = totalCalls.get(),
            mlKitCalls = callsByEngine[TranslationEngine.ML_KIT]?.get() ?: 0,
            geminiCalls = callsByEngine[TranslationEngine.GEMINI_FLASH]?.get() ?: 0,
            hybridCalls = callsByEngine[TranslationEngine.HYBRID]?.get() ?: 0,
            lastHourCalls = lastHourCalls.get(),
            peakHour = callsByHour.maxByOrNull { it.value.get() }?.key ?: 0
        )
    }
    
    /**
     * Performance Metrics
     */
    class PerformanceMetrics {
        private val latencies = mutableListOf<Long>()
        private val p50Latency = AtomicLong(0)
        private val p95Latency = AtomicLong(0)
        private val p99Latency = AtomicLong(0)
        private val maxLatency = AtomicLong(0)
        
        fun recordLatency(latencyMs: Long) {
            synchronized(latencies) {
                latencies.add(latencyMs)
                
                // Keep only last 1000 measurements
                if (latencies.size > 1000) {
                    latencies.removeAt(0)
                }
                
                // Calculate percentiles
                if (latencies.isNotEmpty()) {
                    val sorted = latencies.sorted()
                    p50Latency.set(sorted[sorted.size / 2])
                    p95Latency.set(sorted[(sorted.size * 0.95).toInt()])
                    p99Latency.set(sorted[(sorted.size * 0.99).toInt()])
                    maxLatency.set(sorted.last())
                }
            }
        }
        
        fun getMetrics() = PerformanceMetricsSnapshot(
            p50LatencyMs = p50Latency.get(),
            p95LatencyMs = p95Latency.get(),
            p99LatencyMs = p99Latency.get(),
            maxLatencyMs = maxLatency.get(),
            averageLatencyMs = if (latencies.isNotEmpty()) 
                latencies.average().toLong() else 0
        )
    }
    
    /**
     * Cost Metrics
     */
    class CostMetrics {
        private val dailyCosts = ConcurrentHashMap<String, AtomicInteger>() // Date -> cents
        private val hourlyCosts = ConcurrentHashMap<String, AtomicInteger>() // DateTime -> cents
        private var totalCostCents = AtomicInteger(0)
        
        fun recordCost(engine: TranslationEngine, textLength: Int) {
            val costCents = when (engine) {
                TranslationEngine.ML_KIT -> 0
                TranslationEngine.GEMINI_FLASH, TranslationEngine.HYBRID -> {
                    // Rough estimate based on text length
                    val kiloChars = textLength / 1000.0
                    (kiloChars * GEMINI_COST_PER_1K_CHARS).toInt().coerceAtLeast(1)
                }
            }
            
            if (costCents > 0) {
                totalCostCents.addAndGet(costCents)
                
                val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                dailyCosts.getOrPut(dateKey) { AtomicInteger(0) }.addAndGet(costCents)
                
                val hourKey = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US).format(Date())
                hourlyCosts.getOrPut(hourKey) { AtomicInteger(0) }.addAndGet(costCents)
            }
        }
        
        fun getTodayCostCents(): Int {
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return dailyCosts[dateKey]?.get() ?: 0
        }
        
        fun getThisHourCostCents(): Int {
            val hourKey = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US).format(Date())
            return hourlyCosts[hourKey]?.get() ?: 0
        }
        
        fun getMetrics() = CostMetricsSnapshot(
            totalCostCents = totalCostCents.get(),
            todayCostCents = getTodayCostCents(),
            thisHourCostCents = getThisHourCostCents(),
            projectedDailyCostCents = calculateProjectedDailyCost()
        )
        
        private fun calculateProjectedDailyCost(): Int {
            val now = Calendar.getInstance()
            val minutesIntoDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val todayCost = getTodayCostCents()
            
            return if (minutesIntoDay > 0) {
                (todayCost * 1440 / minutesIntoDay) // Project to full day
            } else todayCost
        }
    }
    
    /**
     * Error Metrics
     */
    class ErrorMetrics {
        private val errorCounts = ConcurrentHashMap<String, AtomicInteger>() // Error type -> count
        private val totalErrors = AtomicInteger(0)
        private val totalRequests = AtomicInteger(0)
        
        fun recordError(errorType: String) {
            totalErrors.incrementAndGet()
            errorCounts.getOrPut(errorType) { AtomicInteger(0) }.incrementAndGet()
        }
        
        fun recordRequest() {
            totalRequests.incrementAndGet()
        }
        
        fun getMetrics() = ErrorMetricsSnapshot(
            totalErrors = totalErrors.get(),
            errorRate = if (totalRequests.get() > 0) 
                (totalErrors.get() * 100.0 / totalRequests.get()) else 0.0,
            topErrors = errorCounts.entries
                .sortedByDescending { it.value.get() }
                .take(5)
                .map { it.key to it.value.get() }
        )
    }
    
    /**
     * Record a translation request
     */
    fun recordTranslation(
        koreanText: String,
        response: TranslationResponse,
        latencyMs: Long,
        error: Exception? = null
    ) {
        // Record API call
        apiCallMetrics.recordCall(response.engine)
        errorMetrics.recordRequest()
        
        // Record performance
        performanceMetrics.recordLatency(latencyMs)
        
        // Record cost
        costMetrics.recordCost(response.engine, koreanText.length)
        
        // Record error if present
        error?.let {
            errorMetrics.recordError(it.javaClass.simpleName)
        }
        
        // Check for alerts
        checkAlerts()
    }
    
    /**
     * Check and trigger alerts
     */
    private fun checkAlerts() {
        val scope = CoroutineScope(Dispatchers.Default)
        
        scope.launch {
            // Check cost alert
            val todayCost = costMetrics.getTodayCostCents()
            if (todayCost > DAILY_COST_ALERT_CENTS) {
                _alerts.emit(MetricAlert(
                    type = AlertType.COST,
                    message = "Daily cost exceeded: $${todayCost / 100.0}",
                    severity = AlertSeverity.HIGH
                ))
            }
            
            // Check API call rate
            val apiMetrics = apiCallMetrics.getMetrics()
            if (apiMetrics.lastHourCalls > HOURLY_API_CALL_ALERT) {
                _alerts.emit(MetricAlert(
                    type = AlertType.API_RATE,
                    message = "High API call rate: ${apiMetrics.lastHourCalls}/hour",
                    severity = AlertSeverity.MEDIUM
                ))
            }
            
            // Check error rate
            val errorMetrics = errorMetrics.getMetrics()
            if (errorMetrics.errorRate > ERROR_RATE_ALERT_PERCENT) {
                _alerts.emit(MetricAlert(
                    type = AlertType.ERROR_RATE,
                    message = "High error rate: ${errorMetrics.errorRate.toInt()}%",
                    severity = AlertSeverity.HIGH
                ))
            }
            
            // Check latency
            val perfMetrics = performanceMetrics.getMetrics()
            if (perfMetrics.p95LatencyMs > LATENCY_ALERT_MS) {
                _alerts.emit(MetricAlert(
                    type = AlertType.LATENCY,
                    message = "High latency: P95=${perfMetrics.p95LatencyMs}ms",
                    severity = AlertSeverity.MEDIUM
                ))
            }
        }
    }
    
    /**
     * Start periodic metrics reporting
     */
    private fun startMetricsReporting() {
        metricsReportJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(METRICS_REPORT_INTERVAL_MS)
                logMetricsReport()
            }
        }
    }
    
    /**
     * Start cost monitoring
     */
    private fun startCostMonitoring() {
        costMonitorJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(COST_CHECK_INTERVAL_MS)
                logCostReport()
            }
        }
    }
    
    /**
     * Log comprehensive metrics report
     */
    private fun logMetricsReport() {
        val apiMetrics = apiCallMetrics.getMetrics()
        val perfMetrics = performanceMetrics.getMetrics()
        val errorMetrics = errorMetrics.getMetrics()
        
        Log.d(TAG, "=== METRICS REPORT ===")
        Log.d(TAG, "API Calls - Total: ${apiMetrics.totalCalls}, Last Hour: ${apiMetrics.lastHourCalls}")
        Log.d(TAG, "By Engine - ML Kit: ${apiMetrics.mlKitCalls}, Gemini: ${apiMetrics.geminiCalls}")
        Log.d(TAG, "Latency - P50: ${perfMetrics.p50LatencyMs}ms, P95: ${perfMetrics.p95LatencyMs}ms")
        Log.d(TAG, "Errors - Rate: ${errorMetrics.errorRate}%, Total: ${errorMetrics.totalErrors}")
        Log.d(TAG, "======================")
    }
    
    /**
     * Log cost report
     */
    private fun logCostReport() {
        val costMetrics = costMetrics.getMetrics()
        
        Log.d(TAG, "=== COST REPORT ===")
        Log.d(TAG, "Today: $${costMetrics.todayCostCents / 100.0}")
        Log.d(TAG, "This Hour: $${costMetrics.thisHourCostCents / 100.0}")
        Log.d(TAG, "Projected Daily: $${costMetrics.projectedDailyCostCents / 100.0}")
        Log.d(TAG, "Total: $${costMetrics.totalCostCents / 100.0}")
        Log.d(TAG, "===================")
    }
    
    /**
     * Get current metrics snapshot
     */
    fun getCurrentMetrics() = MetricsSnapshot(
        api = apiCallMetrics.getMetrics(),
        performance = performanceMetrics.getMetrics(),
        cost = costMetrics.getMetrics(),
        error = errorMetrics.getMetrics(),
        timestamp = System.currentTimeMillis()
    )
    
    /**
     * Export metrics for analysis
     */
    fun exportMetrics(): String {
        val metrics = getCurrentMetrics()
        return buildString {
            appendLine("Translation Metrics Export - ${Date()}")
            appendLine("=====================================")
            appendLine()
            appendLine("API Usage:")
            appendLine("  Total Calls: ${metrics.api.totalCalls}")
            appendLine("  ML Kit: ${metrics.api.mlKitCalls}")
            appendLine("  Gemini: ${metrics.api.geminiCalls}")
            appendLine("  Hybrid: ${metrics.api.hybridCalls}")
            appendLine()
            appendLine("Performance:")
            appendLine("  P50 Latency: ${metrics.performance.p50LatencyMs}ms")
            appendLine("  P95 Latency: ${metrics.performance.p95LatencyMs}ms")
            appendLine("  P99 Latency: ${metrics.performance.p99LatencyMs}ms")
            appendLine()
            appendLine("Cost:")
            appendLine("  Today: $${metrics.cost.todayCostCents / 100.0}")
            appendLine("  Projected: $${metrics.cost.projectedDailyCostCents / 100.0}")
            appendLine("  Total: $${metrics.cost.totalCostCents / 100.0}")
            appendLine()
            appendLine("Errors:")
            appendLine("  Rate: ${metrics.error.errorRate}%")
            appendLine("  Total: ${metrics.error.totalErrors}")
        }
    }
    
    /**
     * Clean up resources
     */
    fun shutdown() {
        metricsReportJob?.cancel()
        costMonitorJob?.cancel()
    }
    
    // Data classes for metrics snapshots
    data class MetricsSnapshot(
        val api: APIMetricsSnapshot,
        val performance: PerformanceMetricsSnapshot,
        val cost: CostMetricsSnapshot,
        val error: ErrorMetricsSnapshot,
        val timestamp: Long
    )
    
    data class APIMetricsSnapshot(
        val totalCalls: Int,
        val mlKitCalls: Int,
        val geminiCalls: Int,
        val hybridCalls: Int,
        val lastHourCalls: Int,
        val peakHour: Int
    )
    
    data class PerformanceMetricsSnapshot(
        val p50LatencyMs: Long,
        val p95LatencyMs: Long,
        val p99LatencyMs: Long,
        val maxLatencyMs: Long,
        val averageLatencyMs: Long
    )
    
    data class CostMetricsSnapshot(
        val totalCostCents: Int,
        val todayCostCents: Int,
        val thisHourCostCents: Int,
        val projectedDailyCostCents: Int
    )
    
    data class ErrorMetricsSnapshot(
        val totalErrors: Int,
        val errorRate: Double,
        val topErrors: List<Pair<String, Int>>
    )
    
    data class MetricAlert(
        val type: AlertType,
        val message: String,
        val severity: AlertSeverity,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class AlertType {
        COST, API_RATE, ERROR_RATE, LATENCY
    }
    
    enum class AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}