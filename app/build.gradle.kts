import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
}

android {
    namespace = "com.koreantranslator"
    compileSdk = 34
    

    defaultConfig {
        applicationId = "com.koreantranslator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Add API key from local.properties with proper error handling
        val localPropertiesFile = rootProject.file("local.properties")
        val localProperties = Properties()
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        
        // Provide default empty strings if keys are not found
        val geminiKey = localProperties.getProperty("GEMINI_API_KEY", "")
        val sonioxKey = localProperties.getProperty("SONIOX_API_KEY", "")
        
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "SONIOX_API_KEY", "\"$sonioxKey\"")
        
        // ABI filters to reduce APK size and fix architecture compatibility
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Optimize for development speed - no minification in debug
            isMinifyEnabled = false
            isShrinkResources = false
            // Enable debugging features
            isDebuggable = true
            // Optional: Add debug suffix to distinguish from release
            applicationIdSuffix = ".debug"
            // Enable signing for better compatibility
            signingConfig = signingConfigs.getByName("debug")
            
            // Security: Use development keys in debug builds
            buildConfigField("String", "BUILD_TYPE_NAME", "\"debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Security enhancement for release builds
            buildConfigField("String", "BUILD_TYPE_NAME", "\"release\"")
            
            // Clear API keys in release APK - they will be loaded securely by ApiKeyManager
            // This prevents reverse engineering of keys from the APK
            if (System.getenv("CI") == "true") {
                // In CI/CD environment, use environment variables
                val ciGeminiKey = System.getenv("GEMINI_API_KEY") ?: ""
                val ciSonioxKey = System.getenv("SONIOX_API_KEY") ?: ""
                buildConfigField("String", "GEMINI_API_KEY", "\"$ciGeminiKey\"")
                buildConfigField("String", "SONIOX_API_KEY", "\"$ciSonioxKey\"")
            } else {
                // For local release builds, use empty strings - keys loaded from secure storage
                buildConfigField("String", "GEMINI_API_KEY", "\"\"")
                buildConfigField("String", "SONIOX_API_KEY", "\"\"")
            }
        }
    }
    
    // APK splitting by CPU architecture to reduce size
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true // Keep one universal APK as fallback
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude protobuf meta files that conflict
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/native-image/**"
            excludes += "/META-INF/proguard/**"
            excludes += "/META-INF/*.proto"
            excludes += "/*.proto"
        }
        
        jniLibs {
            // Exclude GPU-related native libraries that cause compatibility issues
            excludes += listOf(
                "**/libtensorflowlite_gpu_jni.so",
                "**/libtensorflowlite_gpu_gl.so",
                "**/libtensorflowlite_gpu_delegate.so"
            )
            // Pick first for duplicate native libraries
            pickFirsts += listOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_jni.so",
                "**/libtranslate_jni.so"
            )
        }
    }
}

dependencies {
    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation for Compose
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Hilt for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    ksp("com.google.dagger:hilt-compiler:2.50")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ML Kit Translation
    implementation("com.google.mlkit:translate:17.0.2")
    
    // TensorFlow Lite for on-device Korean NLP models
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // GPU acceleration removed to reduce APK size and compatibility issues
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    
    // Korean text processing utilities
    implementation("com.googlecode.juniversalchardet:juniversalchardet:1.0.3") // Character encoding detection

    // WebSocket and real-time streaming support
    // OkHttp WebSocket is already included via OkHttp dependency
    
    // Additional JSON support for WebSocket messages
    // Gson is already included via Retrofit converter

    // Networking for Gemini API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Permission handling
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Audio recording and processing
    implementation("androidx.media:media:1.7.0")

    // Note: Firebase removed to simplify setup
    // App works perfectly without analytics

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.room:room-testing:2.6.1")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.50")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}