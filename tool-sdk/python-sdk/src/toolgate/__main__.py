# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Python SDK project commands, delegated to by the shared toolgate CLI."""
from __future__ import annotations

import argparse
import contextlib
import os
from pathlib import Path
import subprocess
import sys
import time


def main(argv=None):
    parser = argparse.ArgumentParser(prog="toolgate-python-sdk")
    parser.add_argument("command", choices=("dev", "check", "stage", "pack", "run"))
    parser.add_argument("project", nargs="?", default=".")
    parser.add_argument("--http")
    parser.add_argument("--digests", action="store_true")
    parser.add_argument("-o", "--output")
    args = parser.parse_args(argv)
    try:
        from .runtime import Config, Mode, StandaloneRuntime, StartupRefused
        if args.command == "run" and os.environ.get("TOOLGATE_PROTOCOL") == "2":
            from .runtime.governed import run
            output = sys.stdout.buffer
            with contextlib.redirect_stdout(sys.stderr):
                return run(sys.stdin.buffer, output, os.environ)
        if args.command in {"stage", "pack"}:
            from .build import stage, pack
            print((stage if args.command == "stage" else pack)(args.project, args.output))
            return 0
        build = None
        if args.command in {"check", "dev"}:
            from .descriptor import build_project
            with contextlib.redirect_stdout(sys.stderr):
                project, modules, build = build_project(args.project)
            root = project.root
        else:
            root = Path.cwd()
        if args.command == "check":
            if (root / "toolgate.dev.json").is_file():
                try:
                    tracked = subprocess.run(["git", "-C", str(root), "ls-files", "--error-unmatch", "--", "toolgate.dev.json"],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
                    if tracked.returncode == 0:
                        raise ValueError("toolgate.dev.json must not be tracked by git")
                except (OSError, subprocess.TimeoutExpired):
                    pass  # Same as Java Checks: a source archive may have no git installation.
            env = dict(os.environ, TOOLGATE_MODE="development")
            config = Config.load(env, root)
            from .testing import ToolHarness
            with ToolHarness(build, fixtures=config.dev_json) as harness:
                results = harness.run_examples(root)
            tests = root / "tests"
            if tests.is_dir():
                test_env = dict(os.environ)
                test_env["PYTHONPATH"] = str(project.source) + os.pathsep + str(Path(__file__).resolve().parent.parent) + (
                    os.pathsep + test_env["PYTHONPATH"] if test_env.get("PYTHONPATH") else "")
                subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(tests)],
                               cwd=root, env=test_env, check=True)
            print("check passed (%d examples)" % len(results))
            return 0
        env = dict(os.environ)
        if args.command == "dev":
            env["TOOLGATE_MODE"] = "development"
        output = sys.stdout.buffer
        with contextlib.redirect_stdout(sys.stderr):
            runtime = StandaloneRuntime(build, env=env, working_directory=root)
            with runtime:
                if args.digests:
                    from ._json import dumps
                    output.write((dumps(runtime.discover_meta())+"\n").encode())
                    return 0
                address = args.http or env.get("TOOLGATE_HTTP_ADDRESS")
                if address:
                    from .protocol.http import StreamableHttpServer
                    host, port = address.rsplit(":", 1)
                    with StreamableHttpServer(runtime.core, (host.strip("[]"), int(port)), runtime.verifier,
                                               runtime.config.mode is Mode.DEVELOPMENT):
                        while True:
                            time.sleep(.25)
                else:
                    from .protocol.stdio import StdioServer
                    StdioServer(runtime.core).serve(sys.stdin.buffer, output)
        return 0
    except KeyboardInterrupt:
        return 0
    except StartupRefused as exc:
        print("toolgate: refusing to start: " + str(exc), file=sys.stderr)
        return 78
    except Exception as exc:
        print("toolgate: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
