#include "AdaptiveResolution.h"
#include <algorithm>

namespace VideoEngine::Performance {

AdaptiveResolution::AdaptiveResolution(int baseWidth, int baseHeight)
    : m_baseWidth(baseWidth), m_baseHeight(baseHeight), 
      m_targetWidth(baseWidth), m_targetHeight(baseHeight) {
}

void AdaptiveResolution::updateScrubSpeed(float scrubSpeed) {
    if (std::abs(scrubSpeed) > 2.0f) {
        m_scaleFactor = 0.5f;
    } else {
        m_scaleFactor = 1.0f;
    }

    m_targetWidth = static_cast<int>(m_baseWidth * m_scaleFactor);
    m_targetHeight = static_cast<int>(m_baseHeight * m_scaleFactor);
    if (m_targetWidth % 2 != 0) m_targetWidth--;
    if (m_targetHeight % 2 != 0) m_targetHeight--;
}

} // namespace VideoEngine::Performance
