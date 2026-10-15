#!/usr/bin/env python3
"""Regenerate deterministic screenshots and reject drift against the committed actual-renderer captures."""
import tempfile
from pathlib import Path
from snapshot_runner import ROOT, capture

root = ROOT
with tempfile.TemporaryDirectory(prefix="airgradient-preview-") as tmp:
    capture(tmp)
    generated = {p.relative_to(tmp) for p in Path(tmp).rglob("*") if p.is_file()}
    committed = {p.relative_to(root / "docs/assets") for p in (root / "docs/assets").rglob("*") if p.is_file() and p.name != "mark.svg"}
    assert generated == committed, f"Capture set differs: {generated ^ committed}"
    for artifact in generated:
        assert (Path(tmp) / artifact).read_bytes() == (root / "docs/assets" / artifact).read_bytes(), f"Screenshot drift: {artifact}"
print("All screen and theme captures match the actual Glyphora renderer")
