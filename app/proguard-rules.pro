# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class tv.tellyport.data.**$$serializer { *; }
-keepclassmembers class tv.tellyport.data.** {
    *** Companion;
}
-keepclasseswithmembers class tv.tellyport.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Shizuku UserService instantiated reflectively inside Shizuku's process
-keep class tv.tellyport.shizuku.ForceStopUserService { *; }
-keep class tv.tellyport.shizuku.IForceStopService$** { *; }

