#!/usr/bin/env python3
"""Stage-1 regression guard: validate embedded WeChat mappings against pinned upstream configs."""
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / "app/src/main/java/xiaojw/hook/WxLoginHook.java").read_text(encoding="utf-8")
server = (root / "app/src/main/java/xiaojw/hook/LoginHttpServer.java").read_text(encoding="utf-8")
script = (root / "app/src/main/assets/runtime/byd_sign.sh").read_text(encoding="utf-8")
match = re.search(r'private String jsonString = """\s*(\{.*?\})""";', source, re.S)
assert match, "missing embedded version table"
versions = json.loads(match.group(1))
known = {
    "8.0.77": ("ho0.j1", "cn0.c", "e"),
    "8.0.78": ("gp0.j1", "bo0.c", "e"),
    "8.0.79": ("pp0.i1", "ko0.c", "e"),
}
for version, (j1, model, static) in known.items():
    entry = versions[version]
    assert entry["j1"] == j1 and entry["c"] == model, version
    assert entry["a1"] == "plugin.appbrand.jsapi.auth.i2", version
    assert entry["a7"] == "plugin.appbrand.jsapi.auth.m2", version
    assert entry["j1_static_method"] == static, version
    assert entry.get("j1_instance_method", versions["common"]["j1_instance_method"]) == "g", version

# Explicit fail-fast instead of applying the 8.0.62 default on unknown releases.
assert "HOOK_MAPPING_NOT_READY" in source
assert "HOOK_CLASS_NOT_FOUND" in source
assert 'private String j1 = "";' in source
assert 'configSupported = true' in source and 'configSupported = false' in source
assert 'info.put("configSupported", ctx.configSupported)' in server
assert '[ "$fail_count" -eq 0 ] || return 1' in script
print("WeChat 8.0.77–8.0.79 mapping, diagnostics and exit-status checks passed")

# Dynamic DEX resolver is asynchronous, content-keyed, and structurally validated.
probe = (root / "app/src/main/java/xiaojw/hook/DexProbe.java").read_text(encoding="utf-8")
analyzer = (root / "app/src/main/java/xiaojw/hook/JsApiLoginAnalyzer.java").read_text(encoding="utf-8")
mapping = (root / "app/src/main/java/xiaojw/hook/HookMap.java").read_text(encoding="utf-8")
http = (root / "app/src/main/java/xiaojw/hook/LoginHttpServer.java").read_text(encoding="utf-8")
assert "JsApiLoginAnalyzer.analyze(ctx, play)" in probe
assert "forEachDex" in probe and "runtimeDexPaths" in probe
assert "invoke" in analyzer.lower()
assert "MessageDigest.getInstance(\"SHA-256\")" in mapping
assert "getCrc()" in mapping and "lastUpdateTime" in mapping
assert "hasRequestCtor" in mapping and "hasSubmitChain" in mapping
assert 'new Thread(() ->' in source and "HookMap.validate" in source
assert 'probeRunning = false' in source and "probeLock.notifyAll()" in source
assert 'uri.equals("/probe")' in http
print("Dynamic DEX analysis, structural gate, cache invalidation and manual probe checks passed")
