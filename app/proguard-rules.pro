# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Hilt Rules
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Hilt components
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_HiltComponents$* { *; }
-keep class **_Factory { *; }
-keep class **_Factory$* { *; }
-keep class **_Impl { *; }
-keep class **_Impl$* { *; }

# Keep all @HiltAndroidApp, @AndroidEntryPoint, @HiltViewModel classes
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Keep all @Inject constructors
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Keep all @Module and @InstallIn classes
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep our app's classes
-keep class com.koreantranslator.** { *; }
-keepclassmembers class com.koreantranslator.** { *; }

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform
-dontwarn org.conscrypt.ConscryptHostnameVerifier

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Compose
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.compose.**

# General Android
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*

# Keep native methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Secure BuildConfig - Remove in release builds
# Only keep essential fields, obfuscate API keys
-keepclassmembers class com.koreantranslator.BuildConfig {
    public static final java.lang.String APPLICATION_ID;
    public static final int VERSION_CODE;
    public static final java.lang.String VERSION_NAME;
    public static final boolean DEBUG;
}

# Obfuscate API key fields specifically
-obfuscate class com.koreantranslator.BuildConfig {
    public static final java.lang.String GEMINI_API_KEY;
    public static final java.lang.String SONIOX_API_KEY;
}

# CRITICAL FIX: Firebase Transport logging optimization
# Remove Firebase Transport debug logging in release builds
-assumenosideeffects class com.google.firebase.** {
    void log(...);
    void debug(...);
    void trace(...);
    void verbose(...);
}

# Optimize transport and ML Kit logging
-assumenosideeffects class com.google.android.gms.common.** {
    void log(...);
    void debug(...);
    void trace(...);
}

# Remove excessive Firebase Transport event logging
-assumenosideeffects class **.FirebaseTransport** {
    void log(...);
    void debug(...);
    void logEvent(...);
}

# Security Enhancement Rules
# Remove all debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# Remove System.out and System.err calls
-assumenosideeffects class java.lang.System {
    public static *** out;
    public static *** err;
}

# Remove printStackTrace calls
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Aggressive source file obfuscation
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable

# Protect our security package
-keep class com.koreantranslator.security.** { *; }

# Remove test and debug code
-assumenosideeffects class * {
    void debug*(...);
    void trace*(...);
}

# Suppress warnings
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe
-dontwarn com.google.firebase.**
-dontwarn com.google.android.datatransport.**