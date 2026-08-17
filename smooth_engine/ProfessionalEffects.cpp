#include "ProfessionalEffects.h"

namespace VideoEngine::Advanced {

const std::vector<FilterPreset>& builtInFilterPresets() {
    static const std::vector<FilterPreset> kPresets = {
        {"Cine Matte", "luts/cine_matte.cube", 1.0f},
        {"Teal Fade", "luts/teal_fade.cube", 0.92f},
        {"Soft Skin", "luts/soft_skin.cube", 0.88f},
        {"Night Street", "luts/night_street.cube", 1.0f},
        {"Seoul Vlog", "luts/seoul_vlog.cube", 0.94f},
    };
    return kPresets;
}

MaskParams makeDefaultMask(MaskType type) {
    MaskParams params;
    params.type = type;
    switch (type) {
        case MaskType::Linear:
            params.feather = 0.12f;
            params.scale[0] = 1.15f;
            params.scale[1] = 1.0f;
            break;
        case MaskType::Mirror:
            params.feather = 0.08f;
            break;
        case MaskType::Radial:
            params.feather = 0.18f;
            params.scale[0] = 0.72f;
            params.scale[1] = 0.72f;
            break;
        case MaskType::Rectangle:
            params.feather = 0.10f;
            params.scale[0] = 0.84f;
            params.scale[1] = 0.62f;
            break;
        case MaskType::Heart:
        case MaskType::Star:
            params.feather = 0.06f;
            params.scale[0] = 0.66f;
            params.scale[1] = 0.66f;
            break;
        case MaskType::None:
        default:
            break;
    }
    return params;
}

const char* blendModeName(BlendMode mode) {
    switch (mode) {
        case BlendMode::Normal: return "Normal";
        case BlendMode::Multiply: return "Multiply";
        case BlendMode::Screen: return "Screen";
        case BlendMode::Overlay: return "Overlay";
        case BlendMode::Darken: return "Darken";
        case BlendMode::Lighten: return "Lighten";
        case BlendMode::ColorDodge: return "Color Dodge";
        case BlendMode::ColorBurn: return "Color Burn";
        case BlendMode::HardLight: return "Hard Light";
        case BlendMode::SoftLight: return "Soft Light";
        case BlendMode::Difference: return "Difference";
        case BlendMode::Exclusion: return "Exclusion";
        default: return "Unknown";
    }
}

} // namespace VideoEngine::Advanced
