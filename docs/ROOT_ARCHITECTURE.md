# Root Voicechanger Architecture

## Target Shape

Voicechanger now ships as one LSPosed APK per variant. The UI, config provider, root bridge, and hook entrypoint live in the same installed package.

- `module`: installable LSPosed APK with UI and hook code.
- `core:dsp`: shared config, root bridge encoding, and PCM processor.
- `app`: source holder for the UI classes that are compiled into `module`.

## Live Layers

- Java `AudioRecord.read(...)` hooks for `byte[]`, `short[]`, `float[]`, and `ByteBuffer`.
- Root bridge config fallback under `/data/local/tmp`.
- Automatic UI save on every setting change.
- Telegraph-style realtime DSP modes.
- Clean-only runtime; the OPlus/OnePlus vendor parameter layer is not shipped.

## Limits

LSPosed scope still controls which app processes are touched. Apps that bypass Java `AudioRecord` can still need native `AAudio`/Oboe/vendor hooks later.
