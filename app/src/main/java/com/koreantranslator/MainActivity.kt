package com.koreantranslator

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.koreantranslator.service.MLKitTranslationService
import com.koreantranslator.ui.screen.TranslationScreen
import com.koreantranslator.ui.theme.KoreanTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var mlKitTranslationService: MLKitTranslationService
    
    @Inject
    lateinit var translationService: com.koreantranslator.service.TranslationService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize translation services on startup
        initializeTranslationServices()
        
        setContent {
            KoreanTranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TranslationScreen()
                }
            }
        }
    }
    
    private fun initializeTranslationServices() {
        lifecycleScope.launch {
            try {
                // Initialize comprehensive cache system
                Log.d("MainActivity", "Initializing translation cache system...")
                translationService.initializeCacheSystem()
                
                // Check if ML Kit model is already downloaded
                val isDownloaded = mlKitTranslationService.isModelDownloaded()
                
                if (!isDownloaded) {
                    Log.d("MainActivity", "ML Kit Korean model not found, downloading...")
                    Toast.makeText(
                        this@MainActivity, 
                        "Downloading Korean translation model...",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    val success = mlKitTranslationService.downloadLanguageModel()
                    
                    if (success) {
                        Log.d("MainActivity", "ML Kit Korean model downloaded successfully")
                        Toast.makeText(
                            this@MainActivity,
                            "Translation model ready",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.e("MainActivity", "Failed to download ML Kit Korean model")
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to download translation model. Will use online translation only.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Log.d("MainActivity", "ML Kit Korean model already available")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing ML Kit model", e)
            }
        }
    }
}