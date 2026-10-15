#!/usr/bin/env python3
"""Stage runnable artifacts and checksums from evaluated Mill task outputs."""
import hashlib
import json
import platform
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
version = re.search(r'val Version\s*=\s*"([^"]+)"', (root / "app/src/main/scala/com/worxbend/airgradient/Main.scala").read_text()).group(1)

def artifact(target):
    result = subprocess.run([str(root / "mill"), "show", target], cwd=root, check=True, stdout=subprocess.PIPE, text=True)
    value = json.loads(result.stdout)
    return Path(value["path"] if isinstance(value, dict) else value.split(":", 3)[-1])

jar = dist / "airgradient-tui.jar"
shutil.copy2(artifact("app.assembly"), jar)
outputs = [jar]
if "--native" in sys.argv:
    executable = dist / "airgradient-tui"
    shutil.copy2(artifact("native.nativeImage"), executable)
    executable.chmod(0o755)
    arch = {"x86_64": "amd64", "aarch64": "arm64"}.get(platform.machine(), platform.machine())
    archive = dist / f"airgradient-tui-scala-{version}-{platform.system().lower()}-{arch}.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        for item in (executable, root / "LICENSE", root / "README.md"):
            bundle.add(item, arcname=item.name)
    outputs.append(archive)
(dist / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n" for file in outputs))
print("\n".join(str(file.relative_to(root)) for file in outputs))
