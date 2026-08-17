#include "HapticEngine.h"
#include <iostream>

namespace VideoEngine::Android {

void HapticEngine::trigger(Effect effect) {
    // In real Android:
    // Call JNI methods for Vibrator.vibrate(VibrationEffect.createPredefined(...))
    std::cout << "[HapticEngine] Triggering tactile feedback\n";
}

} // namespace VideoEngine::Android
