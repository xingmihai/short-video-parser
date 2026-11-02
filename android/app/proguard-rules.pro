# ---------- 本项目 ----------
-keep class com.svproxy.** { *; }
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod *;
}
-keepclassmembers class * {
    @com.facebook.react.uimanager.annotations.ReactProp *;
    @com.facebook.react.uimanager.annotations.ReactPropGroup *;
}

# ---------- React Native 反射 / JNI ----------
-keep,allowobfuscation @interface com.facebook.proguard.annotations.DoNotStrip
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keepclassmembers class * { @com.facebook.proguard.annotations.DoNotStrip *; }
-keep,allowobfuscation @interface com.facebook.jni.annotations.DoNotStrip
-keep @com.facebook.jni.annotations.DoNotStrip class *
-keepclassmembers class * { @com.facebook.jni.annotations.DoNotStrip *; }
-keep class com.facebook.react.bridge.** { *; }
-keep class com.facebook.react.turbomodule.** { *; }
-dontwarn com.facebook.react.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# ---------- OkHttp / Okio ----------
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
