#!/usr/bin/env python3
"""Scheduler identity and recovery checks without Root or a device."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
lib = (root / "app/src/main/assets/runtime/lib.sh").read_text(encoding="utf-8")
boot = (root / "app/src/main/assets/runtime/root-boot.sh").read_text(encoding="utf-8")
installer = (root / "app/src/main/assets/runtime/root-install.sh").read_text(encoding="utf-8")
bridge = (root / "app/src/main/java/com/brucezhang/bydautosign/RootBridge.kt").read_text(encoding="utf-8")

def extract(name):
    found = re.search(r"(?ms)^" + re.escape(name) + r"\(\) \{\n.*?^\}\n", lib)
    assert found, f"Missing scheduler function: {name}"
    return found.group()

function = extract("is_own_crond")
script = (
    'set -eu\n'
    'WORK_DIR=/data/adb/byd_sign\n'
    + function + '\n'
    'is_own_crond "busybox crond -b -c /data/adb/byd_sign/cron.d -L /tmp/cron.log " / busybox\n'
    'is_own_crond "crond -c /data/adb/byd_sign/cron.d " / crond\n'
    'is_own_crond "busybox crond " /data/adb/byd_sign/cron.d busybox\n'
    'if is_own_crond "busybox crond " /data/adb/some-other-module/cron.d busybox; then exit 11; fi\n'
    'if is_own_crond "busybox crond -c /data/adb/byd_sign/cron.d " / sh; then exit 12; fi\n'
    'if is_own_crond "busybox crond -c /data/adb/other/cron.d " / busybox; then exit 13; fi\n'
)
result = subprocess.run(["sh", "-c", script], capture_output=True, text=True, timeout=10)
assert result.returncode == 0, result.stderr or f"process classification failed: {result.returncode}"

assert "readlink" in extract("own_crond_pids")
assert "find_crond_busybox" in extract("restart_crond")
assert "CRON_ERROR:" in extract("restart_crond")
assert "cron_running" in boot and "CRON_STOPPED" in boot
assert "root-boot.sh" in installer
assert "restartScheduler" in bridge and "cronRunning" in bridge
assert "CRON_STARTED" in boot
print("Cron process identity, health probe and restart invariants passed.")
