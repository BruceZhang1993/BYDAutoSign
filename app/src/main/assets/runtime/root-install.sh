#!/system/bin/sh
# Invoked with root by the single APK. Never wipe existing byd_sign user data.
set -eu
SRC="$1"
D=/data/adb/byd_sign
BOOT=/data/adb/service.d/byd_auto_sign.sh
mkdir -p "$D" "$D/logs" "$D/cron.d" /data/adb/service.d
chmod 700 "$D" "$D/logs" "$D/cron.d"
for name in byd_sign.sh lib.sh webui_api.sh curl openssl; do
  test -s "$SRC/$name" || { echo "MISSING:$name"; exit 2; }
  cp "$SRC/$name" "$D/$name"
  chmod 700 "$D/$name"
done

# Preserve previous configs, state, and sign history.
if [ ! -f "$D/code_api.conf" ]; then
  if [ -f /sdcard/Android/byd_sign/code_api.conf ]; then
    cp /sdcard/Android/byd_sign/code_api.conf "$D/code_api.conf"
  else
    printf 'ports=8088\nforce_foreground_wake=false\n' > "$D/code_api.conf"
  fi
fi
if [ ! -f "$D/email.conf" ]; then
  if [ -f /sdcard/Android/byd_sign/email.conf ]; then
    cp /sdcard/Android/byd_sign/email.conf "$D/email.conf"
  else
    cp "$SRC/email.conf" "$D/email.conf"
  fi
fi
chmod 600 "$D/code_api.conf" "$D/email.conf"
if [ ! -f "$D/cron.d/root" ]; then
  printf '1 0,7,13,22 * * * %s/byd_sign.sh > /dev/null 2>&1\n' "$D" > "$D/cron.d/root"
fi
chmod 600 "$D/cron.d/root"
cp "$SRC/root-boot.sh" "$BOOT"
chmod 755 "$BOOT"
command -v restorecon >/dev/null 2>&1 && restorecon "$BOOT" "$D" 2>/dev/null || true
if [ -d /data/adb/modules/byd_sign ] && [ ! -f /data/adb/modules/byd_sign/disable ]; then
  echo "WARNING: Old byd_sign root module is still enabled; disable it to prevent boot-service conflicts."
fi
sh "$BOOT"
echo 'INSTALL_OK'
