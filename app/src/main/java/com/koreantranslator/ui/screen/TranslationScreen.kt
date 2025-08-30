package com.koreantranslator.ui.screen

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.koreantranslator.BuildConfig
import com.koreantranslator.ui.component.MessageBubble
import kotlinx.coroutines.launch
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun TranslationScreen(viewModel: OptimizedTranslationViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Debug logging for state changes
    LaunchedEffect(uiState.messages.size) {
        Log.d("TranslationScreen", "UI State updated - Message count: ${uiState.messages.size}")
        Log.d("TranslationScreen", "Is recording: ${uiState.isRecording}")
        Log.d("TranslationScreen", "Is translating: ${uiState.isTranslating}")
        Log.d("TranslationScreen", "Is enhancing: ${uiState.isEnhancing}")
        Log.d("TranslationScreen", "Error: ${uiState.error}")
        if (uiState.messages.isNotEmpty()) {
            Log.d("TranslationScreen", "Latest message: ${uiState.messages.last().translatedText}")
        }
    }

    // Handle microphone permission
    val microphonePermissionState =
            rememberPermissionState(permission = Manifest.permission.RECORD_AUDIO)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // App title and status
        Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Korean Translator", style = MaterialTheme.typography.headlineMedium)

            // Network status indicator
            Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                            if (uiState.isOnline) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor =
                            if (uiState.isOnline) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                            imageVector =
                                    if (uiState.isOnline) Icons.Default.Psychology
                                    else Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                    )
                    Text(
                            text = if (uiState.isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // Messages list
        LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages) { message -> MessageBubble(message = message) }

            // Show active message box during recording or when there's text
            val hasText = uiState.accumulatedKoreanText.isNotBlank() || 
                         uiState.currentPartialText?.isNotBlank() == true
            val shouldShowActiveBox = hasText || uiState.isRecording || uiState.isAccumulatingMessage
            if (shouldShowActiveBox) {
                item(key = "active_message_box") {
                    // DISPLAY: Combined accumulated text + current partial text
                    val koreanText = buildString {
                        if (uiState.accumulatedKoreanText.isNotBlank()) {
                            append(uiState.accumulatedKoreanText)
                        }
                        if (uiState.currentPartialText?.isNotBlank() == true) {
                            if (uiState.accumulatedKoreanText.isNotBlank()) append(" ")
                            append(uiState.currentPartialText)
                        }
                    }
                    
                    Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                            .copy(alpha = 0.7f)
                                    ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            // DISPLAY: Korean text content
                            if (koreanText.isNotBlank()) {
                                Text(
                                        text = koreanText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                )
                            }
                            
                            // DISPLAY: English translation
                            val englishText = buildString {
                                if (uiState.accumulatedEnglishText.isNotBlank()) {
                                    append(uiState.accumulatedEnglishText)
                                }
                                if (uiState.currentPartialTranslation?.isNotBlank() == true) {
                                    if (uiState.accumulatedEnglishText.isNotBlank()) append(" ")
                                    append(uiState.currentPartialTranslation)
                                }
                            }
                            if (englishText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                        text = englishText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(uiState.messages.size) {
            if (uiState.messages.isNotEmpty()) {
                coroutineScope.launch { listState.animateScrollToItem(uiState.messages.size - 1) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recording controls and conversation management
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main recording controls
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                        onClick = {
                            if (uiState.isRecording) {
                                viewModel.stopRecording()
                            } else {
                                // Check permission before starting recording
                                if (microphonePermissionState.status.isGranted) {
                                    viewModel.startRecording()
                                } else {
                                    // Request permission
                                    microphonePermissionState.launchPermissionRequest()
                                }
                            }
                        },
                        containerColor =
                                if (uiState.isRecording) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                            imageVector =
                                    if (uiState.isRecording) Icons.Default.MicOff
                                    else Icons.Default.Mic,
                            contentDescription =
                                    if (uiState.isRecording) "Stop Recording" else "Start Recording"
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                            text =
                                    when {
                                        !microphonePermissionState.status.isGranted ->
                                                "Microphone permission needed"
                                        uiState.isRecording && uiState.isAccumulatingMessage -> 
                                                "Recording (Accumulating to existing message)"
                                        uiState.isRecording -> "Recording (New message)"
                                        uiState.isAccumulatingMessage -> "Ready to continue conversation"
                                        else -> "Tap to start recording"
                                    },
                            style = MaterialTheme.typography.bodyMedium
                    )
                    if (uiState.isRecording) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                                text = "Tap again to stop",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Conversation management buttons
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                // Clear Text button (SIMPLIFIED - manual control only)
                if ((uiState.currentPartialText != null || uiState.isAccumulatingMessage) && !uiState.isRecording) {
                    OutlinedButton(
                            onClick = { viewModel.clearAllText() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                    ) {
                        Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Text",
                                modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Text")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                // New Message button (only show when accumulating)
                if (uiState.isAccumulatingMessage && !uiState.isRecording) {
                    OutlinedButton(
                            onClick = { viewModel.forceNewMessage() }
                    ) {
                        Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "New Message",
                                modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Message")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                // New Conversation button
                OutlinedButton(
                        onClick = { viewModel.clearConversation() },
                        enabled = !uiState.isRecording
                ) {
                    Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "New Conversation",
                            modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Conversation")
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Clear History button
                OutlinedButton(
                        onClick = { viewModel.clearAllMessages() },
                        colors =
                                ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                ),
                        enabled = uiState.messages.isNotEmpty()
                ) {
                    Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear History",
                            modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear History")
                }

                // Debug: Test Gemini API button
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                            onClick = { viewModel.testGeminiApi() },
                            colors =
                                    ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.tertiary
                                    )
                    ) {
                        Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "Test API",
                                modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Gemini")
                    }
                }
            }
        }

        // Show permission explanation when needed
        if (!microphonePermissionState.status.isGranted) {
            Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                            text = "Microphone Access Required",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                            text =
                                    "This app needs microphone permission to capture Korean speech for translation. Tap the microphone button to grant permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        uiState.error?.let { errorMessage ->
            Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                            )
            ) {
                Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
