#!/usr/bin/env python3
"""Prove this showcase consumes published Glyphora artifacts, without a sibling checkout or local Maven fallback."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = {"tui-core_3", "tui-terminal_3", "tui-widgets_3", "tui-runtime_3", "tui-macros_3", "tui-dsl_3"}
for target, expected in (("app.runClasspath", ARTIFACTS), ("app.test.runClasspath", ARTIFACTS | {"tui-test_3"})):
    result = subprocess.run([str(ROOT / "mill"), "show", target], cwd=ROOT, check=True, stdout=subprocess.PIPE, text=True)
    paths = json.loads(result.stdout)
    glyphora = [entry for entry in paths if "/com/worxbend/tui-" in entry]
    actual = {artifact for artifact in expected if any(f"/{artifact}/0.18.3/" in entry for entry in glyphora)}
    assert actual == expected, (target, actual, expected)
    assert len(glyphora) == len(expected), (target, glyphora)
    assert all("/https/repo.maven.apache.org/maven2/" in entry for entry in glyphora), glyphora
    print(f"{target}: {len(expected)} Glyphora 0.18.3 JARs resolved from public Maven Central")
