# -----------------------------------------------------------------------------
# Storyline ProGuard / R8 Optimization & Obfuscation Rules
# -----------------------------------------------------------------------------

# Repackage obfuscated classes into a single root package to optimize DEX and boost obfuscation ratio
-repackageclasses 'com.storyline.o'
-allowaccessmodification

# Keep native methods and their declaring classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge classes and their native interfaces
-keep class com.video.engine.NativeBridge {
    native <methods>;
}
-keep class com.video.engine.VideoPreviewView {
    native <methods>;
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.video.engine.photo.PhotoNativeBridge {
    native <methods>;
}
-keep class com.video.engine.pro.jni.NativeEngineBridge {
    native <methods>;
}

# JNI callback methods invoked from C++
-keep interface com.video.engine.ExportCallback {
    void onExportProgress(int);
    void onExportCompleted(boolean, java.lang.String);
}
-keep interface com.video.engine.VideoPreviewView$ExportCallback {
    void onExportProgress(int);
    void onExportCompleted(boolean, java.lang.String);
}

# Keep view classes instantiated via reflection or XML
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep models used across JNI or saved state serialization
-keepclassmembers class com.video.engine.EditorState** { *; }
-keepclassmembers class com.video.engine.ProjectState** { *; }
-keepclassmembers class com.video.engine.LayerDescriptor** { *; }
-keepclassmembers class com.video.engine.LayerItem** { *; }
-keepclassmembers class com.video.engine.ClipSegment** { *; }
-keepclassmembers class com.video.engine.effects.EffectParams** { *; }
-keepclassmembers class com.video.engine.effects.ProFilterPresets** { *; }
-keepclassmembers class com.video.engine.transition.TransitionType** { *; }

# LiteRT & TensorFlow Lite
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Google ML Kit
-dontwarn com.google.mlkit.**

# Keep Kotlin reflect and coroutines metadata
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
