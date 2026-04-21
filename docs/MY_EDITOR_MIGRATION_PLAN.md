# My Editor Migration Plan

## Goal

Use `/home/am/my_editor` as a UI/UX reference source and keep `/home/am/video_engine_core` as the actual engine/runtime base.

Do not migrate the native engine from `my_editor` as-is. Its C++ layer is mostly stubbed. Migrate editor patterns, state modeling, and panel structure instead.

## Practical Decision

- Keep native preview/import/export foundation in `video_engine_core`
- Keep Android JNI and C++ engine ownership in `video_engine_core`
- Recreate selected `my_editor` UX concepts in Kotlin, not by copying Flutter widgets directly
- Use `my_editor` mostly as a product/design and state-management reference

## Why

`video_engine_core` already has:
- working Android launch
- clip import
- preview rendering
- timeline shell
- layer controls
- save/load shell
- export path, even though mixed-input encode still needs work

`my_editor` already has:
- stronger editor-shell architecture
- richer timeline modeling
- more mature panel taxonomy
- better VN-style editing flow

`my_editor` does not currently offer a better native engine base than `video_engine_core`.

## Source Of Truth

### Use `video_engine_core` as source of truth for runtime

- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)
- [VideoPreviewView.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/VideoPreviewView.kt)
- [native_preview.cpp](/home/am/video_engine_core/android/jni/native_preview.cpp)
- [engine.cpp](/home/am/video_engine_core/engine/engine.cpp)
- [timeline.h](/home/am/video_engine_core/core/timeline.h)

### Use `my_editor` as source of truth for UX/state ideas

- [editor_screen.dart](/home/am/my_editor/lib/editor/editor_screen.dart)
- [timeline_editor.dart](/home/am/my_editor/lib/timeline/timeline_editor.dart)
- [feature_panels.dart](/home/am/my_editor/lib/panels/feature_panels.dart)
- [timeline_viewport.dart](/home/am/my_editor/lib/timeline/timeline_viewport.dart)
- [timeline_scale_manager.dart](/home/am/my_editor/lib/timeline/timeline_scale_manager.dart)

## What To Migrate

### 1. Editor layout pattern

Bring this shape from `my_editor` into the Kotlin app:
- preview card on top
- timeline card below
- tool rail / contextual clip actions at bottom
- panel taxonomy grouped by feature, not scattered buttons

Reference:
- [editor_screen.dart](/home/am/my_editor/lib/editor/editor_screen.dart)

Target files:
- [activity_main.xml](/home/am/video_engine_core/android/app/src/main/res/layout/activity_main.xml)
- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)

### 2. Timeline state model

Borrow these concepts:
- multiple tracks
- active clip selection
- transitions as explicit data
- undo/redo stack
- normalized timeline mutations through one manager

Reference:
- [timeline_editor.dart](/home/am/my_editor/lib/timeline/timeline_editor.dart)

Target files:
- [TimelineManager.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineManager.kt)
- [MultiClipTimeline.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/MultiClipTimeline.kt)
- [TimelineClip.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineClip.kt)
- [TimelineAdapter.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineAdapter.kt)

### 3. Panel taxonomy

Bring the panel grouping, not the Flutter implementation:
- Media
- Video
- Audio
- Text
- FX
- Overlay
- Transition
- Export

Reference:
- [feature_panels.dart](/home/am/my_editor/lib/panels/feature_panels.dart)

Target files:
- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)
- [EffectsPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/effects/EffectsPanel.kt)
- [TextEditorPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/TextEditorPanel.kt)
- [LayersPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/LayersPanel.kt)
- [ExportDialog.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/ExportDialog.kt)

### 4. Timeline zoom and viewport behavior

Useful ideas from `my_editor`:
- shared timeline zoom state
- viewport controller
- scale manager
- scrub/scroll separation

Reference:
- [timeline_viewport.dart](/home/am/my_editor/lib/timeline/timeline_viewport.dart)
- [timeline_viewport_controller.dart](/home/am/my_editor/lib/timeline/timeline_viewport_controller.dart)
- [timeline_scale_manager.dart](/home/am/my_editor/lib/timeline/timeline_scale_manager.dart)

Target files:
- [TimelineAdapter.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineAdapter.kt)
- [TimelineView.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/TimelineView.kt)
- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)

### 5. Undo/redo and action centralization

This is one of the highest-value migrations.

Reference:
- [timeline_editor.dart](/home/am/my_editor/lib/timeline/timeline_editor.dart)

Target implementation:
- add an undo stack to `TimelineManager`
- route clip add/remove/move/transition/text/sticker operations through one mutation API
- keep UI event handlers thin

## What Not To Migrate

Do not migrate these as foundations:

- [engine.cpp](/home/am/my_editor/engine/engine.cpp)
- [timeline.h](/home/am/my_editor/core/timeline.h)
- [CMakeLists.txt](/home/am/my_editor/jni/CMakeLists.txt)
- [native_bridge.dart](/home/am/my_editor/lib/native_bridge.dart)

Reason:
- they are too thin or placeholder-level
- they do not improve the current Android-native path in `video_engine_core`

## Implementation Order

### Phase 1: Kotlin editor shell cleanup

Goal:
- make the current app structurally closer to `my_editor`

Do:
- reorganize toolbar/actions into panel categories
- split oversized `MainActivity` responsibilities into feature controllers
- clean panel entry points

Files:
- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)
- [activity_main.xml](/home/am/video_engine_core/android/app/src/main/res/layout/activity_main.xml)

### Phase 2: Timeline state refactor

Goal:
- stop treating timeline as loose UI lists

Do:
- add track model
- add selected clip state
- add transition ownership inside `TimelineManager`
- add undo/redo

Files:
- [TimelineManager.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineManager.kt)
- [TimelineClip.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineClip.kt)
- [TimelineAdapter.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineAdapter.kt)

### Phase 3: Viewport and zoom

Goal:
- make timeline feel like a real editor, not a simple list

Do:
- add pixels-per-second scaling
- add clip width by duration
- add better scrub position mapping
- add transition overlay placement tied to clip bounds

Files:
- [TimelineAdapter.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/timeline/TimelineAdapter.kt)
- [TimelineView.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/TimelineView.kt)

### Phase 4: Overlay and panel consolidation

Goal:
- make text, stickers, effects, transitions, and layers use one interaction model

Do:
- unify selected element state
- route panel edits through one active-target system
- remove duplicated logic spread in `MainActivity`

Files:
- [MainActivity.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/MainActivity.kt)
- [LayersPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/LayersPanel.kt)
- [TextEditorPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/TextEditorPanel.kt)
- [EffectsPanel.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/effects/EffectsPanel.kt)
- [Transitions.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/transition/Transitions.kt)
- [Stickers.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/stickers/Stickers.kt)

### Phase 5: Export pipeline

Goal:
- keep current native export ownership in `video_engine_core`

Do:
- do not replace export architecture with `my_editor`
- finish mixed-input native export separately
- once export is stable, hook richer timeline state into export graph

Files:
- [native_preview.cpp](/home/am/video_engine_core/android/jni/native_preview.cpp)
- [VideoPreviewView.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/VideoPreviewView.kt)
- [ExportDialog.kt](/home/am/video_engine_core/android/app/src/main/kotlin/com/video/engine/ExportDialog.kt)

## Concrete Mapping

### `my_editor` source -> `video_engine_core` target

- `lib/editor/editor_screen.dart` -> `MainActivity` + `activity_main.xml`
- `lib/timeline/timeline_editor.dart` -> `TimelineManager.kt` + `TimelineAdapter.kt`
- `lib/timeline/timeline_scale_manager.dart` -> timeline zoom/scaling logic in Kotlin
- `lib/timeline/timeline_viewport_controller.dart` -> scroll/playhead mapping logic in Kotlin
- `lib/panels/feature_panels.dart` -> Kotlin feature panels and toolbar grouping
- `lib/export/*` -> design/reference only, not engine/export backend
- `android/app/src/main/cpp/*` in `my_editor` -> reference only, not a direct base

## Next Best Execution Step

Implement Phase 1 and Phase 2 first.

That means:
- reduce `MainActivity` responsibilities
- make `TimelineManager` the single mutation entry point
- add undo/redo and selected clip state

This gives the biggest quality jump with the least risk to the working native engine.
