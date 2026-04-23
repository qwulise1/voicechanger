# Voicechanger Install

1. Install `Voicechanger Module` and `Voicechanger Companion`.
2. Enable the module inside `LSPosed`.
3. Add your target apps to the module scope. The recommended baseline is:
   - `org.telegram.messenger`
   - `com.discord`
   - `com.whatsapp`
   - `org.thoughtcrime.securesms`
   - `org.signal.messenger`
   - `com.skype.raider`
4. Reboot the target apps after changing LSPosed scope.
5. Open `Voicechanger Companion`, pick a mode, set gain, and save the config.
6. Keep the diagnostics screen open while testing. The app refreshes hook logs automatically every few seconds.

The current stable path is safe-mode `AudioRecord.read(...)` only. WebRTC/lifecycle/native layers are intentionally held back until crash isolation is complete on Telegram-like clients.
