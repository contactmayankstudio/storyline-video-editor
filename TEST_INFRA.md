# E2E Test Infra: storyline Video Engine Features

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on internal implementation details.
- Systematic methodology: Category-Partition + BVA + Pairwise + Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|---------------------|:------:|:------:|:------:|:------:|
| 1 | Proxy Editing Path & Invalidation | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 2 | Export Path Original Media Isolation | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 3 | Audio Real-Time Time Stretch & Export | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 4 | Speed Curve Mapping in Export | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 5 | OpenGL Blend Modes & Shaders | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| 6 | Texture Alpha/Luma Mask Sampling | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| 7 | Keyframe Struct & Vector | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |
| 8 | Project JSON Serialization & Load | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |

## Test Architecture
- Compilation sanity check: `cd android && ./gradlew :app:assembleDebug`
- JVM Unit Tests: `cd android && ./gradlew testDebugUnitTest`
- C++ Native Test Harnesses & JSON validation scripts

## Coverage Thresholds
- Tier 1: ≥5 test cases per feature (happy-path & core execution)
- Tier 2: ≥5 test cases per feature (boundary values & edge conditions)
- Tier 3: Pairwise feature interaction scenarios
- Tier 4: Application-level integration scenarios
- Tier 5: White-box adversarial code path hardening
