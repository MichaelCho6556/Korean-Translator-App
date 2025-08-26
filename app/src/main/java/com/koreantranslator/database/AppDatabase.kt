package com.koreantranslator.database

import androidx.room.*
import com.koreantranslator.model.TranslationMessage
import java.util.*

@Database(
    entities = [TranslationMessage::class],
    version = 3,  // Incremented version for database indexes
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}