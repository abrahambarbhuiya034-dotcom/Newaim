# React Native
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }

# BitAim — never obfuscate any app class
-keep class com.bitaim.carromaim.** { *; }

# OpenCV — native JNI methods must not be renamed
-keep class org.opencv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
