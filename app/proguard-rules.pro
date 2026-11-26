# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.warpmo.app.data.**$$serializer { *; }
-keepclassmembers class com.warpmo.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.warpmo.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
