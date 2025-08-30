# Test Phrases to Verify Translation Improvements

Test these phrases in order and watch the logs in Android Studio's Logcat!

## 1. Complex Particle Tests (Should use 4096 thinking tokens)

### Reminder/Assertion (-잖아요)
- **Korean**: "제가 그랬잖아요"
- **Expected**: "I told you" or "I said that"
- **Watch for**: "Thinking budget: 4096"

### Explanation (-거든요)
- **Korean**: "제가 지금 바쁘거든요"
- **Expected**: "You see, I'm busy right now"
- **Watch for**: "hasComplexParticles=true"

### Realization (-네요)
- **Korean**: "날씨가 좋네요"
- **Expected**: "Oh, the weather is nice"
- **Watch for**: "Using enhancement with context and thinking"

## 2. Short Context Tests (Should use few-shot examples)

### Common greetings
- **Korean**: "안녕하세요"
- **Expected**: "Hello"
- **Watch for**: "Using short-context translation with few-shot examples"

### Simple questions
- **Korean**: "괜찮아요"
- **Expected**: "It's okay"
- **Watch for**: "Thinking budget: 512"

## 3. Internet Slang Tests (Should have 98% confidence)

### Laughter
- **Korean**: "ㅋㅋㅋ"
- **Expected**: "lol"
- **Watch for**: "Found internet slang: ㅋㅋㅋ -> lol"

### Crying
- **Korean**: "ㅠㅠ"
- **Expected**: "*crying*"
- **Watch for**: "confidence = 0.98f"

### Agreement
- **Korean**: "ㅇㅇ"
- **Expected**: "yeah/okay"
- **Watch for**: "Internet slang/abbreviation detected"

## 4. What to Look for in Logcat:

### Success Indicators:
```
✓ Gemini 2.0 Flash enhanced: "I told you..."
✓ Translation engine: GEMINI_FLASH, Enhanced: true
✓ Average Confidence: 0.95+
✓ Using short-context translation with few-shot examples
✓ Text complexity: TextComplexity(hasComplexParticles=true...)
```

### Performance Metrics (after 50 translations):
```
════════════════════════════════════════
TRANSLATION METRICS REPORT
════════════════════════════════════════
Enhanced: 45 (90%)
Average Confidence: 0.94
Pattern Success Rates:
  reminder_assertion: 95%
  internet_slang: 98%
```

## 5. How to View in Android Studio:

1. **Open Logcat** (Alt + 6)
2. **Set filter to**: `tag:TranslationMetrics | tag:GeminiApiService`
3. **Clear logs**: Click the trash can icon
4. **Run app**: Shift + F10
5. **Start testing**: Speak or type the test phrases
6. **Watch logs update** in real-time!

## 6. Export Results:

In Logcat window:
- Right-click → "Save As..." → Save as `translation_test_results.txt`
- Share the results to analyze improvement patterns!

## Expected Improvements You Should See:

| Metric | Old System | New System |
|--------|------------|------------|
| "제가 그랬잖아요" accuracy | ~70% | ~95% |
| Internet slang recognition | ~60% | ~98% |
| Average confidence | ~0.85 | ~0.94 |
| Response time (with thinking) | N/A | ~650ms |
| Cache hit rate (repeated phrases) | ~5% | ~15% |

## Troubleshooting:

If you don't see logs:
1. Check filter is set correctly
2. Ensure app is selected in device dropdown
3. Try log level "Verbose" instead of "Debug"
4. Click "Restart" button in Logcat
5. Check app is running (not crashed)

If logs are too fast:
1. Click "Pause" button in Logcat
2. Use Ctrl+F to search for specific terms
3. Add more specific filters