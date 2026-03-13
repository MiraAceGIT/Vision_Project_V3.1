# ProGuard Configuration for Blind Helmet Vision App

# Keep Android framework classes
-keep public class android.**
-keepnames class android.**

# Keep our app classes
-keep class com.blind_helmet.app.** { *; }

# Keep TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keepclasseswithmembernames class org.tensorflow.lite.** {
    native <methods>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep JSON
-keep class org.json.** { *; }
-keep class com.google.gson.** { *; }

# Keep BuildConfig
-keep class **.BuildConfig { *; }

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# General optimization
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# Remove logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
