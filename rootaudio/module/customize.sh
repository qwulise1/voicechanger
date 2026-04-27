#!/system/bin/sh

MODID="qwulivoice_root_audio"
EFFECT_NAME="qwulivoice_preprocess"
EFFECT_LIB="libqwv_preprocess.so"
EFFECT_UUID="b4972f02-3d78-4c59-8d1c-7f5f37f8aa52"
EFFECT_LIB_PATH="/vendor/lib/soundfx/${EFFECT_LIB}"
EFFECT_SOURCES="mic,voice_communication,camcorder,voice_recognition,unprocessed,voice_performance,voice_call,voice_uplink,voice_downlink"
EFFECT_DEVICES="AUDIO_DEVICE_IN_BUILTIN_MIC,AUDIO_DEVICE_IN_BACK_MIC,AUDIO_DEVICE_IN_WIRED_HEADSET,AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET,AUDIO_DEVICE_IN_USB_DEVICE,AUDIO_DEVICE_IN_USB_HEADSET"
DEST_PROPS="$MODPATH/system/vendor/etc/qwulivoice.properties"
PATCHED_COUNT=0

ui_print "qwulivoice root audio layer"
ui_print "Installing systemless input pre-processing effect"

dest_for_source() {
  case "$1" in
    /vendor/etc/*) echo "$MODPATH/system/vendor/etc/${1#/vendor/etc/}" ;;
    /odm/etc/*) echo "$MODPATH/system/odm/etc/${1#/odm/etc/}" ;;
    /product/etc/*) echo "$MODPATH/system/product/etc/${1#/product/etc/}" ;;
    /system_ext/etc/*) echo "$MODPATH/system/system_ext/etc/${1#/system_ext/etc/}" ;;
    /system/my_product/etc/*) echo "$MODPATH/system/my_product/etc/${1#/system/my_product/etc/}" ;;
    /system/etc/*) echo "$MODPATH/system/etc/${1#/system/etc/}" ;;
    *) echo "$MODPATH/system/vendor/etc/$(basename "$1")" ;;
  esac
}

write_minimal_xml() {
  cat > "$1" <<EOF
<audio_effects_conf version="2.0">
    <libraries>
    </libraries>
    <effects>
    </effects>
</audio_effects_conf>
EOF
}

write_minimal_conf() {
  cat > "$1" <<EOF
libraries {
}
effects {
}
EOF
}

patch_xml() {
  local file="$1"
  if grep -q "$EFFECT_NAME" "$file" 2>/dev/null; then
    return 0
  fi

  local tmp="$file.tmp"
  awk -v effect="$EFFECT_NAME" -v lib="$EFFECT_LIB" -v uuid="$EFFECT_UUID" -v sources="$EFFECT_SOURCES" -v devices="$EFFECT_DEVICES" '
    function print_sources(    n, arr, i) {
      n = split(sources, arr, ",")
      for (i = 1; i <= n; i++) {
        print "        <stream type=\"" arr[i] "\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </stream>"
      }
    }
    function print_devices(    n, arr, i) {
      n = split(devices, arr, ",")
      for (i = 1; i <= n; i++) {
        print "        <devicePort type=\"" arr[i] "\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </devicePort>"
      }
    }
    /<libraries>/ {
      saw_lib = 1
      print
      print "        <library name=\"" effect "\" path=\"" lib "\"/>"
      next
    }
    /<effects>/ {
      saw_effects = 1
      print
      print "        <effect name=\"" effect "\" library=\"" effect "\" uuid=\"" uuid "\"/>"
      next
    }
    /<preprocess>/ { saw_pre = 1 }
    /<\/preprocess>/ && inserted_pre == 0 {
      print_sources()
      inserted_pre = 1
    }
    /<deviceEffects>/ { saw_device = 1 }
    /<\/deviceEffects>/ && inserted_device == 0 {
      print_devices()
      inserted_device = 1
    }
    /<\/audio_effects_conf>/ {
      if (saw_lib == 0) {
        print "    <libraries>"
        print "        <library name=\"" effect "\" path=\"" lib "\"/>"
        print "    </libraries>"
      }
      if (saw_effects == 0) {
        print "    <effects>"
        print "        <effect name=\"" effect "\" library=\"" effect "\" uuid=\"" uuid "\"/>"
        print "    </effects>"
      }
      if (saw_pre == 0) {
        print "    <preprocess>"
        print_sources()
        print "    </preprocess>"
      }
      if (saw_device == 0) {
        print "    <deviceEffects>"
        print_devices()
        print "    </deviceEffects>"
      }
      print
      next
    }
    { print }
  ' "$file" > "$tmp" && mv "$tmp" "$file"
  PATCHED_COUNT=$((PATCHED_COUNT + 1))
}

patch_conf() {
  local file="$1"
  if grep -q "$EFFECT_NAME" "$file" 2>/dev/null; then
    return 0
  fi

  local tmp="$file.tmp"
  awk -v effect="$EFFECT_NAME" -v uuid="$EFFECT_UUID" -v libpath="$EFFECT_LIB_PATH" -v sources="$EFFECT_SOURCES" '
    function braces(line, ch,    copy) {
      copy = line
      gsub("[^" ch "]", "", copy)
      return length(copy)
    }
    function print_library() {
      print "  " effect " {"
      print "    path " libpath
      print "  }"
    }
    function print_effect() {
      print "  " effect " {"
      print "    library " effect
      print "    uuid " uuid
      print "  }"
    }
    function print_source_blocks(    n, arr, i) {
      n = split(sources, arr, ",")
      for (i = 1; i <= n; i++) {
        print "    " arr[i] " {"
        print "        " effect " {"
        print "        }"
        print "    }"
      }
    }
    function print_preprocessing() {
      print "pre_processing {"
      print_source_blocks()
      print "}"
    }
    /^[[:space:]]*libraries[[:space:]]*\{/ && inserted_lib == 0 {
      print
      print_library()
      inserted_lib = 1
      next
    }
    /^[[:space:]]*effects[[:space:]]*\{/ && inserted_effect == 0 {
      print
      print_effect()
      inserted_effect = 1
      next
    }
    {
      if ($0 ~ /^[[:space:]]*pre_processing[[:space:]]*\{/) {
        saw_pre = 1
        in_pre = 1
        pre_depth = 0
      }
      if (in_pre == 1) {
        delta = braces($0, "{") - braces($0, "}")
        if (pre_depth + delta <= 0 && inserted_pre == 0) {
          print_source_blocks()
          inserted_pre = 1
        }
        print
        pre_depth += delta
        if (pre_depth <= 0) {
          in_pre = 0
        }
        next
      }
      print
    }
    END {
      if (inserted_lib == 0) {
        print "libraries {"
        print_library()
        print "}"
      }
      if (inserted_effect == 0) {
        print "effects {"
        print_effect()
        print "}"
      }
      if (saw_pre == 0) {
        print_preprocessing()
      }
    }
  ' "$file" > "$tmp" && mv "$tmp" "$file"
  PATCHED_COUNT=$((PATCHED_COUNT + 1))
}

copy_and_patch_xml() {
  local src="$1"
  local dest
  dest="$(dest_for_source "$src")"
  mkdir -p "$(dirname "$dest")"
  cp -af "$src" "$dest"
  patch_xml "$dest"
}

copy_and_patch_conf() {
  local src="$1"
  local dest
  dest="$(dest_for_source "$src")"
  mkdir -p "$(dirname "$dest")"
  cp -af "$src" "$dest"
  patch_conf "$dest"
}

FOUND_XML=0
for src in \
  /vendor/etc/audio_effects.xml \
  /odm/etc/audio_effects.xml \
  /product/etc/audio_effects.xml \
  /system_ext/etc/audio_effects.xml \
  /system/my_product/etc/audio_effects.xml \
  /system/etc/audio_effects.xml \
  /system/vendor/etc/audio_effects.xml
do
  if [ -f "$src" ]; then
    copy_and_patch_xml "$src"
    FOUND_XML=1
  fi
done

if [ "$FOUND_XML" -eq 0 ]; then
  fallback_xml="$MODPATH/system/vendor/etc/audio_effects.xml"
  mkdir -p "$(dirname "$fallback_xml")"
  write_minimal_xml "$fallback_xml"
  patch_xml "$fallback_xml"
fi

FOUND_CONF=0
for src in \
  /vendor/etc/audio_effects.conf \
  /odm/etc/audio_effects.conf \
  /product/etc/audio_effects.conf \
  /system_ext/etc/audio_effects.conf \
  /system/my_product/etc/audio_effects.conf \
  /system/etc/audio_effects.conf \
  /system/vendor/etc/audio_effects.conf
do
  if [ -f "$src" ]; then
    copy_and_patch_conf "$src"
    FOUND_CONF=1
  fi
done

if [ "$FOUND_CONF" -eq 0 ]; then
  fallback_conf="$MODPATH/system/vendor/etc/audio_effects.conf"
  mkdir -p "$(dirname "$fallback_conf")"
  write_minimal_conf "$fallback_conf"
  patch_conf "$fallback_conf"
fi

mkdir -p "$(dirname "$DEST_PROPS")"
if [ ! -f "$DEST_PROPS" ]; then
  cat > "$DEST_PROPS" <<EOF
# qwulivoice root audio fallback config
enabled=false
mode_id=original
effect_strength=85
mic_gain_percent=0
EOF
fi

find "$MODPATH/system" -type f \( -name "audio_effects.xml" -o -name "audio_effects.conf" -o -name "qwulivoice.properties" \) -exec chmod 0644 {} \; 2>/dev/null

ui_print "Patched ${PATCHED_COUNT} audio effect config(s)"
ui_print "Reboot is required"
