package com.koreantranslator.database

import androidx.room.*
import com.koreantranslator.model.TranslationMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    
    @Query("SELECT * FROM translation_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<TranslationMessage>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: TranslationMessage)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<TranslationMessage>)
    
    @Update
    suspend fun updateMessage(message: TranslationMessage)
    
    @Query("SELECT * FROM translation_messages WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveMessage(): TranslationMessage?
    
    @Query("UPDATE translation_messages SET isActive = 0 WHERE id = :messageId")
    suspend fun setMessageInactive(messageId: String)
    
    @Query("DELETE FROM translation_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
    
    @Query("DELETE FROM translation_messages")
    suspend fun clearAllMessages()
    
    @Query("SELECT * FROM translation_messages WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp ASC")
    suspend fun getMessagesByDateRange(startDate: Long, endDate: Long): List<TranslationMessage>
    
    @Query("SELECT * FROM translation_messages WHERE originalText LIKE :query OR translatedText LIKE :query ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<TranslationMessage>
    
    @Query("SELECT COUNT(*) FROM translation_messages")
    suspend fun getMessageCount(): Int
    
    @Query("DELETE FROM translation_messages WHERE timestamp < :cutoffTime")
    suspend fun deleteMessagesOlderThan(cutoffTime: Long): Int
    
    @Query("SELECT * FROM translation_messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentMessage(): TranslationMessage?
    
    @Query("UPDATE translation_messages SET isActive = 1 WHERE id = :messageId")
    suspend fun setMessageActive(messageId: String)
}