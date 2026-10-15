"""Run the test-only renderer in a standalone JVM using Mill's evaluated classpath."""
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def capture(directory):
    subprocess.run([str(ROOT / "mill"), "app.test.compile"], cwd=ROOT, check=True)
    result = subprocess.run([str(ROOT / "mill"), "show", "app.test.runClasspath"], cwd=ROOT,
                            check=True, stdout=subprocess.PIPE, text=True)
    paths = [item.split(":", 3)[-1] for item in json.loads(result.stdout)]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    subprocess.run([java, "--enable-native-access=ALL-UNNAMED", "-cp", os.pathsep.join(paths),
                    "com.worxbend.airgradient.SnapshotMain", str(directory)], cwd=ROOT, check=True)
