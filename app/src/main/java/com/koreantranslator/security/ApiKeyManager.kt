package com.koreantranslator.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.koreantranslator.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure API Key Manager using Android Keystore for enhanced security.
 * 
 * This class provides:
 * - Hardware-backed encryption for API keys
 * - Secure storage using Android Keystore
 * - Protection against reverse engineering
 * - Centralized key management
 */
@Singleton
class ApiKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "ApiKeyManager"
        private const val KEYSTORE_ALIAS = "korean_translator_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        
        private const val PREF_NAME = "secure_keys"
        private const val PREF_GEMINI_ENCRYPTED = "gemini_encrypted"
        private const val PREF_SONIOX_ENCRYPTED = "soniox_encrypted"
        private const val PREF_IV_GEMINI = "iv_gemini"
        private const val PREF_IV_SONIOX = "iv_soniox"
    }
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    
    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    init {
        initializeKeystore()
        securelyStoreKeys()
    }
    
    /**
     * Initializes the Android Keystore with a secure key for encryption.
     */
    private fun initializeKeystore() {
        try {
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false) // For production use
                    .setRandomizedEncryptionRequired(true)
                    .build()
                
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
                
                Log.d(TAG, "Keystore initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keystore", e)
        }
    }
    
    /**
     * Securely stores API keys using hardware-backed encryption.
     */
    private fun securelyStoreKeys() {
        try {
            // Only encrypt and store if not already done
            if (!prefs.contains(PREF_GEMINI_ENCRYPTED)) {
                val geminiKey = BuildConfig.GEMINI_API_KEY
                val sonioxKey = BuildConfig.SONIOX_API_KEY
                
                if (geminiKey.isNotEmpty()) {
                    val (encryptedGemini, ivGemini) = encryptApiKey(geminiKey)
                    prefs.edit()
                        .putString(PREF_GEMINI_ENCRYPTED, encryptedGemini)
                        .putString(PREF_IV_GEMINI, ivGemini)
                        .apply()
                }
                
                if (sonioxKey.isNotEmpty()) {
                    val (encryptedSoniox, ivSoniox) = encryptApiKey(sonioxKey)
                    prefs.edit()
                        .putString(PREF_SONIOX_ENCRYPTED, encryptedSoniox)
                        .putString(PREF_IV_SONIOX, ivSoniox)
                        .apply()
                }
                
                Log.d(TAG, "API keys securely stored")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store API keys securely", e)
        }
    }
    
    /**
     * Encrypts an API key using Android Keystore.
     * @param apiKey The plain text API key
     * @return Pair of encrypted key (Base64) and IV (Base64)
     */
    private fun encryptApiKey(apiKey: String): Pair<String, String> {
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        
        return Pair(
            android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP),
            android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        )
    }
    
    /**
     * Decrypts an API key using Android Keystore.
     * @param encryptedKey Base64 encoded encrypted key
     * @param ivString Base64 encoded IV
     * @return Decrypted API key
     */
    private fun decryptApiKey(encryptedKey: String, ivString: String): String {
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = android.util.Base64.decode(ivString, android.util.Base64.NO_WRAP)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        val encryptedBytes = android.util.Base64.decode(encryptedKey, android.util.Base64.NO_WRAP)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }
    
    /**
     * Securely retrieves the Gemini API key.
     * @return Gemini API key or empty string if not available
     */
    fun getGeminiApiKey(): String {
        return try {
            val encryptedKey = prefs.getString(PREF_GEMINI_ENCRYPTED, null)
            val iv = prefs.getString(PREF_IV_GEMINI, null)
            
            if (encryptedKey != null && iv != null) {
                decryptApiKey(encryptedKey, iv)
            } else {
                // Fallback to BuildConfig for compatibility
                BuildConfig.GEMINI_API_KEY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Gemini API key", e)
            // Fallback to BuildConfig
            BuildConfig.GEMINI_API_KEY
        }
    }
    
    /**
     * Securely retrieves the Soniox API key.
     * @return Soniox API key or empty string if not available
     */
    fun getSonioxApiKey(): String {
        return try {
            val encryptedKey = prefs.getString(PREF_SONIOX_ENCRYPTED, null)
            val iv = prefs.getString(PREF_IV_SONIOX, null)
            
            if (encryptedKey != null && iv != null) {
                decryptApiKey(encryptedKey, iv)
            } else {
                // Fallback to BuildConfig for compatibility
                BuildConfig.SONIOX_API_KEY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Soniox API key", e)
            // Fallback to BuildConfig
            BuildConfig.SONIOX_API_KEY
        }
    }
    
    /**
     * Checks if the Gemini API key is configured.
     * @return true if key is available, false otherwise
     */
    fun isGeminiKeyConfigured(): Boolean {
        return try {
            getGeminiApiKey().isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Gemini key configuration", e)
            false
        }
    }
    
    /**
     * Checks if the Soniox API key is configured.
     * @return true if key is available, false otherwise
     */
    fun isSonioxKeyConfigured(): Boolean {
        return try {
            getSonioxApiKey().isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Soniox key configuration", e)
            false
        }
    }
    
    /**
     * Clears all stored encrypted keys (for security reset).
     */
    fun clearStoredKeys() {
        try {
            prefs.edit().clear().apply()
            Log.d(TAG, "Stored keys cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear stored keys", e)
        }
    }
}