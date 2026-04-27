#!/system/bin/sh

MODDIR="${0%/*}"
APP_CONFIG="/data/local/tmp/qwulivoice-com.qwulivoice.beta.properties"
ADB_CONFIG="/data/adb/qwulivoice/config.properties"
VENDOR_CONFIG="$MODDIR/system/vendor/etc/qwulivoice.properties"
APP_SOUNDPAD_LIBRARY="/data/local/tmp/qwulivoice-com.qwulivoice.beta.soundpad.properties"
APP_SOUNDPAD_PLAYBACK="/data/local/tmp/qwulivoice-com.qwulivoice.beta.soundpad.state.properties"
APP_SOUNDPAD_DIR="/data/local/tmp/qwulivoice-com.qwulivoice.beta-soundpad"
ADB_SOUNDPAD_LIBRARY="/data/adb/qwulivoice/soundpad.properties"
ADB_SOUNDPAD_PLAYBACK="/data/adb/qwulivoice/soundpad.state.properties"
ADB_SOUNDPAD_DIR="/data/adb/qwulivoice/soundpad"
LOG_FILE="/data/adb/qwulivoice/root-audio.log"
BOOT_RESTART_MARK="/dev/qwulivoice-root-audio-restarted"

mkdir -p /data/adb/qwulivoice "$ADB_SOUNDPAD_DIR"

log_line() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG_FILE"
}

sync_config() {
  if [ -f "$APP_CONFIG" ]; then
    cp -af "$APP_CONFIG" "$ADB_CONFIG"
    cp -af "$APP_CONFIG" "$VENDOR_CONFIG"
  elif [ -f "$ADB_CONFIG" ]; then
    cp -af "$ADB_CONFIG" "$VENDOR_CONFIG"
  fi
  chmod 0644 "$ADB_CONFIG" "$VENDOR_CONFIG" 2>/dev/null
}

sync_soundpad_state() {
  if [ -f "$APP_SOUNDPAD_LIBRARY" ]; then
    cp -af "$APP_SOUNDPAD_LIBRARY" "$ADB_SOUNDPAD_LIBRARY"
  fi
  if [ -f "$APP_SOUNDPAD_PLAYBACK" ]; then
    cp -af "$APP_SOUNDPAD_PLAYBACK" "$ADB_SOUNDPAD_PLAYBACK"
  fi
  chmod 0644 "$ADB_SOUNDPAD_LIBRARY" "$ADB_SOUNDPAD_PLAYBACK" 2>/dev/null
}

sync_soundpad_pcm() {
  [ -d "$APP_SOUNDPAD_DIR" ] || return 0
  mkdir -p "$ADB_SOUNDPAD_DIR"
  for pcm in "$APP_SOUNDPAD_DIR"/*.pcm
  do
    [ -f "$pcm" ] || continue
    cp -af "$pcm" "$ADB_SOUNDPAD_DIR/"
  done
  chmod 0755 "$ADB_SOUNDPAD_DIR" 2>/dev/null
  chmod 0644 "$ADB_SOUNDPAD_DIR"/*.pcm 2>/dev/null
}

log_audio_state() {
  log_line "module=$MODDIR"
  for path in \
    /vendor/etc/audio_effects.xml \
    /vendor/etc/audio_effects.conf \
    /odm/etc/audio_effects.xml \
    /product/etc/audio_effects.xml \
    /system_ext/etc/audio_effects.xml
  do
    if [ -f "$path" ]; then
      if grep -q "qwulivoice_preprocess" "$path" 2>/dev/null; then
        log_line "patched=$path"
      else
        log_line "unpatched=$path"
      fi
    fi
  done
  for path in \
    /vendor/lib/soundfx/libqwv_preprocess.so \
    /vendor/lib64/soundfx/libqwv_preprocess.so \
    /system/vendor/lib/soundfx/libqwv_preprocess.so \
    /system/vendor/lib64/soundfx/libqwv_preprocess.so
  do
    [ -f "$path" ] && log_line "library=$path"
  done
}

restart_audio_stack_once() {
  (
    sleep 12
    [ -e "$BOOT_RESTART_MARK" ] && exit 0
    touch "$BOOT_RESTART_MARK"
    log_line "restarting audioserver/audio HAL once to load patched input effects"
    killall audioserver 2>/dev/null
    killall mediaserver 2>/dev/null
    killall android.hardware.audio@4.0-service-mediatek 2>/dev/null
    killall android.hardware.audio.service 2>/dev/null
    killall vendor.audio-hal-2-0 2>/dev/null
    killall vendor.audio-hal 2>/dev/null
  ) &
}

log_line "qwulivoice root audio service started"
sync_config
sync_soundpad_state
sync_soundpad_pcm
log_audio_state
restart_audio_stack_once

counter=0
while true
do
  sync_config
  sync_soundpad_state
  if [ $((counter % 8)) -eq 0 ]; then
    sync_soundpad_pcm
  fi
  counter=$((counter + 1))
  sleep 2
done
