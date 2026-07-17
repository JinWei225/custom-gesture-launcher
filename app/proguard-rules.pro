# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class dev.neffly.gesturelauncher.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
