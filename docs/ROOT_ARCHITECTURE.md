# Root Voicechanger Architecture

## Target shape

The project is split into two shipping APKs plus shared logic:

- `module`: root-facing hook module for process injection and audio interception.
- `app`: companion APK for UI, toggles, target app selection, logs, and future profile management.
- `core:dsp`: shared models and DSP-facing configuration contracts.

## Intended interception layers

The root module is expected to evolve through the following layers:

1. Java path: `AudioRecord.read(...)` via LSPosed legacy API entrypoint
2. WebRTC path for apps with custom voice stacks
3. Native path: `AAudio` / `Oboe` / selected vendor-facing audio entry points
4. Optional Zygisk-native helper for broad process coverage

## Constraints

- "Global for every app" is still not a magic switch even with root.
- OEM audio stacks and vendor changes will require device-specific validation.
- System dialer / carrier call stacks may need separate handling.

## What is live right now

- companion `app` writes config to the module through an exported `ContentProvider`
- LSPosed `module` reads that config from injected app processes with caching
- `AudioRecord.read(...)` overloads for `byte[]`, `short[]`, `float[]`, and `ByteBuffer` are processed
- current DSP is lightweight and real-time safe: original, robot, bright, deep, plus mic gain

## Still missing for a broader "global" result

- native interception for apps bypassing Java `AudioRecord`
- package-level routing and diagnostics
- stronger pitch/formant algorithms closer to studio-style voice changers
