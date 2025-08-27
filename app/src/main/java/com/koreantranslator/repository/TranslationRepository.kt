package com.koreantranslator.repository

import com.koreantranslator.database.TranslationDao
import com.koreantranslator.model.TranslationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationRepository @Inject constructor(
    private val translationDao: TranslationDao
) {
    companion object {
        private const val TAG = "TranslationRepository"
    }
    
    fun getAllMessages(): Flow<List<TranslationMessage>> {
        return translationDao.getAllMessages()
            .flowOn(Dispatchers.IO) // Ensure flow operations run on IO thread
    }
    
    suspend fun insertMessage(message: TranslationMessage) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            translationDao.insertMessage(message)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inserted message in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert message: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun updateMessage(message: TranslationMessage) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            translationDao.updateMessage(message)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Updated message in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update message: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun getActiveMessage(): TranslationMessage? = withContext(Dispatchers.IO) {
        try {
            return@withContext translationDao.getActiveMessage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active message: ${e.message}", e)
            return@withContext null
        }
    }
    
    suspend fun setMessageInactive(messageId: String) = withContext(Dispatchers.IO) {
        try {
            translationDao.setMessageInactive(messageId)
            Log.d(TAG, "Set message $messageId as inactive")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set message inactive: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            translationDao.deleteMessage(messageId)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Deleted message in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun clearAllMessages() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val count = translationDao.getMessageCount()
            translationDao.clearAllMessages()
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Cleared $count messages in ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear messages: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun getMessagesByDateRange(startDate: Long, endDate: Long): List<TranslationMessage> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val messages = translationDao.getMessagesByDateRange(startDate, endDate)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Retrieved ${messages.size} messages by date range in ${duration}ms")
            return@withContext messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get messages by date range: ${e.message}", e)
            throw e
        }
    }
    
    suspend fun searchMessages(query: String): List<TranslationMessage> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val searchQuery = "%${query.trim()}%"
            val messages = translationDao.searchMessages(searchQuery)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Search for '$query' found ${messages.size} messages in ${duration}ms")
            return@withContext messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search messages: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Batch insert for better performance when importing multiple messages
     * Uses Room's optimized batch insert API for single transaction
     */
    suspend fun insertMessages(messages: List<TranslationMessage>) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            translationDao.insertMessages(messages)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Batch inserted ${messages.size} messages in ${duration}ms (optimized)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch insert messages: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Get database statistics for monitoring
     */
    suspend fun getDatabaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        try {
            val totalMessages = translationDao.getMessageCount()
            return@withContext DatabaseStats(
                totalMessages = totalMessages,
                estimatedSizeKB = totalMessages * 2 // Rough estimate: 2KB per message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get database stats: ${e.message}", e)
            return@withContext DatabaseStats(0, 0)
        }
    }
    
    /**
     * Clean up old messages to manage database size
     * Automatically removes messages older than 30 days
     */
    suspend fun cleanupOldMessages(retentionDays: Int = 30) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            val deletedCount = translationDao.deleteMessagesOlderThan(cutoffTime)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Cleaned up $deletedCount old messages in ${duration}ms (retention: $retentionDays days)")
            return@withContext deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old messages: ${e.message}", e)
            throw e
        }
    }

    data class DatabaseStats(
        val totalMessages: Int,
        val estimatedSizeKB: Int
    )
}