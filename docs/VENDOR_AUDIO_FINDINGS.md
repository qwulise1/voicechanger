# Vendor Audio Findings

## OPlus Game Assistant

The OPlus Game Assistant implementation is not a Java `AudioRecord.read()` PCM hook. The APK uses `AudioManager.setParameters()` to pass vendor keys into the audio stack.

Observed keys from `com.oplus.games`:

- `currentGamePackageName=<package>`
- `oplusmagicvoiceinfo=<voiceParam>|<package>|true`
- `clearMagicVoiceInfo=true`
- `magicvoiceloopbackpackage=<package>`
- `magicvoiceloopbackenable=0|1`
- `OPLUS_AUDIO_SET_TRACKVOLUME:<gain>:<uid>`

Relevant support probes:

- `oplus.software.audio.magicvoice_v2_basic_support`
- `oplus.software.audio.magicvoice_v2.1_basic_support`
- `oplus.software.audio.magicvoice_support`
- `oplus.software.audio.magicvoice_loopback_support`
- `oplus.gamespace.voicechange.support`
- `oppo.gamespace.voicechange.support`
- `ro.vendor.audio.voice.change.support`
- `ro.vendor.audio.voice.change.version`
- `ro.vendor.audio.voice.change.youme.support`

On the test device the package feature checks are false, but the vendor properties include `ro.vendor.audio.voice.change.support=true` and `ro.vendor.audio.voice.change.version=2`, so the HAL path is worth testing separately from LSPosed capture hooks.

## Implementation Direction

The distributed APK is clean-only now, so the OPlus vendor layer is not shipped in CI artifacts. These notes are kept only as research context.

This path is vendor-specific. If the HAL rejects calls from non-system packages or requires Game Assistant allowlists/cloud auth, the next layer is a root/system-service shim or Magisk-side audioserver/HAL integration instead of app-level `AudioRecord` hooks.
