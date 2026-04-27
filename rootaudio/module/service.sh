#!/system/bin/sh

MODDIR="${0%/*}"
APP_CONFIG="/data/local/tmp/qwulivoice-com.qwulivoice.beta.properties"
ADB_CONFIG="/data/adb/qwulivoice/config.properties"
VENDOR_CONFIG="$MODDIR/system/vendor/etc/qwulivoice.properties"
LOG_FILE="/data/adb/qwulivoice/root-audio.log"

mkdir -p /data/adb/qwulivoice

sync_config() {
  if [ -f "$APP_CONFIG" ]; then
    cp -af "$APP_CONFIG" "$ADB_CONFIG"
    cp -af "$APP_CONFIG" "$VENDOR_CONFIG"
  elif [ -f "$ADB_CONFIG" ]; then
    cp -af "$ADB_CONFIG" "$VENDOR_CONFIG"
  fi
  chmod 0644 "$ADB_CONFIG" "$VENDOR_CONFIG" 2>/dev/null
}

{
  echo "$(date '+%Y-%m-%d %H:%M:%S') qwulivoice root audio service started"
  echo "module=$MODDIR"
} >> "$LOG_FILE"

sync_config

while true
do
  sync_config
  sleep 2
done
