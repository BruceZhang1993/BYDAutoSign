#!/system/bin/sh
# Magisk / KernelSU / APatch service.d; no dependency on the application process.
D=/data/adb/byd_sign
if [ ! -f "$D/lib.sh" ]; then
    echo "CRON_ERROR: Root runtime not installed" >&2
    exit 1
fi
. "$D/lib.sh"

# Read-only health check, safe to call from UI without restarting daemon.
if [ "$1" = "status" ]; then
    if cron_running; then
        echo "CRON_RUNNING"
        echo "CRON_SCHEDULE:"
        sed -n '/^[[:space:]]*#/d;/^[[:space:]]*$/d;p' "$D/cron.d/root" 2>/dev/null
        exit 0
    fi
    echo "CRON_STOPPED"
    exit 3
fi

mkdir -p "$D/logs" "$D/cron.d" || exit 1
SCHEDULER_LOG="$D/logs/scheduler.log"
# Keep startup errors for diagnosis, including after a reboot.
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting BYD crond" >> "$SCHEDULER_LOG"
if restart_crond >> "$SCHEDULER_LOG" 2>&1; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] CRON_STARTED" >> "$SCHEDULER_LOG"
    echo "CRON_STARTED"
else
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] CRON_FAILED" >> "$SCHEDULER_LOG"
    echo "CRON_FAILED: see $SCHEDULER_LOG" >&2
    exit 1
fi
