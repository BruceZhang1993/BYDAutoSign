#!/system/bin/sh
# Magisk / KernelSU / APatch service.d entry point; survives app process death.
D=/data/adb/byd_sign
[ -f "$D/lib.sh" ] || exit 0
. "$D/lib.sh"
mkdir -p "$D/logs" "$D/cron.d"
find_busybox
if [ "$BUSYBOX" = "busybox" ]; then
  command -v busybox >/dev/null 2>&1 || { echo "BusyBox not found; cannot schedule byd_sign" >&2; exit 1; }
elif [ ! -x "$BUSYBOX" ]; then
  echo "BusyBox is not executable: $BUSYBOX" >&2
  exit 1
fi
restart_crond
echo 'CRON_STARTED'
