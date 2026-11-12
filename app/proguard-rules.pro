# ---- ML Kit text recognition (fixes release crash in TextRecognition.getClient) ----
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# ML Kit discovers components via ComponentRegistrar implementations
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class * implements com.google.mlkit.common.sdkinternal.** { *; }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService { *; }
-keep class com.google.mlkit.common.sdkinternal.MlKitInitProvider { *; }

-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# ---- Native (JNI) code, e.g. whisperkit: method names must not be renamed ----
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- Rive animations (JNI + reflection) ----
-keep class app.rive.** { *; }
-dontwarn app.rive.**

# Keep line numbers so release stack traces stay readable
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile