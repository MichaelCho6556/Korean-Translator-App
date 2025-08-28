package com.koreantranslator.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreantranslator.model.TranslationEngine
import com.koreantranslator.model.TranslationMessage
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(
    message: TranslationMessage,
    modifier: Modifier = Modifier
) {
    // Active message visual feedback
    val isActiveMessage = message.isActive
    
    // Pulsing animation for active messages
    val pulseScale by animateFloatAsState(
        targetValue = if (isActiveMessage) 1.02f else 1f,
        animationSpec = if (isActiveMessage) {
            infiniteRepeatable(
                animation = tween(800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(300)
        },
        label = "pulse_scale"
    )
    
    val cardColor by animateColorAsState(
        targetValue = when {
            isActiveMessage -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
            message.translationEngine == TranslationEngine.GEMINI_FLASH -> MaterialTheme.colorScheme.primaryContainer
            message.translationEngine == TranslationEngine.HYBRID -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "card_color"
    )
    
    val borderColor by animateColorAsState(
        targetValue = if (isActiveMessage) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(300),
        label = "border_color"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActiveMessage) 4.dp else 2.dp
        ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = if (isActiveMessage) BorderStroke(2.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Active message indicator
            if (isActiveMessage) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Recording",
                        modifier = Modifier.size(8.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Recording...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            // Original Korean text
            Text(
                text = if (message.originalText.isBlank() && isActiveMessage) "Listening..." else message.originalText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (message.originalText.isBlank() && isActiveMessage) 
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Translation with animation
            AnimatedContent(
                targetState = message.translatedText,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith 
                    fadeOut(animationSpec = tween(300))
                },
                label = "translation_text"
            ) { translatedText ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    val displayText = if (translatedText.isBlank() && isActiveMessage) {
                        "Translating..."
                    } else {
                        translatedText
                    }
                    
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (translatedText.isBlank() && isActiveMessage) 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Enhanced indicator with animation
                    AnimatedContent(
                        targetState = message.isEnhanced,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith 
                            fadeOut(animationSpec = tween(300))
                        },
                        label = "enhanced_indicator"
                    ) { isEnhanced ->
                        if (isEnhanced) {
                            Surface(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Enhanced",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Bottom row with engine info, timestamp, and confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Engine indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val (icon, engineName) = when (message.translationEngine) {
                        TranslationEngine.ML_KIT -> Icons.Default.Speed to "Instant"
                        TranslationEngine.GEMINI_FLASH -> Icons.Default.Psychology to "Gemini"
                        TranslationEngine.HYBRID -> Icons.Default.Psychology to "Enhanced"
                    }
                    
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = engineName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                // Timestamp
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                
                // Confidence indicator
                if (message.confidence > 0) {
                    val confidenceColor = when {
                        message.confidence >= 0.9f -> MaterialTheme.colorScheme.primary
                        message.confidence >= 0.7f -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    
                    Text(
                        text = "${(message.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = confidenceColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}