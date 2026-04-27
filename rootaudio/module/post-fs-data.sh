#!/system/bin/sh

MODDIR="${0%/*}"
mkdir -p /data/adb/qwulivoice
mkdir -p /data/adb/qwulivoice/soundpad
chmod 0755 /data/adb/qwulivoice
chmod 0755 /data/adb/qwulivoice/soundpad

if [ -f "$MODDIR/system/vendor/etc/qwulivoice.properties" ]; then
  chmod 0644 "$MODDIR/system/vendor/etc/qwulivoice.properties"
fi
