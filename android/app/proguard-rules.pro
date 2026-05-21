# Marco Polo ProGuard rules
# Add project specific ProGuard rules here.

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.marcopolo.model.**$$serializer { *; }
-keepclassmembers class com.marcopolo.model.** { *** Companion; }
-keepclasseswithmembers class com.marcopolo.model.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
