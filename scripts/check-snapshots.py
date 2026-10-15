#!/usr/bin/env python3
"""Regenerate deterministic screenshots and reject drift against the committed actual-renderer captures."""
import tempfile
from pathlib import Path
from snapshot_runner import ROOT, capture

root = ROOT
with tempfile.TemporaryDirectory(prefix="airgradient-preview-") as tmp:
    capture(tmp)
    for name in ("dashboard", "history", "settings"):
        for suffix in ("svg", "txt"):
            artifact = f"{name}.{suffix}"
            assert (Path(tmp) / artifact).read_bytes() == (root / "docs/assets" / artifact).read_bytes(), f"Screenshot drift: {artifact}"
print("All three real Glyphora render captures match")
