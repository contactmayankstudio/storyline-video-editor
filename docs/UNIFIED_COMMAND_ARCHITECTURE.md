# Unified Command Architecture

This repo now has a master command bridge for Kotlin -> JNI -> C++.

## Kotlin

Use:

```kotlin
val result = NativeBridge.executeCommand(
    action = "MOVE_CLIP",
    params = mapOf(
        "clipId" to 12,
        "newTimeMs" to 4_500L,
    )
)
```

File:
- `android/app/src/main/kotlin/com/video/engine/NativeBridge.kt`

## JNI

Single entry point:
- `android/jni/command_jni.cpp`

JNI only forwards `action + payloadJson` into the native command manager.

## C++

Dispatcher and command stack:
- `engine/commands/command_manager.h`
- `engine/commands/command_manager.cpp`
- `engine/commands/editor_command.h`
- `engine/commands/command_result.h`

Current command cases:
- `SEEK`
- `PLAY`
- `PAUSE`
- `DELETE` / `DELETE_CLIP`
- `MOVE_CLIP`
- `SPEED`
- `UNDO`
- `REDO`

Scaffolded but not implemented:
- `SPLIT`
- `TRANSITION`

## Threading

Commands are queued into a dedicated native worker thread inside `CommandManager`.
The JNI layer stays unchanged when new commands are added.

## Adding a new command

1. Add a new `EditorCommand` subclass in `command_manager.cpp` or move it into its own file.
2. Add a new case in `CommandManager::buildCommand(...)`.
3. Call it from Kotlin via `NativeBridge.executeCommand(...)`.

No new JNI function is needed.
