#!/usr/bin/env python3
"""Refresh the committed SVG/text captures after an intentional UI change."""
from snapshot_runner import ROOT, capture
capture(ROOT / "docs/assets")
