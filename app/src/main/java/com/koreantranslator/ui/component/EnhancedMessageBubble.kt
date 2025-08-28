package com.koreantranslator.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationMessage
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced message bubble with quality indicators, animations, and user interactions
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EnhancedMessageBubble(
    message: TranslationMessage,
    onLongPress: (() -> Unit)? = null,
    onCorrection: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showActions by remember { mutableStateOf(false) }
    var showCorrectionDialog by remember { mutableStateOf(false) }
    
    // Animation for message appearance
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 300)
    )
    
    val slideOffset by animateIntAsState(
        targetValue = 0,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer(alpha = alpha, translationX = slideOffset.toFloat())
            .combinedClickable(
                onClick = { 
                    showActions = !showActions 
                },
                onLongClick = {
                    // Haptic feedback removed for compatibility
                    showActions = true
                    onLongPress?.invoke()
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = getMessageBackgroundColor(message.translationEngine, message.confidence)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with timestamp and quality indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                TranslationQualityIndicator(
                    engine = message.translationEngine,
                    confidence = message.confidence,
                    wasEnhanced = message.isEnhanced
                )
            }
            
            // Original Korean text
            Text(
                text = message.originalText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            
            // Translation with subtle styling
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Text(
                    text = message.translatedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
            
            // Animated action buttons
            AnimatedVisibility(
                visible = showActions,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    // Copy button
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.translatedText))
                            // Haptic feedback removed for compatibility
                            showActions = false
                        },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Translation",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    
                    // Correction button (if callback provided)
                    if (onCorrection != null) {
                        OutlinedButton(
                            onClick = {
                                showCorrectionDialog = true
                                showActions = false
                            },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Correct Translation",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Correct",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Correction dialog
    if (showCorrectionDialog && onCorrection != null) {
        CorrectionDialog(
            originalText = message.originalText,
            currentTranslation = message.translatedText,
            onCorrection = { correctedTranslation ->
                onCorrection(message.originalText, correctedTranslation)
                showCorrectionDialog = false
            },
            onDismiss = { showCorrectionDialog = false }
        )
    }
}

/**
 * Dialog for user corrections
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionDialog(
    originalText: String,
    currentTranslation: String,
    onCorrection: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var correctedText by remember { mutableStateOf(currentTranslation) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Correct Translation")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Original Korean:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Corrected English:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = correctedText,
                    onValueChange = { correctedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter correct translation") },
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCorrection(correctedText.trim()) },
                enabled = correctedText.trim().isNotEmpty() && correctedText.trim() != currentTranslation
            ) {
                Text("Save Correction")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helper functions

private fun getMessageBackgroundColor(engine: TranslationEngine, confidence: Float): Color {
    val alpha = when {
        confidence > 0.9f -> 0.05f
        confidence > 0.7f -> 0.03f
        else -> 0.02f
    }
    
    return when (engine) {
        TranslationEngine.GEMINI_FLASH -> Color(0xFFFFD700).copy(alpha = alpha)
        TranslationEngine.ML_KIT -> Color(0xFF4CAF50).copy(alpha = alpha)
        TranslationEngine.HYBRID -> Color(0xFF2196F3).copy(alpha = alpha)
    }
}

private fun formatTimestamp(timestamp: Date): String {
    val now = Date()
    val diff = now.time - timestamp.time
    
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(timestamp)
    }
}