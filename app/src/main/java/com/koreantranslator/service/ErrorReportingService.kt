package com.koreantranslator.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.koreantranslator.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Professional error reporting and logging service
 * Collects, categorizes, and reports errors for debugging and monitoring
 */
@Singleton
class ErrorReportingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkStateMonitor: NetworkStateMonitor
) {
    
    companion object {
        private const val TAG = "ErrorReporting"
        private const val ERROR_LOG_FILE = "error_log.json"
        private const val MAX_LOG_ENTRIES = 1000
        private const val MAX_LOG_FILE_SIZE_MB = 5
    }

    private val errorChannel = Channel<ErrorReport>(capacity = 100)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    data class ErrorReport(
        val timestamp: Long = System.currentTimeMillis(),
        val severity: Severity,
        val category: Category,
        val title: String,
        val message: String,
        val stackTrace: String? = null,
        val context: Map<String, Any> = emptyMap(),
        val userActions: List<String> = emptyList(),
        val deviceInfo: DeviceInfo = DeviceInfo.current(),
        val appInfo: AppInfo = AppInfo.current(),
        val networkInfo: NetworkInfo? = null
    )

    enum class Severity {
        LOW,      // Minor issues, degraded performance
        MEDIUM,   // Feature failures, recoverable errors  
        HIGH,     // Service failures, data loss
        CRITICAL  // App crashes, security issues
    }

    enum class Category {
        TRANSLATION,    // Translation service errors
        NETWORK,        // Network and API errors
        AUDIO,          // Audio recording and processing errors
        DATABASE,       // Database operation errors
        SECURITY,       // Security and permission errors
        PERFORMANCE,    // Performance and memory issues
        UI,             // User interface errors
        UNKNOWN         // Uncategorized errors
    }

    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val locale: String,
        val timeZone: String
    ) {
        companion object {
            fun current() = DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                locale = Locale.getDefault().toString(),
                timeZone = TimeZone.getDefault().id
            )
        }
    }

    data class AppInfo(
        val versionName: String,
        val versionCode: String,
        val isDebug: Boolean,
        val buildTime: String
    ) {
        companion object {
            fun current() = AppInfo(
                versionName = BuildConfig.VERSION_NAME ?: "unknown",
                versionCode = BuildConfig.VERSION_CODE.toString(),
                isDebug = BuildConfig.DEBUG,
                buildTime = BuildConfig.BUILD_TYPE
            )
        }
    }

    data class NetworkInfo(
        val isConnected: Boolean,
        val networkType: String,
        val signalStrength: String
    )

    init {
        startErrorProcessing()
    }

    private fun startErrorProcessing() {
        serviceScope.launch {
            errorChannel.receiveAsFlow().collect { errorReport ->
                processErrorReport(errorReport)
            }
        }
    }

    /**
     * Report an error with automatic categorization
     */
    fun reportError(
        severity: Severity,
        title: String,
        message: String,
        throwable: Throwable? = null,
        category: Category = Category.UNKNOWN,
        context: Map<String, Any> = emptyMap(),
        userActions: List<String> = emptyList()
    ) {
        val networkState = networkStateMonitor.networkState.value
        val networkInfo = NetworkInfo(
            isConnected = networkState.isConnected,
            networkType = networkState.networkType.name,
            signalStrength = networkState.signalStrength.name
        )

        val errorReport = ErrorReport(
            severity = severity,
            category = category,
            title = title,
            message = message,
            stackTrace = throwable?.stackTraceToString(),
            context = context,
            userActions = userActions,
            networkInfo = networkInfo
        )

        // Non-blocking error reporting
        try {
            errorChannel.trySend(errorReport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue error report", e)
        }
    }

    /**
     * Report a translation service error
     */
    fun reportTranslationError(
        title: String,
        message: String,
        throwable: Throwable? = null,
        koreanText: String? = null,
        translationEngine: String? = null
    ) {
        val context = mutableMapOf<String, Any>()
        koreanText?.let { context["korean_text"] = it.take(100) }
        translationEngine?.let { context["translation_engine"] = it }

        reportError(
            severity = Severity.MEDIUM,
            category = Category.TRANSLATION,
            title = title,
            message = message,
            throwable = throwable,
            context = context
        )
    }

    /**
     * Report a network error
     */
    fun reportNetworkError(
        title: String,
        message: String,
        throwable: Throwable? = null,
        url: String? = null,
        statusCode: Int? = null
    ) {
        val context = mutableMapOf<String, Any>()
        url?.let { context["url"] = it }
        statusCode?.let { context["status_code"] = it }

        reportError(
            severity = Severity.MEDIUM,
            category = Category.NETWORK,
            title = title,
            message = message,
            throwable = throwable,
            context = context
        )
    }

    /**
     * Report an audio processing error
     */
    fun reportAudioError(
        title: String,
        message: String,
        throwable: Throwable? = null,
        sampleRate: Int? = null,
        audioFormat: String? = null
    ) {
        val context = mutableMapOf<String, Any>()
        sampleRate?.let { context["sample_rate"] = it }
        audioFormat?.let { context["audio_format"] = it }

        reportError(
            severity = Severity.HIGH,
            category = Category.AUDIO,
            title = title,
            message = message,
            throwable = throwable,
            context = context
        )
    }

    private suspend fun processErrorReport(errorReport: ErrorReport) {
        try {
            // Log immediately for development
            when (errorReport.severity) {
                Severity.CRITICAL -> Log.e(TAG, "CRITICAL: ${errorReport.title} - ${errorReport.message}", 
                    errorReport.stackTrace?.let { Exception(it) })
                Severity.HIGH -> Log.e(TAG, "HIGH: ${errorReport.title} - ${errorReport.message}")
                Severity.MEDIUM -> Log.w(TAG, "MEDIUM: ${errorReport.title} - ${errorReport.message}")
                Severity.LOW -> Log.i(TAG, "LOW: ${errorReport.title} - ${errorReport.message}")
            }

            // Save to local storage for offline capability
            saveErrorToFile(errorReport)

            // In production, you would send to a crash reporting service here
            // if (networkStateMonitor.isNetworkSuitableForApiCalls()) {
            //     sendToRemoteService(errorReport)
            // }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process error report", e)
        }
    }

    private suspend fun saveErrorToFile(errorReport: ErrorReport) {
        try {
            val errorFile = File(context.filesDir, ERROR_LOG_FILE)
            
            // Check file size and rotate if necessary
            if (errorFile.exists() && errorFile.length() > MAX_LOG_FILE_SIZE_MB * 1024 * 1024) {
                rotateLogFile(errorFile)
            }

            val errorJson = errorReportToJson(errorReport)
            
            FileWriter(errorFile, true).use { writer ->
                writer.appendLine(errorJson.toString())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save error to file", e)
        }
    }

    private fun rotateLogFile(errorFile: File) {
        try {
            val backupFile = File(context.filesDir, "${ERROR_LOG_FILE}.backup")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            errorFile.renameTo(backupFile)
            Log.i(TAG, "Error log rotated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    private fun errorReportToJson(errorReport: ErrorReport): JSONObject {
        return JSONObject().apply {
            put("timestamp", errorReport.timestamp)
            put("severity", errorReport.severity.name)
            put("category", errorReport.category.name)
            put("title", errorReport.title)
            put("message", errorReport.message)
            errorReport.stackTrace?.let { put("stack_trace", it) }
            
            if (errorReport.context.isNotEmpty()) {
                put("context", JSONObject(errorReport.context))
            }
            
            if (errorReport.userActions.isNotEmpty()) {
                put("user_actions", errorReport.userActions)
            }

            put("device_info", JSONObject().apply {
                put("manufacturer", errorReport.deviceInfo.manufacturer)
                put("model", errorReport.deviceInfo.model)
                put("android_version", errorReport.deviceInfo.androidVersion)
                put("api_level", errorReport.deviceInfo.apiLevel)
                put("locale", errorReport.deviceInfo.locale)
                put("timezone", errorReport.deviceInfo.timeZone)
            })

            put("app_info", JSONObject().apply {
                put("version_name", errorReport.appInfo.versionName)
                put("version_code", errorReport.appInfo.versionCode)
                put("is_debug", errorReport.appInfo.isDebug)
                put("build_time", errorReport.appInfo.buildTime)
            })

            errorReport.networkInfo?.let { networkInfo ->
                put("network_info", JSONObject().apply {
                    put("is_connected", networkInfo.isConnected)
                    put("network_type", networkInfo.networkType)
                    put("signal_strength", networkInfo.signalStrength)
                })
            }
        }
    }

    /**
     * Get error statistics for monitoring
     */
    suspend fun getErrorStatistics(): ErrorStatistics {
        return withContext(Dispatchers.IO) {
            try {
                val errorFile = File(context.filesDir, ERROR_LOG_FILE)
                if (!errorFile.exists()) {
                    return@withContext ErrorStatistics()
                }

                val lines = errorFile.readLines()
                val errors = lines.mapNotNull { line ->
                    try {
                        val json = JSONObject(line)
                        val severity = Severity.valueOf(json.getString("severity"))
                        val category = Category.valueOf(json.getString("category"))
                        val timestamp = json.getLong("timestamp")
                        Triple(severity, category, timestamp)
                    } catch (e: Exception) {
                        null
                    }
                }

                val now = System.currentTimeMillis()
                val last24Hours = now - 24 * 60 * 60 * 1000
                val last7Days = now - 7 * 24 * 60 * 60 * 1000

                val recentErrors = errors.filter { it.third >= last24Hours }
                val weeklyErrors = errors.filter { it.third >= last7Days }

                ErrorStatistics(
                    totalErrors = errors.size,
                    errorsLast24Hours = recentErrors.size,
                    errorsLast7Days = weeklyErrors.size,
                    criticalErrors = errors.count { it.first == Severity.CRITICAL },
                    networkErrors = errors.count { it.second == Category.NETWORK },
                    translationErrors = errors.count { it.second == Category.TRANSLATION },
                    audioErrors = errors.count { it.second == Category.AUDIO }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get error statistics", e)
                ErrorStatistics()
            }
        }
    }

    data class ErrorStatistics(
        val totalErrors: Int = 0,
        val errorsLast24Hours: Int = 0,
        val errorsLast7Days: Int = 0,
        val criticalErrors: Int = 0,
        val networkErrors: Int = 0,
        val translationErrors: Int = 0,
        val audioErrors: Int = 0
    )

    fun cleanup() {
        serviceScope.cancel()
        errorChannel.close()
    }
}