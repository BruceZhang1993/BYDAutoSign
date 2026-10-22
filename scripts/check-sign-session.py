#!/usr/bin/env python3
"""Test that authorization owns WeChat and sign-only only reads cached sessions."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[1] /
          "app/src/main/assets/runtime/byd_sign.sh").read_text(encoding="utf-8")

def extract(name: str) -> str:
    m = re.search(r"(?ms)^" + re.escape(name) + r"\(\) \{\n.*?^\}\n", source)
    assert m, f"Missing shell function {name}"
    return m.group()

for method in ("valid_port", "session_path", "save_session", "load_session",
               "forget_session", "session_status", "authorize"):
    assert extract(method)

sign = source[source.index("        # Sign-only:"):source.index("    # 汇总通知", source.index("        # Sign-only:"))]
for forbidden in ("wake_wechat", "get_code", "get_session_id",
                  '"/login"', "mina.decryptCode", "am start"):
    assert forbidden not in sign, f"Sign path invokes authorization: {forbidden}"
assert 'authorize) authorize; return $?' in source
assert 'status) session_status; return 0' in source
assert 'session_id=$(load_session "$port")' in source
assert '"FAILED|AUTH_EXPIRED"' in source

functions = "\n".join(extract(name) for name in
    ("valid_port", "session_path", "save_session", "load_session",
     "forget_session", "session_status", "authorize"))
with tempfile.TemporaryDirectory() as temporary:
    runtime = Path(temporary)
    shell = (
        'set -eu\n'
        'WORK_DIR="$1"\n'
        'LOG_DIR="$WORK_DIR/logs"\n'
        'mkdir -p "$LOG_DIR"\n'
        'SESSION_DIR="$WORK_DIR/sessions"\n'
        'get_code_api_ports() { echo "8088 8098"; }\n'
        'wake_wechat_silent() { echo WAKE >> "$WORK_DIR/events"; }\n'
        'log() { printf "%s\\n" "$1" >&2; }\n'
        '_get_code_checked() { printf "one-use-code-%s" "$1"; }\n'
        '_get_session_id_checked() { printf "server-session-%s" "$2"; }\n'
        'retry_command() { shift 2; RETRY_OUTPUT=$("$@"); }\n'
        + functions + '\n'
        'authorize\n'
        'session_status\n'
        '[ "$(load_session 8088)" = "server-session-8088" ]\n'
        '[ "$(load_session 8098)" = "server-session-8098" ]\n'
        'if load_session "../8088"; then exit 2; fi\n'
        'forget_session 8088\n'
        'if load_session 8088; then exit 3; fi\n'
    )
    runner = runtime / "test.sh"
    runner.write_text(shell, encoding="utf-8")
    result = subprocess.run(["sh", str(runner), str(runtime)],
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    session = runtime / "sessions/8098.session"
    assert session.read_text() == "server-session-8098"
    assert (session.stat().st_mode & 0o777) == 0o600, oct(session.stat().st_mode)
    assert (session.parent.stat().st_mode & 0o777) == 0o700
    assert "one-use-code" not in session.read_text()
    assert "server-session" not in result.stdout
    assert "WAKE" in (runtime / "events").read_text()

print("Authorization-only WeChat access, root credential permissions and sign-only regression passed.")
