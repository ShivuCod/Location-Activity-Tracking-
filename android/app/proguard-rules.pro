# Keep OkHttp platform used by Cronet
-keepclassmembers class okhttp3.internal.platform.* {
    okhttp3.internal.platform.Platform buildPlatform();
}

# Keep required services
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.bouncycastle.jsse.provider.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.openjsse.** { *; }

# Keep OkHttp internal details
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep all your model classes
-keep class com.example.back_activity_detect.** { *; }
