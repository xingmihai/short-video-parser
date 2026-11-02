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
