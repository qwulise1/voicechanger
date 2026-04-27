# qwulivoice root audio layer

`qwulivoice-root-audio-module.zip` is a KernelSU Next / Magisk compatible module.

It installs a systemless input pre-processing `AudioEffect`:

- native library: `/vendor/lib64/soundfx/libqwv_preprocess.so`
- native library: `/vendor/lib/soundfx/libqwv_preprocess.so`
- patched configs: `audio_effects.xml` and legacy `audio_effects.conf` under vendor/odm/product/system_ext when present
- AML integration: `.aml.sh` with `pre_processing` patches for common microphone sources
- synced settings: `/data/adb/qwulivoice/config.properties` and `/vendor/etc/qwulivoice.properties`
- synced soundpad state/clips: `/data/adb/qwulivoice/soundpad*`

The APK remains the control surface. The module service syncs APK voice and
soundpad settings into root-readable files used by the native audioserver-side
effect.

Install flow:

1. Install `qwulivoice-beta-release.apk`.
2. Install `qwulivoice-root-audio-module.zip` in KernelSU Next or Magisk.
3. Reboot.
4. Open qwulivoice and enable the effect.
5. Test apps that use microphone input with Android capture sources such as `VOICE_COMMUNICATION`, `MIC`, `CAMCORDER`, `VOICE_CALL`, or `UNPROCESSED`.

Cellular calls may still be handled by the modem/vendor voice path instead of
normal Android `AudioRecord`. This module adds the Android audio effect layer,
but vendor HAL policy decides whether that layer is applied to the call path.
