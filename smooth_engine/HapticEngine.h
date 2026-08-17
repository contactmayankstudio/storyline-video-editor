#pragma once

namespace VideoEngine::Android {

/**
 * @brief Android Haptic Feedback Engine.
 * 
 * Provides "Premium Feel" via tactile feedback.
 * - Snap to clip: Light vibration.
 * - Keyframe added: Sharp click.
 * - Long press: Heavy pulse.
 */
class HapticEngine {
public:
    enum class Effect {
        LightClick,
        HeavyClick,
        Tick,
        Success,
        Warning
    };

    /**
     * @brief Trigger a haptic effect using Android's Vibrator service.
     */
    void trigger(Effect effect);

private:
    void* m_vibratorService = nullptr;
};

} // namespace VideoEngine::Android
