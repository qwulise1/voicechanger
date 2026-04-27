#!/system/bin/sh

QWV_EFFECT="qwulivoice_preprocess"
QWV_UUID="b4972f02-3d78-4c59-8d1c-7f5f37f8aa52"
QWV_LIBDIR="${LIBDIR:-/vendor/lib/soundfx}"
QWV_LIBPATH="${QWV_LIBDIR}/libqwv_preprocess.so"

for source in mic voice_communication camcorder voice_recognition unprocessed voice_performance voice_call voice_uplink voice_downlink
do
  patch_cfgs -qle "$source" "$QWV_EFFECT" "$QWV_UUID" "$QWV_EFFECT" "$QWV_LIBPATH"
done
