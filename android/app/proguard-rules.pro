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

# Keep osmdroid — used at runtime for tile rendering and overlays
-keep class org.osmdroid.events.** { *; }
-keep class org.osmdroid.tileprovider.** { *; }
-keep class org.osmdroid.util.GeoPoint { *; }
-keep class org.osmdroid.util.BoundingBox { *; }
-keep class org.osmdroid.views.MapView { *; }
-keep class org.osmdroid.views.overlay.Marker { *; }
-keep class org.osmdroid.views.overlay.Polyline { *; }
-keep class org.osmdroid.views.CustomZoomButtonsController { *; }
-dontwarn org.osmdroid.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
