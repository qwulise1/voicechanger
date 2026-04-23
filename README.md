# Voicechanger

Root/LSPosed voice changer for Android microphone capture.

The current build ships as a single installable APK. The same APK contains:

- LSPosed module entrypoint;
- clean settings screen;
- provider-backed config store;
- root bridge fallback for target processes;
- Java `AudioRecord.read(...)` PCM processing.

## Builds

- `Voicechanger-oplus-release.apk` - main package with hidden OPlus/OnePlus vendor audio parameter layer.
- `Voicechanger-clean-release.apk` - separate package id without the vendor layer, so it does not update the main APK.

Install only the variant you want to test, enable it in LSPosed, choose target apps in LSPosed scope, then open Voicechanger and change settings. Settings save automatically.

## Audio

The DSP mode list follows the Telegraph/ExteraGram plugin direction: child, mouse, male, female, monster, robot, alien, hoarse, echo, noise, helium, hexafluoride, cave, speed, and custom modulation.

Mic boost is `0..101`. `0` is default/off; `101` is the hard limiter/saturation mode.
