# Voicechanger

`voicechanger` is a bootstrap repository for a rooted Android voice changing stack built around two deliverables:

1. `module` - a hook/root-facing module intended for LSPosed/Zygisk-style integration.
2. `app` - a companion APK for configuration, profiles, diagnostics, and future per-app targeting.

The initial goal is not to ship a finished root hook on day one, but to establish a repository that already builds on GitHub and is ready for iterative work on:

- microphone PCM interception;
- shared DSP profiles and routing;
- app/module settings synchronization;
- GitHub Actions builds and artifact publishing.

## Modules

- `app` - companion application with baseline UI and project status screen.
- `module` - hook module APK scaffold with a visible entry activity and implementation roadmap.
- `core:dsp` - shared Kotlin core for effect metadata and reusable configuration models.

## Current Status

This first revision is a repository bootstrap:

- Android project is initialized.
- GitHub Actions are configured to build both APKs in the cloud.
- Root hook logic is intentionally stubbed and documented rather than faked.

## Next Steps

1. Add settings storage shared between module and companion app.
2. Define target hook layers: `AudioRecord`, `AAudio`, `WebRTC`, and selected native paths.
3. Move current ExteraGram DSP experiments into reusable shared code.
4. Add per-app enable/disable routing and live diagnostics.

