# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class dev.neffly.gesturelauncher.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# EvalEx is built with Lombok, whose @Generated annotation is compile-time only and not shipped;
# R8 treats the dangling reference as an error unless told it is expected.
-dontwarn lombok.Generated

# EvalEx finds its operators and functions through their @InfixOperator / @PrefixOperator /
# @FunctionParameter annotations at parse time, by reflection. Shrunk, the annotation classes go
# and every operator is "not found" — the calculator's first use then crashes the search box.
-keep class com.ezylang.evalex.** { *; }
