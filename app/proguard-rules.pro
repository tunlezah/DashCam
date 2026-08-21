# DashCam ProGuard/R8 rules.
# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.tunlezah.dashcam.** {
    kotlinx.serialization.KSerializer serializer(...);
}
