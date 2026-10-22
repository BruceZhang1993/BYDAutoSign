#!/system/bin/sh
# Invoked with root by the single APK. Never wipe existing byd_sign user data.
set -eu
SRC="$1"
D=/data/adb/byd_sign
BOOT=/data/adb/service.d/byd_auto_sign.sh
mkdir -p "$D" "$D/logs" "$D/cron.d" "$D/sessions" /data/adb/service.d
chmod 700 "$D" "$D/logs" "$D/cron.d" "$D/sessions"
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
# Migrate very old JSON port configuration without dropping any original data.
if grep -q '^[[:space:]]*{' "$D/code_api.conf" 2>/dev/null; then
  old_ports=$(sed -n 's/.*"ports"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p' "$D/code_api.conf" | tr -d '"' | tr ',' ' ' | xargs 2>/dev/null || true)
  old_wake=$(sed -n 's/.*"force_foreground_wake"[[:space:]]*:[[:space:]]*\([^,}]*\).*/\1/p' "$D/code_api.conf" | tr -d ' "[:space:]')
  [ -n "$old_ports" ] || old_ports=8088
  [ "$old_wake" = true ] || old_wake=false
  cp "$D/code_api.conf" "$D/code_api.conf.legacy-json.bak"
  printf 'ports=%s\nforce_foreground_wake=%s\n' "$(echo "$old_ports" | tr ' ' ',')" "$old_wake" > "$D/code_api.conf"
fi
if [ ! -f "$D/email.conf" ]; then
  if [ -f /sdcard/Android/byd_sign/email.conf ]; then
    cp /sdcard/Android/byd_sign/email.conf "$D/email.conf"
  else
    cp "$SRC/email.conf" "$D/email.conf"
  fi
fi
chmod 600 "$D/code_api.conf" "$D/email.conf"
if [ ! -s "$D/cron.d/root" ]; then
  if [ -f "$D/cron.d/root" ]; then
    cp "$D/cron.d/root" "$D/cron.d/root.empty.bak"
  fi
  printf '1 0,7,13,22 * * * %s/byd_sign.sh > /dev/null 2>&1\n' "$D" > "$D/cron.d/root"
fi
chmod 600 "$D/cron.d/root"
if ! grep -Fq "$D/byd_sign.sh" "$D/cron.d/root"; then
  echo "WARNING: existing root crontab does not schedule BYDAutoSign; user-defined schedule preserved."
fi
cp "$SRC/root-boot.sh" "$BOOT"
chmod 755 "$BOOT"
command -v restorecon >/dev/null 2>&1 && restorecon "$BOOT" "$D" 2>/dev/null || true
if [ -d /data/adb/modules/byd_sign ] && [ ! -f /data/adb/modules/byd_sign/disable ]; then
  echo "WARNING: Old byd_sign root module is still enabled; disable it to prevent boot-service conflicts."
fi
sh "$BOOT"
echo 'INSTALL_OK'
