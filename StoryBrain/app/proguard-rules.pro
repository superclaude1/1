# Keep serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep app data models
-keep class com.storybrain.app.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
