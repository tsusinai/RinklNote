# Keep Compose stability annotations for recomposition skipping
-keep @androidx.compose.runtime.Stable class **
-keep @androidx.compose.runtime.Immutable class **
-keepclassmembers class * {
    @androidx.compose.runtime.Stable <methods>;
}

# Room
-keep class com.example.rinklnote.data.db.entity.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.rinklnote.**$$serializer { *; }
-keepclassmembers class com.example.rinklnote.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.rinklnote.** {
    kotlinx.serialization.KSerializer serializer(...);
}