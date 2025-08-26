package com.koreantranslator.nlp

import android.util.Log
import android.util.LruCache
import com.koreantranslator.service.KoreanNLPEnhancementService
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache for NLP enhancement results
 * 
 * Provides efficient caching of processed text to avoid redundant ML inference.
 * Uses LRU (Least Recently Used) eviction policy with TTL (Time To Live) support.
 */
@Singleton
class NLPCache @Inject constructor() {
    
    companion object {
        private const val TAG = "NLPCache"
        
        // Cache configuration
        private const val MAX_CACHE_SIZE_MB = 10 // Maximum cache size in MB
        private const val MAX_ENTRIES = 500 // Maximum number of cached entries
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 minutes default TTL
        
        // Cache statistics
        private var hitCount = 0
        private var missCount = 0
        private var evictionCount = 0
    }
    
    /**
     * Cache entry with metadata
     */
    private data class CacheEntry(
        val result: KoreanNLPEnhancementService.EnhancementResult,
        val timestamp: Long,
        val ttl: Long,
        val accessCount: Int = 0,
        val size: Int // Approximate size in bytes
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > ttl
        }
        
        fun withAccess(): CacheEntry {
            return copy(accessCount = accessCount + 1)
        }
    }
    
    // LRU cache implementation
    private val cache = object : LruCache<String, CacheEntry>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: CacheEntry): Int {
            // Return size in entries (1 per entry)
            // Could be modified to return actual byte size if needed
            return 1
        }
        
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: CacheEntry,
            newValue: CacheEntry?
        ) {
            if (evicted) {
                evictionCount++
                Log.d(TAG, "Cache entry evicted: ${key.take(16)}... " +
                        "(accessed ${oldValue.accessCount} times)")
            }
        }
    }
    
    // Secondary cache for hash collisions (unlikely but possible)
    private val collisionMap = mutableMapOf<String, MutableList<Pair<String, CacheEntry>>>()
    
    /**
     * Get cached result if available and not expired
     */
    fun get(text: String): KoreanNLPEnhancementService.EnhancementResult? {
        val key = generateKey(text)
        
        synchronized(cache) {
            val entry = cache.get(key)
            
            if (entry != null) {
                // Check if expired
                if (entry.isExpired()) {
                    cache.remove(key)
                    missCount++
                    Log.d(TAG, "Cache miss (expired): ${text.take(30)}...")
                    return null
                }
                
                // Update access count and timestamp
                cache.put(key, entry.withAccess())
                hitCount++
                
                val hitRate = if (hitCount + missCount > 0) {
                    (hitCount * 100.0 / (hitCount + missCount)).toInt()
                } else 0
                
                Log.d(TAG, "Cache hit: ${text.take(30)}... (Hit rate: $hitRate%)")
                return entry.result
            }
            
            // Check collision map as fallback
            collisionMap[key]?.let { collisions ->
                for ((originalText, collisionEntry) in collisions) {
                    if (originalText == text && !collisionEntry.isExpired()) {
                        hitCount++
                        Log.d(TAG, "Cache hit (collision map): ${text.take(30)}...")
                        return collisionEntry.result
                    }
                }
            }
        }
        
        missCount++
        Log.d(TAG, "Cache miss: ${text.take(30)}...")
        return null
    }
    
    /**
     * Store result in cache
     */
    fun put(
        text: String,
        result: KoreanNLPEnhancementService.EnhancementResult,
        ttl: Long = DEFAULT_TTL_MS
    ) {
        val key = generateKey(text)
        val size = estimateSize(result)
        
        // Check if we're within size limits
        if (size > MAX_CACHE_SIZE_MB * 1024 * 1024) {
            Log.w(TAG, "Result too large for cache: ${size / 1024}KB")
            return
        }
        
        val entry = CacheEntry(
            result = result,
            timestamp = System.currentTimeMillis(),
            ttl = ttl,
            size = size
        )
        
        synchronized(cache) {
            // Check for existing entry with same key but different text (collision)
            val existing = cache.get(key)
            if (existing != null && existing.result.originalText != text) {
                // Handle collision
                handleCollision(key, text, entry)
            } else {
                cache.put(key, entry)
                Log.d(TAG, "Cached result: ${text.take(30)}... " +
                        "(Size: ${size / 1024}KB, TTL: ${ttl / 1000}s)")
            }
        }
    }
    
    /**
     * Handle hash collision by storing in secondary map
     */
    private fun handleCollision(key: String, text: String, entry: CacheEntry) {
        Log.w(TAG, "Hash collision detected for key: ${key.take(16)}...")
        
        val collisions = collisionMap.getOrPut(key) { mutableListOf() }
        
        // Remove expired entries from collision list
        collisions.removeAll { (_, e) -> e.isExpired() }
        
        // Add new entry
        collisions.add(Pair(text, entry))
        
        // Limit collision list size
        if (collisions.size > 5) {
            collisions.removeAt(0) // Remove oldest
        }
    }
    
    /**
     * Generate cache key from text
     * Uses MD5 for consistent, fast hashing
     */
    private fun generateKey(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(text.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hash
            text.hashCode().toString()
        }
    }
    
    /**
     * Estimate size of cached result in bytes
     */
    private fun estimateSize(result: KoreanNLPEnhancementService.EnhancementResult): Int {
        // Rough estimation: original text + enhanced text + metadata
        return result.originalText.length * 2 + // UTF-16 chars
                result.enhancedText.length * 2 +
                100 // Metadata overhead
    }
    
    /**
     * Clear expired entries from cache
     */
    fun clearExpired() {
        synchronized(cache) {
            val snapshot = cache.snapshot()
            var clearedCount = 0
            
            for ((key, entry) in snapshot) {
                if (entry.isExpired()) {
                    cache.remove(key)
                    clearedCount++
                }
            }
            
            // Clear expired collision entries
            collisionMap.values.forEach { collisions ->
                collisions.removeAll { (_, entry) -> entry.isExpired() }
            }
            collisionMap.entries.removeAll { it.value.isEmpty() }
            
            if (clearedCount > 0) {
                Log.i(TAG, "Cleared $clearedCount expired cache entries")
            }
        }
    }
    
    /**
     * Clear all cache entries
     */
    fun clear() {
        synchronized(cache) {
            cache.evictAll()
            collisionMap.clear()
            Log.i(TAG, "Cache cleared")
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getStatistics(): CacheStatistics {
        synchronized(cache) {
            val snapshot = cache.snapshot()
            val totalSize = snapshot.values.sumOf { it.size }
            val avgAccessCount = if (snapshot.isNotEmpty()) {
                snapshot.values.sumOf { it.accessCount } / snapshot.size
            } else 0
            
            return CacheStatistics(
                entryCount = snapshot.size,
                totalSizeBytes = totalSize,
                hitCount = hitCount,
                missCount = missCount,
                evictionCount = evictionCount,
                hitRate = if (hitCount + missCount > 0) {
                    hitCount.toFloat() / (hitCount + missCount)
                } else 0f,
                averageAccessCount = avgAccessCount,
                collisionCount = collisionMap.size
            )
        }
    }
    
    /**
     * Warm up cache with common phrases
     */
    fun warmUp(commonPhrases: List<String>) {
        // Pre-populate cache with common phrases
        // This would typically be called during app initialization
        // with frequently used Korean phrases
        
        Log.d(TAG, "Warming up cache with ${commonPhrases.size} common phrases")
        
        // Note: Actual warming would require processing these phrases
        // through the NLP service first, which should be done asynchronously
    }
    
    /**
     * Cache statistics
     */
    data class CacheStatistics(
        val entryCount: Int,
        val totalSizeBytes: Int,
        val hitCount: Int,
        val missCount: Int,
        val evictionCount: Int,
        val hitRate: Float,
        val averageAccessCount: Int,
        val collisionCount: Int
    ) {
        fun toLogString(): String {
            return "Cache Stats - " +
                    "Entries: $entryCount, " +
                    "Size: ${totalSizeBytes / 1024}KB, " +
                    "Hit rate: ${(hitRate * 100).toInt()}%, " +
                    "Hits: $hitCount, " +
                    "Misses: $missCount, " +
                    "Evictions: $evictionCount"
        }
    }
}