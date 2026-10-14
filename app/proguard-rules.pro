# JNI bridge keeps its names; nothing else reflected.
-keepclasseswithmembernames class com.prismml.bonsaidroid.nativeffi.BonsaiNative {
    native <methods>;
}

# kotlinx.serialization models used for harness exports / chat store.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.prismml.bonsaidroid.**$$serializer { *; }
-keepclasseswithmembers class com.prismml.bonsaidroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}
