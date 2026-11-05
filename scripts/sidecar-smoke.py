"""Run only on GitHub Actions against the CI-built JVM sidecar."""
import json
import os
import queue
import secrets
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import zipfile

# A runnable fat jar must preserve every provider, including duplicate service paths.
with zipfile.ZipFile(sys.argv[1]) as jar:
    names = jar.namelist()
    services = [name for name in names if name.startswith("META-INF/services/") and not name.endswith("/")]
    assert services, "fat jar contains no service providers"
    assert len(services) == len(set(services)), "duplicate service descriptors"
    for name in services:
        providers = [line.split("#", 1)[0].strip() for line in jar.read(name).decode("utf-8").splitlines()]
        providers = [provider for provider in providers if provider]
        assert providers and len(providers) == len(set(providers)), name
        for provider in providers:
            assert provider.replace(".", "/") + ".class" in names, (name, provider)

with tempfile.TemporaryDirectory(prefix="parsehub-smoke-") as data:
    token = secrets.token_hex(32)
    env = dict(os.environ, PARSEHUB_SESSION_TOKEN=token, PARSEHUB_DATA_DIR=data)
    proc = subprocess.Popen(["java", f"-Duser.home={data}", "-jar", sys.argv[1]], env=env, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, text=True, encoding="utf-8")
    messages = queue.Queue()
    def consume():
        for line in proc.stdout:
            try:
                value = json.loads(line)
                if value.get("event") == "ready":
                    messages.put(value)
            except (ValueError, AttributeError):
                pass
    threading.Thread(target=consume, daemon=True).start()
    try:
        ready = messages.get(timeout=90)
        base = f"http://127.0.0.1:{ready['port']}"
        def request(path, method="GET", body=None, auth=token):
            headers = {"Authorization": f"Bearer {auth}", "Content-Type": "application/json"}
            payload = json.dumps(body).encode() if body is not None else None
            with urllib.request.urlopen(urllib.request.Request(base + path, payload, headers, method=method), timeout=20) as response:
                return json.load(response)
        assert ready["protocol"] == 1
        assert request("/api/health") == {"status": "ok", "protocol": 1}
        try:
            request("/api/health", auth="bad")
            raise AssertionError("unauthenticated request succeeded")
        except urllib.error.HTTPError as exc:
            assert exc.code == 401
        assert len(request("/api/platforms")) == 16
        settings = request("/api/settings")
        settings["config"]["theme"] = "dark"
        settings["outDir"] = os.path.join(data, "输出 目录")
        assert request("/api/settings", "PUT", settings)["config"]["theme"] == "dark"
        assert request("/api/settings")["outDir"] == settings["outDir"]
        try:
            request("/api/parse", "POST", {"text": "invalid"})
            raise AssertionError("invalid link accepted")
        except urllib.error.HTTPError as exc:
            assert exc.code == 400
        invalid = dict(settings, outDir="")
        try:
            request("/api/settings", "PUT", invalid)
            raise AssertionError("empty output directory accepted")
        except urllib.error.HTTPError as exc:
            assert exc.code == 400
        assert request("/api/settings")["outDir"] == settings["outDir"]
        assert request("/api/detect", "POST", {"text": "https://www.douyin.com/video/123"})["platform"] == "douyin"
        assert request("/api/tasks") == []
        assert request("/api/history") == []
        assert "时间" in request("/api/history/export")["csv"]
        assert request("/api/history", "DELETE")["ok"] is True
        assert request("/api/history") == []
        print("Sidecar auth, settings, platform and input smoke passed")
    finally:
        proc.stdin.close()
        try:
            code = proc.wait(timeout=20)
            assert code == 0, code
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            raise AssertionError("sidecar did not exit on stdin EOF")
