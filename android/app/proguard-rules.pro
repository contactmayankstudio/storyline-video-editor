# -----------------------------------------------------------------------------
# Storyline ProGuard / R8 Optimization & Obfuscation Rules
# -----------------------------------------------------------------------------

# Keep native methods and their declaring classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge and native interface
-keep class com.video.engine.NativeBridge {
    *;
}
-keep class com.video.engine.ExportCallback {
    *;
}

# Keep view classes instantiated via reflection or XML
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep ViewBinding generated classes
-keep class com.video.engine.databinding.** { *; }

# Keep models used across JNI or saved state serialization
-keep class com.video.engine.EditorState** { *; }
-keep class com.video.engine.ProjectState** { *; }
-keep class com.video.engine.LayerDescriptor** { *; }
-keep class com.video.engine.LayerItem** { *; }
-keep class com.video.engine.ClipSegment** { *; }
-keep class com.video.engine.effects.EffectParams** { *; }
-keep class com.video.engine.effects.ProFilterPresets** { *; }
-keep class com.video.engine.transition.TransitionType** { *; }
-keep class com.video.engine.stickers.** { *; }
-keep class com.video.engine.timeline.** { *; }

# Keep Firebase and Google Play Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Kotlin reflect and coroutines metadata
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
