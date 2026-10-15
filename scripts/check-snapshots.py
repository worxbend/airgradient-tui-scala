#!/usr/bin/env python3
"""Regenerate deterministic screenshots and reject drift against the committed actual-renderer captures."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="airgradient-preview-") as tmp:
    subprocess.run([str(root / "mill"), "app.test.runMain", "com.worxbend.airgradient.SnapshotMain", tmp], cwd=root, check=True)
    for name in ("dashboard", "history", "settings"):
        for suffix in ("svg", "txt"):
            artifact = f"{name}.{suffix}"
            assert (Path(tmp) / artifact).read_bytes() == (root / "docs/assets" / artifact).read_bytes(), f"Screenshot drift: {artifact}"
print("All three real Glyphora render captures match")
