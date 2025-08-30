# Alternative Ways to Test and Debug Gemini API

## Option 1: Use Android Studio's Logcat

1. Open Android Studio
2. Run the app from Android Studio (not command line):
   - Click the green play button
   - Select your device/emulator
3. Open Logcat window (View → Tool Windows → Logcat)
4. In the filter box, type: `GeminiAPI-HTTP|GeminiApiService|TranslationService`
5. Test translations and watch the logs

## Option 2: Use Wireless ADB

If USB isn't working, connect over WiFi:

1. Connect phone to same WiFi as computer
2. On phone: Settings → About → Status → IP Address (note this)
3. Enable Developer Options and USB Debugging
4. In terminal:
```
adb tcpip 5555
adb connect [YOUR_PHONE_IP]:5555
```

## Option 3: Add On-Screen Debug Info

We can modify the app to show debug info directly on screen. Add this to `TranslationScreen.kt`:

```kotlin
// In the UI, show which engine was used
Text(
    text = "Engine: ${message.translationEngine}",
    style = MaterialTheme.typography.caption,
    color = Color.Gray
)
```

## Option 4: Use Emulator Instead

1. Open Android Studio
2. Tools → AVD Manager
3. Create/Start an emulator
4. The emulator should automatically connect to ADB

## Option 5: Direct Testing Method

Add a debug button to the app that tests Gemini directly:

In `TranslationScreen.kt`, add:
```kotlin
Button(onClick = { viewModel.testGeminiApi() }) {
    Text("Test Gemini API")
}
```

Then check the logs or add a Toast to show the result.

## What to Look For in Logs

When it's working correctly:
```
GeminiAPI-HTTP: --> POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
GeminiAPI-HTTP: x-goog-api-key: AIza...
GeminiAPI-HTTP: {"contents":[{"parts":[{"text":"..."}]}]}
GeminiAPI-HTTP: <-- 200 OK
TranslationService: ✓ Gemini 2.5 Flash enhanced: "I don't study although I go to the library"
```

Common errors:
- `401`: Invalid API key
- `403`: No access to model
- `404`: Model not found
- `429`: Rate limit exceeded