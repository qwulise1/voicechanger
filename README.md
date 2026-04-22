# Voicechanger

`voicechanger` is a rooted Android voice-changing stack built around two deliverables:

1. `module` - a hook/root-facing module intended for LSPosed/Zygisk-style integration.
2. `app` - a companion APK for configuration, profiles, diagnostics, and future per-app targeting.

The current MVP already ships a working Java-layer microphone hook through LSPosed and keeps room for deeper native routing later:

- microphone PCM interception via `AudioRecord.read(...)`;
- shared DSP profiles and routing;
- app/module settings synchronization through a module `ContentProvider`;
- GitHub Actions builds and artifact publishing.

## Modules

- `app` - companion application with live controls for enable/mode/strength/gain.
- `module` - LSPosed module with an active `AudioRecord` interception layer and provider-backed config store.
- `core:dsp` - shared Kotlin core for effect metadata, config contracts, and reusable PCM processors.

## Current Status

Current MVP status:

- GitHub Actions build both APKs in the cloud.
- The module hooks Java `AudioRecord.read(...)` for scoped apps.
- The companion app saves configuration into the module through a content provider.
- Four PCM modes are available right now: Original, Robot, Bright, and Deep.
- Optional per-app package routing is available.
- The module keeps a live diagnostic ring buffer and reports WebRTC detection events.

## Next Steps

1. Extend interception beyond Java `AudioRecord` into deeper `WebRTC`, `AAudio`, and selected native paths.
2. Replace the current lightweight timbre effects with deeper pitch/formant DSP.
3. Add release packaging and optional GitHub Releases publishing.
