#!/usr/bin/env python3
"""Binder-only contract tests. No private credentials or live account required."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
runtime = ROOT / "app/src/main/assets/runtime"
source = (runtime / "byd_sign.sh").read_text(encoding="utf-8")
lib = (runtime / "lib.sh").read_text(encoding="utf-8")
webui = (runtime / "webui_api.sh").read_text(encoding="utf-8")
hook = (ROOT / "app/src/main/java/xiaojw/hook/WxLoginHook.java").read_text(encoding="utf-8")
broker = (ROOT / "app/src/main/java/com/brucezhang/bydautosign/ipc/IpcBrokerService.java").read_text(encoding="utf-8")
ui = (ROOT / "app/src/main/java/com/brucezhang/bydautosign/RootBridge.kt").read_text(encoding="utf-8")
aidl = (ROOT / "app/src/main/aidl/com/brucezhang/bydautosign/ipc/IHookEndpoint.aidl").read_text(encoding="utf-8")

def extract(name):
    m = re.search(r"(?ms)^" + re.escape(name) + r"\(\) \{\n.*?^\}\n", source)
    assert m, f"Missing shell function: {name}"
    return m.group()

for method in ("valid_port", "session_path", "save_session", "load_session",
               "forget_session", "session_status", "authorize_from_stdin"):
    assert extract(method)

# Do not silently retain any local HTTP login interfaces or build dependencies.
for name, content in (("sign", source), ("lib", lib), ("webui", webui),
                      ("Hook", hook), ("RootBridge", ui)):
    for forbidden in ("http://127.0.0.1", "get_code_from_api", "LoginHttpServer",
                      '"/login"', "HttpURLConnection"):
        assert forbidden not in content, f"{name} still contains {forbidden}"
assert not (ROOT / "app/src/main/java/xiaojw/hook/LoginHttpServer.java").exists()
assert "org.nanohttpd:nanohttpd" not in (ROOT / "app/build.gradle").read_text()
assert "usesCleartextTraffic=\"true\"" not in (ROOT / "app/src/main/AndroidManifest.xml").read_text()
for method in ("requestLoginCode", "getHookStatus", "triggerProbe"):
    assert method in aidl and method in hook or (method in aidl and method in broker)
assert 'authorize) authorize;' not in source
assert 'authorize-stdin) authorize_from_stdin "$2"' in source
assert 'wake-wechat) wake_wechat_silent;' in source
assert "IpcBrokerService.obtainCode" in ui
assert "IpcBrokerService.readHookStatus" in ui
assert "IpcBrokerService.requestProbe" in ui

# A sign-only path may never initiate fresh authentication, wake WeChat or
# call the BYD one-time code exchange endpoint.
sign = source[source.index("        # Sign-only:"):source.index("    # 汇总通知", source.index("        # Sign-only:"))]
for forbidden in ("wake_wechat", "get_code", "get_session_id", "am start", "mina.decryptCode"):
    assert forbidden not in sign, f"Sign-only path invokes authorization: {forbidden}"
assert 'session_id=$(load_session "$port")' in sign
assert '"FAILED|AUTH_EXPIRED"' in source

# Exercise the actual extracted private-session functions and stdin path with
# mocked BYD response. Never use a real secret in test fixtures.
functions = "\n".join(extract(name) for name in (
    "valid_port", "session_path", "save_session", "load_session",
    "forget_session", "session_status", "authorize_from_stdin"))
with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    shell = (
        'set -eu\n'
        'WORK_DIR="$1"\n'
        'LOG_DIR="$WORK_DIR/logs"\n'
        'mkdir -p "$LOG_DIR"\n'
        'SESSION_DIR="$WORK_DIR/sessions"\n'
        'get_code_api_ports() { echo "8088"; }\n'
        'log() { printf "%s\\n" "$1" >&2; }\n'
        '_get_session_id_checked() { [ "$1" = "one-use-code-test" ] && printf "server-session-%s" "$2"; }\n'
        'retry_command() { shift 2; RETRY_OUTPUT=$("$@"); }\n'
        + functions + '\n'
        'printf "one-use-code-test\\n" | authorize_from_stdin 8088\n'
        '[ "$(load_session 8088)" = "server-session-8088" ]\n'
        '[ "$(cat "$SESSION_DIR/8088.session.via")" = binder ]\n'
        'session_status\n'
        'if session_path "../8088"; then exit 3; fi\n'
        'forget_session 8088\n'
        '[ ! -e "$SESSION_DIR/8088.session.via" ]\n'
    )
    path = directory / "test.sh"
    path.write_text(shell, encoding="utf-8")
    result = subprocess.run(["sh", str(path), str(directory)], capture_output=True,
                            text=True, timeout=15)
    assert result.returncode == 0, result.stderr
    assert "one-use-code-test" not in result.stdout + result.stderr
    assert "server-session" not in result.stdout + result.stderr

print("Binder-only authorization, Root stdin session storage, and sign-only checks passed.")
