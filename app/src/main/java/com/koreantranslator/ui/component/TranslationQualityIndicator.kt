package com.koreantranslator.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreantranslator.model.TranslationEngine

/**
 * Visual indicator showing translation quality and engine used
 */
@Composable
fun TranslationQualityIndicator(
    engine: TranslationEngine,
    confidence: Float,
    fromCache: Boolean = false,
    processingTimeMs: Long = 0L,
    wasEnhanced: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = getEngineContainerColor(engine, confidence),
        contentColor = getEngineContentColor(engine, confidence)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Engine icon with animation for enhanced translations
            val scale by animateFloatAsState(
                targetValue = if (wasEnhanced) 1.1f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
            
            Icon(
                imageVector = getEngineIcon(engine, fromCache),
                contentDescription = "${engine.name} Translation",
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
                tint = getEngineIconColor(engine, confidence)
            )
            
            // Quality label
            Text(
                text = getEngineLabel(engine, fromCache, wasEnhanced),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (engine == TranslationEngine.GEMINI_FLASH) FontWeight.Medium else FontWeight.Normal
            )
            
            // Confidence indicator (only for non-cached translations)
            if (!fromCache && confidence > 0f) {
                ConfidenceIndicator(
                    confidence = confidence,
                    showPercentage = engine == TranslationEngine.GEMINI_FLASH
                )
            }
            
            // Processing time for performance awareness (debug/development)
            if (processingTimeMs > 0L && processingTimeMs < 5000L) {
                Text(
                    text = "${processingTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Confidence indicator with visual progress
 */
@Composable
private fun ConfidenceIndicator(
    confidence: Float,
    showPercentage: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Visual confidence bar
        LinearProgressIndicator(
            progress = confidence,
            modifier = Modifier
                .width(24.dp)
                .height(2.dp),
            color = getConfidenceColor(confidence),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        
        // Percentage for premium translations
        if (showPercentage) {
            Text(
                text = "${(confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Loading indicator for translation states
 */
@Composable
fun TranslationLoadingIndicator(
    isTranslating: Boolean,
    isEnhancing: Boolean,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = isTranslating || isEnhancing,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Animated loading spinner
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (isEnhancing) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.secondary
                )
                
                // Status text with animation
                Text(
                    text = when {
                        isEnhancing -> "Enhancing with Gemini AI..."
                        isTranslating -> "Translating..."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Premium indicator for Gemini enhancement
                if (isEnhancing) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Enhancement",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Error card for translation failures
 */
@Composable
fun TranslationErrorCard(
    originalText: String,
    errorMessage: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Translation Error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Translation Failed",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            if (originalText.isNotEmpty()) {
                Text(
                    text = "Original text: \"${originalText.take(50)}${if (originalText.length > 50) "..." else ""}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                
                if (canRetry) {
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// Helper functions for styling

private fun getEngineIcon(engine: TranslationEngine, fromCache: Boolean): ImageVector {
    return when {
        fromCache -> Icons.Default.Storage
        engine == TranslationEngine.GEMINI_FLASH -> Icons.Default.AutoAwesome
        engine == TranslationEngine.ML_KIT -> Icons.Default.OfflineBolt
        engine == TranslationEngine.HYBRID -> Icons.Default.Sync
        else -> Icons.Default.Translate
    }
}

private fun getEngineIconColor(engine: TranslationEngine, confidence: Float): Color {
    return when (engine) {
        TranslationEngine.GEMINI_FLASH -> Color(0xFFFFD700) // Gold for premium
        TranslationEngine.ML_KIT -> Color(0xFF4CAF50) // Green for offline
        TranslationEngine.HYBRID -> Color(0xFF2196F3) // Blue for hybrid
    }
}

private fun getEngineContainerColor(engine: TranslationEngine, confidence: Float): Color {
    return when (engine) {
        TranslationEngine.GEMINI_FLASH -> Color(0x1AFFD700) // Subtle gold background
        TranslationEngine.ML_KIT -> Color(0x1A4CAF50) // Subtle green background
        TranslationEngine.HYBRID -> Color(0x1A2196F3) // Subtle blue background
    }
}

private fun getEngineContentColor(engine: TranslationEngine, confidence: Float): Color {
    return when (engine) {
        TranslationEngine.GEMINI_FLASH -> Color(0xFF8B6914) // Dark gold text
        TranslationEngine.ML_KIT -> Color(0xFF2E7D32) // Dark green text  
        TranslationEngine.HYBRID -> Color(0xFF1565C0) // Dark blue text
    }
}

private fun getEngineLabel(engine: TranslationEngine, fromCache: Boolean, wasEnhanced: Boolean): String {
    return when {
        fromCache -> "Cached"
        wasEnhanced -> "Enhanced"
        engine == TranslationEngine.GEMINI_FLASH -> "Premium"
        engine == TranslationEngine.ML_KIT -> "Offline"
        engine == TranslationEngine.HYBRID -> "Hybrid"
        else -> "Standard"
    }
}

private fun getConfidenceColor(confidence: Float): Color {
    return when {
        confidence > 0.9f -> Color(0xFF4CAF50) // Green for high confidence
        confidence > 0.7f -> Color(0xFFFF9800) // Orange for medium confidence
        else -> Color(0xFFF44336) // Red for low confidence
    }
}