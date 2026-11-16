# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Verify the standalone graph in an isolated environment with its pinned dependencies."""
from pathlib import Path
import os
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "apps/langgraph-runtime"


def main():
    environment = ROOT / ".dev/langgraph-runtime" / sys.implementation.cache_tag
    python = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not python.is_file():
        venv.EnvBuilder(with_pip=True).create(environment)
    subprocess.run([str(python), "-m", "pip", "install", "--disable-pip-version-check",
                    "-r", str(RUNTIME / "requirements.lock")], cwd=ROOT, check=True)
    subprocess.run([str(python), "-m", "pip", "check"], cwd=ROOT, check=True)
    env = dict(os.environ, PYTHONPATH=str(RUNTIME / "src"))
    subprocess.run([str(python), "-m", "unittest", "discover", "-s", str(RUNTIME / "tests"), "-v"],
                   cwd=ROOT, env=env, check=True)
    subprocess.run([str(python), "-m", "unittest", "discover", "-s", "tools/workflows",
                    "-p", "test_*.py", "-v"], cwd=ROOT, check=True)
    subprocess.run([str(python), "-m", "unittest", "discover", "-s", "examples/langchain",
                    "-p", "test_workflow_e2e.py", "-v"], cwd=ROOT, env=env, check=True)


if __name__ == "__main__":
    main()
