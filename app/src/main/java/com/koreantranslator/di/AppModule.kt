package com.koreantranslator.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.koreantranslator.database.AppDatabase
import com.koreantranslator.database.TranslationDao
import com.koreantranslator.repository.TranslationRepository
import com.koreantranslator.service.*
import com.koreantranslator.nlp.KoreanNLPService
import com.koreantranslator.util.KoreanTextValidator
import com.koreantranslator.util.MLKitModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    // Migration from version 3 to 4: Add isActive field
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE translation_messages ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "korean_translator_database"
        )
        .addMigrations(MIGRATION_3_4)  // Proper migration preserves user data
        .build()
    }
    
    @Provides
    fun provideTranslationDao(database: AppDatabase): TranslationDao {
        return database.translationDao()
    }
    
    @Provides
    @Singleton
    fun provideConfidenceAwareCorrector(): ConfidenceAwareCorrector {
        return ConfidenceAwareCorrector()
    }
    
    @Provides
    @Singleton
    fun provideSmartPhraseCache(
        @ApplicationContext context: Context
    ): SmartPhraseCache {
        return SmartPhraseCache(context)
    }
    
    @Provides
    @Singleton
    fun provideKoreanTextValidator(): KoreanTextValidator {
        return KoreanTextValidator()
    }
    
    // KoreanNLPService is needed to fix speech recognition spacing issues
    @Provides
    @Singleton
    fun provideKoreanDictionaryLoader(
        @ApplicationContext context: Context
    ): com.koreantranslator.nlp.KoreanDictionaryLoader {
        return com.koreantranslator.nlp.KoreanDictionaryLoader(context)
    }

    @Provides
    @Singleton
    fun provideKoreanNLPService(
        @ApplicationContext context: Context,
        koreanDictionaryLoader: com.koreantranslator.nlp.KoreanDictionaryLoader
    ): com.koreantranslator.nlp.KoreanNLPService {
        return com.koreantranslator.nlp.KoreanNLPService(context, koreanDictionaryLoader)
    }
    
    @Provides
    @Singleton
    fun provideSonioxStreamingService(
        @ApplicationContext context: Context,
        koreanTextValidator: KoreanTextValidator,
        audioQualityAnalyzer: AudioQualityAnalyzer,
        confidenceAwareCorrector: ConfidenceAwareCorrector,
        smartPhraseCache: SmartPhraseCache,
        geminiReconstructionService: GeminiReconstructionService,
        koreanNLPService: KoreanNLPService
    ): SonioxStreamingService {
        return SonioxStreamingService(context, koreanTextValidator, audioQualityAnalyzer, confidenceAwareCorrector, smartPhraseCache, geminiReconstructionService, koreanNLPService)
    }
    
    @Provides
    @Singleton
    fun provideMLKitTranslationService(): MLKitTranslationService {
        return MLKitTranslationService()
    }
    
    @Provides
    @Singleton
    fun provideNetworkStateMonitor(@ApplicationContext context: Context): NetworkStateMonitor {
        return NetworkStateMonitor(context)
    }
    
    @Provides
    @Singleton
    fun provideCircuitBreakerService(): CircuitBreakerService {
        return CircuitBreakerService()
    }
    
    @Provides
    @Singleton
    fun provideErrorReportingService(
        @ApplicationContext context: Context,
        networkStateMonitor: NetworkStateMonitor
    ): ErrorReportingService {
        return ErrorReportingService(context, networkStateMonitor)
    }
    
    @Provides
    @Singleton
    fun provideGeminiApiService(): GeminiApiService {
        return GeminiApiService()
    }
    
    @Provides
    @Singleton
    fun provideKoreanLinguisticService(): KoreanLinguisticService {
        return KoreanLinguisticService()
    }
    
    @Provides
    @Singleton
    fun provideGeminiTranslatorImpl(
        geminiApiService: GeminiApiService
    ): GeminiTranslatorImpl {
        return GeminiTranslatorImpl(geminiApiService)
    }
    
    @Provides
    @Singleton
    fun provideMLKitTranslatorImpl(
        mlKitTranslationService: MLKitTranslationService
    ): MLKitTranslatorImpl {
        return MLKitTranslatorImpl(mlKitTranslationService)
    }
    
    @Provides
    @Singleton
    fun provideTranslationManager(
        geminiTranslator: GeminiTranslatorImpl,
        mlKitTranslator: MLKitTranslatorImpl,
        koreanNLPService: com.koreantranslator.nlp.KoreanNLPService,
        networkStateMonitor: NetworkStateMonitor,
        translationCacheManager: TranslationCacheManager,
        circuitBreakerService: CircuitBreakerService,
        @ApplicationContext context: Context
    ): TranslationManager {
        return TranslationManager(
            geminiTranslator,
            mlKitTranslator,
            koreanNLPService,
            networkStateMonitor,
            translationCacheManager,
            circuitBreakerService,
            context
        )
    }
    
    @Provides
    @Singleton
    fun provideTranslationMetrics(
        @ApplicationContext context: Context
    ): TranslationMetrics {
        return TranslationMetrics(context)
    }
    
    @Provides
    @Singleton
    fun provideTranslationService(
        mlKitTranslationService: MLKitTranslationService,
        geminiApiService: GeminiApiService,
        koreanLinguisticService: KoreanLinguisticService,
        translationMetrics: TranslationMetrics,
        translationCacheManager: TranslationCacheManager,
        networkStateMonitor: NetworkStateMonitor,
        circuitBreakerService: CircuitBreakerService,
        errorReportingService: ErrorReportingService,
        @ApplicationContext context: Context
    ): TranslationService {
        return TranslationService(
            mlKitTranslationService,
            geminiApiService,
            koreanLinguisticService,
            translationMetrics,
            translationCacheManager,
            networkStateMonitor,
            circuitBreakerService,
            errorReportingService,
            context
        )
    }
    
    @Provides
    @Singleton
    fun provideTranslationRepository(
        translationDao: TranslationDao
    ): TranslationRepository {
        return TranslationRepository(translationDao)
    }
    
    @Provides
    @Singleton
    fun provideMLKitModelManager(
        @ApplicationContext context: Context
    ): MLKitModelManager {
        return MLKitModelManager(context)
    }
    
    @Provides
    @Singleton
    fun provideTranslationCacheManager(
        @ApplicationContext context: Context
    ): TranslationCacheManager {
        return TranslationCacheManager(context)
    }
    
    @Provides
    @Singleton
    fun provideAudioQualityAnalyzer(): AudioQualityAnalyzer {
        return AudioQualityAnalyzer()
    }
    
    @Provides
    @Singleton
    fun provideGeminiReconstructionService(
        geminiApiService: GeminiApiService
    ): GeminiReconstructionService {
        return GeminiReconstructionService(geminiApiService)
    }
    
    @Provides
    @Singleton
    fun provideProductionAlertingService(
        @ApplicationContext context: Context
    ): ProductionAlertingService {
        return ProductionAlertingService(context)
    }
    
}