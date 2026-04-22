# Voicechanger

`voicechanger` is a rooted Android voice-changing stack built around two deliverables:

1. `module` - a hook/root-facing module intended for LSPosed/Zygisk-style integration.
2. `app` - a companion APK for configuration, profiles, diagnostics, and future per-app targeting.

The current MVP already ships a working Java-layer microphone hook through LSPosed and keeps room for deeper native routing later:

- microphone PCM interception via `AudioRecord.read(...)`;
- shared DSP profiles and routing;
- app/module settings synchronization through a module `ContentProvider`;
- live diagnostics, scope presets, and GitHub Actions builds.

## Modules

- `app` - companion application with live controls for enable/mode/strength/gain.
- `module` - LSPosed module with an active `AudioRecord` interception layer and provider-backed config store.
- `core:dsp` - shared Kotlin core for effect metadata, config contracts, and reusable PCM processors.

## Current Status

Current MVP status:

- GitHub Actions build both APKs in the cloud.
- GitHub Actions package a ready-to-download release bundle zip.
- The module hooks Java `AudioRecord.read(...)` for scoped apps.
- The companion app saves configuration into the module through a content provider.
- Four PCM modes are available right now: Original, Robot, Bright, and Deep.
- Optional per-app package routing is available.
- The module keeps a live diagnostic ring buffer and reports WebRTC detection events.
- Recommended LSPosed scope is declared in the manifest for common messaging and voice apps.
- The companion app can fill routing from recommended packages or from recent live logs.

## Install Flow

1. Install both APKs: module and companion.
2. Enable the module in `LSPosed`.
3. Put target apps into the module scope.
4. Open the companion app and save the effect config.
5. Test voice capture while watching the diagnostics view.

Detailed install notes are in [docs/INSTALL.md](docs/INSTALL.md).

## Next Steps

1. Extend interception beyond Java `AudioRecord` into deeper `WebRTC`, `AAudio`, and selected native paths.
2. Replace the current lightweight timbre effects with deeper pitch/formant DSP.
3. Validate the hook chain on more real devices and app-specific audio pipelines.
