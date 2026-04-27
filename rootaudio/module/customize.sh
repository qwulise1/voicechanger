#!/system/bin/sh

MODID="qwulivoice_root_audio"
EFFECT_NAME="qwulivoice_preprocess"
EFFECT_LIB="libqwv_preprocess.so"
EFFECT_UUID="b4972f02-3d78-4c59-8d1c-7f5f37f8aa52"
DEST_XML="$MODPATH/system/vendor/etc/audio_effects.xml"
DEST_PROPS="$MODPATH/system/vendor/etc/qwulivoice.properties"

ui_print "qwulivoice root audio layer"
ui_print "Installing systemless input pre-processing effect"

mkdir -p "$MODPATH/system/vendor/etc"

SRC_XML=""
for candidate in \
  /vendor/etc/audio_effects.xml \
  /odm/etc/audio_effects.xml \
  /system/vendor/etc/audio_effects.xml \
  /system/etc/audio_effects.xml
do
  if [ -f "$candidate" ]; then
    SRC_XML="$candidate"
    break
  fi
done

if [ -n "$SRC_XML" ]; then
  cp -af "$SRC_XML" "$DEST_XML"
else
  cat > "$DEST_XML" <<EOF
<audio_effects_conf version="2.0">
    <libraries>
    </libraries>
    <effects>
    </effects>
</audio_effects_conf>
EOF
fi

if ! grep -q "$EFFECT_NAME" "$DEST_XML"; then
  TMP_XML="$MODPATH/audio_effects.xml.tmp"
  awk -v effect="$EFFECT_NAME" -v lib="$EFFECT_LIB" -v uuid="$EFFECT_UUID" '
    BEGIN { saw_pre = 0; inserted_pre = 0; saw_device = 0; inserted_device = 0 }
    /<libraries>/ {
      print
      print "        <library name=\"" effect "\" path=\"" lib "\"/>"
      next
    }
    /<effects>/ {
      print
      print "        <effect name=\"" effect "\" library=\"" effect "\" uuid=\"" uuid "\"/>"
      next
    }
    /<preprocess>/ { saw_pre = 1 }
    /<\/preprocess>/ && inserted_pre == 0 {
      print "        <stream type=\"mic\">"
      print "            <apply effect=\"" effect "\"/>"
      print "        </stream>"
      print "        <stream type=\"voice_communication\">"
      print "            <apply effect=\"" effect "\"/>"
      print "        </stream>"
      print "        <stream type=\"camcorder\">"
      print "            <apply effect=\"" effect "\"/>"
      print "        </stream>"
      inserted_pre = 1
    }
    /<deviceEffects>/ { saw_device = 1 }
    /<\/deviceEffects>/ && inserted_device == 0 {
      print "        <devicePort type=\"AUDIO_DEVICE_IN_BUILTIN_MIC\">"
      print "            <apply effect=\"" effect "\"/>"
      print "        </devicePort>"
      print "        <devicePort type=\"AUDIO_DEVICE_IN_BACK_MIC\">"
      print "            <apply effect=\"" effect "\"/>"
      print "        </devicePort>"
      inserted_device = 1
    }
    /<\/audio_effects_conf>/ {
      if (saw_pre == 0) {
        print "    <preprocess>"
        print "        <stream type=\"mic\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </stream>"
        print "        <stream type=\"voice_communication\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </stream>"
        print "        <stream type=\"camcorder\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </stream>"
        print "    </preprocess>"
      }
      if (saw_device == 0) {
        print "    <deviceEffects>"
        print "        <devicePort type=\"AUDIO_DEVICE_IN_BUILTIN_MIC\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </devicePort>"
        print "        <devicePort type=\"AUDIO_DEVICE_IN_BACK_MIC\">"
        print "            <apply effect=\"" effect "\"/>"
        print "        </devicePort>"
        print "    </deviceEffects>"
      }
      next
    }
    { print }
  ' "$DEST_XML" > "$TMP_XML" && mv "$TMP_XML" "$DEST_XML"
fi

if [ ! -f "$DEST_PROPS" ]; then
  cat > "$DEST_PROPS" <<EOF
# qwulivoice root audio fallback config
enabled=false
mode_id=original
effect_strength=85
mic_gain_percent=0
EOF
fi

chmod 0644 "$DEST_XML" "$DEST_PROPS"

ui_print "Installed patched audio_effects.xml"
ui_print "Reboot is required"
