package com.koreantranslator.service

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Circuit Breaker implementation for preventing cascading failures
 * Monitors service health and temporarily blocks requests to failing services
 */
@Singleton
class CircuitBreakerService @Inject constructor() {
    
    companion object {
        private const val TAG = "CircuitBreaker"
        
        // Default circuit breaker configuration
        private const val DEFAULT_FAILURE_THRESHOLD = 5
        private const val DEFAULT_SUCCESS_THRESHOLD = 3
        private const val DEFAULT_TIMEOUT_MS = 60_000L // 1 minute
        private const val DEFAULT_RETRY_DELAY_MS = 5_000L // 5 seconds
    }

    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()

    /**
     * Circuit Breaker states
     */
    enum class State {
        CLOSED,    // Normal operation
        OPEN,      // Failing - block all requests
        HALF_OPEN  // Testing if service recovered
    }

    /**
     * Individual circuit breaker for a service
     */
    data class CircuitBreaker(
        val serviceName: String,
        val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
        val successThreshold: Int = DEFAULT_SUCCESS_THRESHOLD,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS
    ) {
        @Volatile var state: State = State.CLOSED
        val failureCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val lastFailureTime = AtomicLong(0)
        val totalRequests = AtomicInteger(0)
        val totalFailures = AtomicInteger(0)
        
        fun getHealthPercentage(): Double {
            val total = totalRequests.get()
            return if (total == 0) 100.0 else {
                ((total - totalFailures.get()).toDouble() / total * 100)
            }
        }
    }

    /**
     * Execute a service call with circuit breaker protection
     */
    suspend fun <T> executeWithCircuitBreaker(
        serviceName: String,
        operation: suspend () -> T
    ): T {
        val circuitBreaker = getOrCreateCircuitBreaker(serviceName)
        
        when (circuitBreaker.state) {
            State.OPEN -> {
                if (shouldAttemptReset(circuitBreaker)) {
                    circuitBreaker.state = State.HALF_OPEN
                    Log.i(TAG, "$serviceName: Circuit breaker moving to HALF_OPEN")
                } else {
                    throw CircuitBreakerOpenException("Circuit breaker is OPEN for $serviceName")
                }
            }
            State.HALF_OPEN -> {
                // Allow limited requests to test if service recovered
            }
            State.CLOSED -> {
                // Normal operation
            }
        }

        circuitBreaker.totalRequests.incrementAndGet()
        
        return try {
            val result = operation()
            onSuccess(circuitBreaker)
            result
        } catch (e: Exception) {
            onFailure(circuitBreaker, e)
            throw e
        }
    }

    /**
     * Check if service is available (circuit breaker is not OPEN)
     */
    fun isServiceAvailable(serviceName: String): Boolean {
        val circuitBreaker = circuitBreakers[serviceName] ?: return true
        return when (circuitBreaker.state) {
            State.CLOSED, State.HALF_OPEN -> true
            State.OPEN -> shouldAttemptReset(circuitBreaker)
        }
    }

    /**
     * Get circuit breaker health metrics
     */
    fun getServiceHealth(serviceName: String): ServiceHealth? {
        val circuitBreaker = circuitBreakers[serviceName] ?: return null
        
        return ServiceHealth(
            serviceName = serviceName,
            state = circuitBreaker.state,
            healthPercentage = circuitBreaker.getHealthPercentage(),
            totalRequests = circuitBreaker.totalRequests.get(),
            totalFailures = circuitBreaker.totalFailures.get(),
            currentFailureCount = circuitBreaker.failureCount.get(),
            lastFailureTime = if (circuitBreaker.lastFailureTime.get() == 0L) null else circuitBreaker.lastFailureTime.get()
        )
    }

    /**
     * Get all service health metrics
     */
    fun getAllServiceHealth(): List<ServiceHealth> {
        return circuitBreakers.values.mapNotNull { getServiceHealth(it.serviceName) }
    }

    /**
     * Manually reset a circuit breaker (for admin/testing purposes)
     */
    fun resetCircuitBreaker(serviceName: String) {
        circuitBreakers[serviceName]?.let { circuitBreaker ->
            circuitBreaker.state = State.CLOSED
            circuitBreaker.failureCount.set(0)
            circuitBreaker.successCount.set(0)
            circuitBreaker.lastFailureTime.set(0)
            Log.i(TAG, "$serviceName: Circuit breaker manually reset")
        }
    }

    private fun getOrCreateCircuitBreaker(serviceName: String): CircuitBreaker {
        return circuitBreakers.computeIfAbsent(serviceName) { 
            Log.d(TAG, "Creating circuit breaker for service: $serviceName")
            CircuitBreaker(serviceName) 
        }
    }

    private fun onSuccess(circuitBreaker: CircuitBreaker) {
        when (circuitBreaker.state) {
            State.HALF_OPEN -> {
                val successCount = circuitBreaker.successCount.incrementAndGet()
                if (successCount >= circuitBreaker.successThreshold) {
                    circuitBreaker.state = State.CLOSED
                    circuitBreaker.failureCount.set(0)
                    circuitBreaker.successCount.set(0)
                    Log.i(TAG, "${circuitBreaker.serviceName}: Circuit breaker reset to CLOSED after successful recovery")
                }
            }
            State.CLOSED -> {
                // Reset failure count on successful operations in CLOSED state
                if (circuitBreaker.failureCount.get() > 0) {
                    circuitBreaker.failureCount.set(0)
                }
            }
            State.OPEN -> {
                // This shouldn't happen, but handle it gracefully
                Log.w(TAG, "${circuitBreaker.serviceName}: Success recorded while circuit breaker is OPEN")
            }
        }
    }

    private fun onFailure(circuitBreaker: CircuitBreaker, exception: Exception) {
        circuitBreaker.totalFailures.incrementAndGet()
        circuitBreaker.lastFailureTime.set(System.currentTimeMillis())
        
        when (circuitBreaker.state) {
            State.HALF_OPEN -> {
                // Failure in half-open state immediately opens the circuit
                circuitBreaker.state = State.OPEN
                circuitBreaker.successCount.set(0)
                Log.w(TAG, "${circuitBreaker.serviceName}: Circuit breaker opened due to failure in HALF_OPEN state")
            }
            State.CLOSED -> {
                val failureCount = circuitBreaker.failureCount.incrementAndGet()
                if (failureCount >= circuitBreaker.failureThreshold) {
                    circuitBreaker.state = State.OPEN
                    Log.w(TAG, "${circuitBreaker.serviceName}: Circuit breaker opened after $failureCount failures")
                }
            }
            State.OPEN -> {
                // Already open, just log
                Log.d(TAG, "${circuitBreaker.serviceName}: Additional failure while circuit breaker is OPEN")
            }
        }
        
        Log.w(TAG, "${circuitBreaker.serviceName}: Service failure recorded - ${exception.message}")
    }

    private fun shouldAttemptReset(circuitBreaker: CircuitBreaker): Boolean {
        val timeSinceLastFailure = System.currentTimeMillis() - circuitBreaker.lastFailureTime.get()
        return timeSinceLastFailure >= circuitBreaker.timeoutMs
    }

    /**
     * Service health information
     */
    data class ServiceHealth(
        val serviceName: String,
        val state: State,
        val healthPercentage: Double,
        val totalRequests: Int,
        val totalFailures: Int,
        val currentFailureCount: Int,
        val lastFailureTime: Long?
    )

    /**
     * Exception thrown when circuit breaker is open
     */
    class CircuitBreakerOpenException(message: String) : Exception(message)
}