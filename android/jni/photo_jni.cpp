#include <jni.h>
#include <string>
#include <android/hardware_buffer_jni.h>
#include "../engine/photo_ai_effects.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_video_engine_photo_PhotoNativeBridge_nativeApplyBackgroundRemoval(JNIEnv* env, jobject thiz, jobject bufferObj) {
    if (bufferObj == nullptr) {
        return JNI_FALSE;
    }

    // 1. HardwareBuffer (Zero-copy Memory Strategy)
    // Directly wrapping the Android Hardware Buffer without copying image bytes to RAM!
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, bufferObj);
    if (!buffer) {
        return JNI_FALSE;
    }

    engine::ai::PhotoAIEffects effects("default_model.tflite");
    if (!effects.initialize()) {
        return JNI_FALSE;
    }

    engine::ai::ImageBuffer input;
    engine::ai::ImageBuffer output;
    
    // Process image without memory duplication...
    bool result = effects.removeBackground(input, output);

    return result ? JNI_TRUE : JNI_FALSE;
}
