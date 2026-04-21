import 'dart:ffi' as ffi;
import 'dart:io' show Platform;
import 'package:ffi/ffi.dart';

/// A wrapper class for native video functions implemented in C++.
///
/// This class uses dart:ffi to load a native dynamic library
/// (`libnative_engine.so` on Android) and provides Dart methods
/// that call the underlying C functions.
class NativeVideoUtil {
  /// Holds the loaded native dynamic library.
  static final ffi.DynamicLibrary _dylib = _loadLibrary();

  // --- C function signature ---
  // typedef int (*get_video_duration_func)(const char* filepath);
  //
  // --- Dart FFI mapping ---

  /// C function signature definition for `get_video_duration`.
  /// We use ffi.Int32 for C's `int` and ffi.Pointer<Utf8> for `const char*`.
  typedef _GetVideoDurationCFunc = ffi.Int32 Function(ffi.Pointer<Utf8> filepath);

  /// Dart function signature definition for `get_video_duration`.
  typedef _GetVideoDurationDartFunc = int Function(ffi.Pointer<Utf8> filepath);

  /// Looks up the 'get_video_duration' function in the loaded library
  /// and casts it to a Dart function that can be called.
  static final _GetVideoDurationDartFunc _getVideoDuration =
      _dylib.lookup<ffi.NativeFunction<_GetVideoDurationCFunc>>('get_video_duration').asFunction<_GetVideoDurationDartFunc>();

  /// Loads the native library.
  ///
  /// On Android and Linux, the convention is to use the library name directly
  /// (e.g., 'libnative_engine.so'), and the dynamic linker will find it.
  static ffi.DynamicLibrary _loadLibrary() {
    if (Platform.isAndroid) {
      return ffi.DynamicLibrary.open('libnative_engine.so');
    }
    // Add other platforms as needed. For example:
    // if (Platform.isIOS) {
    //   return ffi.DynamicLibrary.executable();
    // }
    // if (Platform.isWindows) {
    //   return ffi.DynamicLibrary.open('native_engine.dll');
    // }
    throw UnsupportedError('Platform not supported');
  }

  /// Calls the native `get_video_duration` function.
  ///
  /// Takes a Dart [filePath] string, converts it to a C-compatible
  /// null-terminated UTF-8 string, calls the native function,
  /// and then frees the allocated native memory.
  ///
  /// Returns the duration of the video in seconds as an integer.
  static int getDuration(String filePath) {
    // 1. Convert the Dart String to a C-style null-terminated string (Pointer<Utf8>).
    //    `toNativeUtf8` allocates memory that must be manually freed.
    final ffi.Pointer<Utf8> pathPointer = filePath.toNativeUtf8();

    try {
      // 2. Call the native function with the pointer.
      final int duration = _getVideoDuration(pathPointer);
      return duration;
    } finally {
      // 3. Always free the memory allocated by `toNativeUtf8` to prevent leaks.
      calloc.free(pathPointer);
    }
  }
}
