# OkHttp 用到反射与 ServiceLoader，保留相关成员避免 R8 裁掉
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# androidx.core FileProvider 由 Manifest 反射实例化
-keep class androidx.core.content.FileProvider { *; }

# 布局 XML 中引用的自定义 View（双保险，AGP 也会自动生成 keep 规则）
-keep class com.svproxy.WavyProgressView { *; }
