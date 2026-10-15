#!/usr/bin/env python3
"""Exercise the shipped application against a synthetic LAN device, outside the source checkout."""
import argparse
import http.server
import json
import subprocess
import tempfile
import threading
from pathlib import Path

parser = argparse.ArgumentParser()
target = parser.add_mutually_exclusive_group(required=True)
target.add_argument("--jar", type=Path)
target.add_argument("--binary", type=Path)
args = parser.parse_args()
command = (["java", "--enable-native-access=ALL-UNNAMED", "-jar", str(args.jar.resolve())] if args.jar
           else [str(args.binary.resolve())])
state = {"status": 200, "body": b'{"rco2":612,"pm02":7.4,"pm02Compensated":5.1,"atmpCompensated":22.6}'}
requests = []

class Device(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        self.send_response(state["status"])
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(state["body"])))
        self.end_headers()
        try:
            self.wfile.write(state["body"])
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Device)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory(prefix="airgradient-http-") as tmp:
        config = Path(tmp) / "config.json"
        history = Path(tmp) / "history.jsonl"
        invocation = command + ["--json", "--no-history", "--config", str(config), "--history", str(history),
                                "--url", f"http://127.0.0.1:{server.server_port}/ignored?ignored=true"]

        def invoke():
            return subprocess.run(invocation, cwd=tmp, capture_output=True, text=True, timeout=15)

        result = invoke()
        assert result.returncode == 0, result.stderr
        values = json.loads(result.stdout)["measurements"]
        assert values["co2"] == 612 and values["pm25"] == 5.1 and values["temperature_c"] == 22.6, values
        assert values["humidity"] is None, values
        state.update(status=503)
        result = invoke()
        assert result.returncode == 1 and "HTTP 503" in result.stderr and not result.stdout, result
        state.update(status=200, body=b"malformed JSON")
        result = invoke()
        assert result.returncode == 1 and "malformed JSON" in result.stderr and not result.stdout, result
        state.update(body=b"x" * (1024 * 1024 + 1))
        result = invoke()
        assert result.returncode == 1 and "exceeds" in result.stderr and not result.stdout, result
        assert requests == ["/measures/current"] * 4, requests
        assert not config.exists() and not history.exists()
    print("Packaged HTTP: live decoding, compensated values, missing sensors, HTTP errors, malformed JSON, size limit passed")
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
