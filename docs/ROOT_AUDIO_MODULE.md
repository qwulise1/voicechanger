# qwulivoice root audio layer

`qwulivoice-root-audio-module.zip` is a KernelSU Next / Magisk compatible module.

It installs a systemless input pre-processing `AudioEffect`:

- native library: `/vendor/lib64/soundfx/libqwv_preprocess.so`
- native library: `/vendor/lib/soundfx/libqwv_preprocess.so`
- patched config: `/vendor/etc/audio_effects.xml`
- synced settings: `/vendor/etc/qwulivoice.properties`

The APK remains the control surface. The module service syncs APK settings from
`/data/local/tmp/qwulivoice-com.qwulivoice.beta.properties` into the vendor-side
properties file read by audioserver.

Install flow:

1. Install `qwulivoice-beta-release.apk`.
2. Install `qwulivoice-root-audio-module.zip` in KernelSU Next or Magisk.
3. Reboot.
4. Open qwulivoice and enable the effect.
5. Test apps that use microphone input with `VOICE_COMMUNICATION`, `MIC`, or `CAMCORDER`.

Cellular calls may still be handled by the modem/vendor voice path instead of
normal Android `AudioRecord`. This module adds the Android audio effect layer,
but vendor HAL policy decides whether that layer is applied to the call path.
