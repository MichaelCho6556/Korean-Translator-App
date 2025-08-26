package com.koreantranslator.di

import android.content.Context
import androidx.room.Room
import com.koreantranslator.database.AppDatabase
import com.koreantranslator.database.TranslationDao
import com.koreantranslator.repository.TranslationRepository
import com.koreantranslator.service.*
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
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "korean_translator_database"
        )
        .fallbackToDestructiveMigration()  // Allow database recreation on schema changes
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
    
    // NOTE: Removed TFLiteModelManager, NLPCache, and KoreanNLPEnhancementService
    // These are no longer needed with the simplified pipeline that trusts Soniox output
    
    @Provides
    @Singleton
    fun provideSonioxStreamingService(
        @ApplicationContext context: Context,
        koreanTextValidator: KoreanTextValidator,
        audioQualityAnalyzer: AudioQualityAnalyzer,
        confidenceAwareCorrector: ConfidenceAwareCorrector,
        smartPhraseCache: SmartPhraseCache,
        geminiReconstructionService: GeminiReconstructionService
    ): SonioxStreamingService {
        return SonioxStreamingService(context, koreanTextValidator, audioQualityAnalyzer, confidenceAwareCorrector, smartPhraseCache, geminiReconstructionService)
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
        networkStateMonitor: NetworkStateMonitor,
        translationCacheManager: TranslationCacheManager,
        circuitBreakerService: CircuitBreakerService,
        @ApplicationContext context: Context
    ): TranslationManager {
        return TranslationManager(
            geminiTranslator,
            mlKitTranslator,
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
    
}