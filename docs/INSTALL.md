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
6. Use the routing buttons if needed:
   - `Реком. пакеты` fills the recommended scope list into package routing.
   - `Из логов` builds a routing list from apps already seen by diagnostics.
   - `Весь scope` disables package restriction and processes every scoped app.
7. Keep the diagnostics screen open while testing. The app refreshes hook logs automatically every few seconds.

The Java capture layer is covered end-to-end for `AudioRecord` and Java `WebRTC` paths, including lifecycle diagnostics and a `nativeDataIsRecorded` bridge. Native `AAudio/Oboe/vendor` paths can still require future hook work.
