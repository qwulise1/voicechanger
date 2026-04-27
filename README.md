# qwulivoice

Root/LSPosed voice changer for Android microphone capture.

The current build ships as one installable clean APK plus an optional root audio module. The APK contains:

- LSPosed module entrypoint;
- clean settings screen;
- provider-backed config store;
- root bridge fallback for target processes;
- Java `AudioRecord.read(...)` PCM processing.

## Builds

- `qwulivoice-beta-release.apk` - clean LSPosed module, package `com.qwulivoice.beta`, without the hidden OPlus/OnePlus vendor layer.
- `qwulivoice-root-audio-module.zip` - KernelSU Next / Magisk systemless input audio effect module.

Install the APK, enable it in LSPosed, choose target apps in LSPosed scope, then open qwulivoice and change settings. Settings save automatically.

For the root audio layer, install `qwulivoice-root-audio-module.zip` in KernelSU Next or Magisk and reboot. The APK remains the settings UI.

## Audio

The DSP mode list follows the Telegraph/ExteraGram plugin direction: child, mouse, male, female, monster, robot, alien, hoarse, echo, noise, helium, hexafluoride, cave, speed, and custom modulation.

Mic boost is `0..101`. `0` is default/off; `101` is the hard limiter/saturation mode.
