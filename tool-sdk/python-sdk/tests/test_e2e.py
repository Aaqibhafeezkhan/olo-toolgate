# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Fresh project -> check -> deterministic executable -> MCP and governed process -> Rust package."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

from support import REPO, SDK


class EndToEndTest(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("packaging"), "Locked wheel builds need toolgate-sdk[build]")
    def test_hash_locked_dependency_is_embedded_and_wrong_hash_is_refused(self):
        with tempfile.TemporaryDirectory(prefix="toolgate-wheels-e2e-") as temp:
            root = Path(temp)
            wheels = root / "wheels"
            wheels.mkdir()
            wheel = wheels / "extra_demo-1.0.0-py3-none-any.whl"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("extra_demo.py", 'def greeting():\n    return "From wheel"\n')
                archive.writestr("extra_demo-1.0.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: extra-demo\nVersion: 1.0.0\n")
                archive.writestr("extra_demo-1.0.0.dist-info/WHEEL", "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n")
                archive.writestr("extra_demo-1.0.0.dist-info/RECORD", "")
            (root / "requirements.lock").write_text("extra-demo==1.0.0 --hash=sha256:" + hashlib.sha256(wheel.read_bytes()).hexdigest()+"\n")
            (root / "pyproject.toml").write_text('''[project]
name = "wheel-demo"
version = "1.0.0"
dependencies = ["extra-demo==1.0.0"]
[tool.toolgate]
descriptor_generation = 1
modules = ["sample"]
requirements_lock = "requirements.lock"
wheelhouse = "wheels"
''')
            (root / "sample.py").write_text('''from dataclasses import dataclass
from extra_demo import greeting
from toolgate import tool_package, server_tool
tool_package(id="acme.wheels", name="Wheels", publisher="Acme")
@dataclass
class Out:
    value: str
@server_tool(name="hello", title="Hello", description="Hello")
def hello() -> Out:
    return Out(greeting())
''')
            env = dict(os.environ, PYTHONPATH=str(SDK/"src")+os.pathsep+str(wheel))
            staged = subprocess.run([sys.executable, "-m", "toolgate", "stage"], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, staged.returncode, staged.stdout+staged.stderr)
            request = {"jsonrpc":"2.0", "id":1, "method":"tools/call", "params":{"name":"wheels.hello", "arguments":{},
                "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28", "io.modelcontextprotocol/clientCapabilities":{}}}}
            result = subprocess.run([sys.executable, "-I", str(root/"build/toolgate/staging/artifacts/tool.pyz")],
                                    cwd=root, input=json.dumps(request)+"\n", text=True, capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual({"value":"From wheel"}, json.loads(result.stdout)["result"]["structuredContent"])
            (root/"requirements.lock").write_text("extra-demo==1.0.0 --hash=sha256:"+"0"*64+"\n")
            rejected = subprocess.run([sys.executable, "-m", "toolgate", "stage"], cwd=root, env=env,
                                      capture_output=True, text=True, timeout=30)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("hash", rejected.stderr.lower())

    def test_starter_checks_and_packaged_executable_runs_without_sdk_install(self):
        with tempfile.TemporaryDirectory(prefix="toolgate-python-e2e-") as temp:
            root = Path(temp)
            cli = os.environ.get("TOOLGATE_CLI")
            env = dict(os.environ, PYTHONPATH=str(SDK/"src"), TOOLGATE_PYTHON=sys.executable)
            if cli:
                subprocess.run([cli, "init", "orders", "--lang", "python", "--dir", str(root)],
                               check=True, capture_output=True, text=True, env=env)
            else:
                templates = REPO / "tool-sdk/cli/starters/python/common"
                variables = {"name": "orders", "packageId": "example.orders", "title": "Orders", "publisher": "Example",
                             "pythonPackage": "example_orders", "sdkVersion": "0.10.0.dev0"}
                for source in templates.rglob("*.tmpl"):
                    target = root / source.relative_to(templates).as_posix().removesuffix(".tmpl").replace("__PYTHON_PACKAGE__", "example_orders")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    text = source.read_text(encoding="utf-8")
                    for key, value in variables.items():
                        text = text.replace("{{"+key+"}}", value)
                    target.write_text(text, encoding="utf-8")
            def command(*args):
                result = subprocess.run([sys.executable, "-m", "toolgate", *args], cwd=root, env=env,
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                return result
            command("check")
            command("stage")
            artifact = root / "build/toolgate/staging/artifacts/tool.pyz"
            first = hashlib.sha256(artifact.read_bytes()).digest()
            command("stage")
            self.assertEqual(first, hashlib.sha256(artifact.read_bytes()).digest())
            clean_env = {k: v for k, v in os.environ.items() if not k.startswith(("PYTHON", "TOOLGATE_"))}
            meta = {"io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {}}
            request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": "orders.hello", "arguments": {"name": "Ada"}, "_meta": meta}}
            # -I proves the artifact includes the runtime, author code and generated metadata.
            result = subprocess.run([sys.executable, "-I", str(artifact)], input=json.dumps(request)+"\n", text=True,
                                    env=clean_env, cwd=root, capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            response = json.loads(result.stdout)
            self.assertEqual({"message": "Hello Ada"}, response["result"]["structuredContent"])
            governed = {"protocolVersion": 2, "invocationId": "e2e-invocation", "toolId": "example.orders/lookup",
                        "arguments": {"id": "7"}, "deadlineUnixMs": int(time.time()*1000)+10000}
            broker = {"kind": "BROKER_RESPONSE", "requestId": "broker-1", "result": {"body": {"message": "Item 7"}}}
            result = subprocess.run([sys.executable, "-I", str(artifact)], input=json.dumps(governed)+"\n"+json.dumps(broker)+"\n",
                                    text=True, env={**clean_env, "TOOLGATE_PROTOCOL": "2"}, cwd=root,
                                    capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            frames = [json.loads(line) for line in result.stdout.splitlines()]
            self.assertEqual("BROKER_REQUEST", frames[0]["kind"])
            self.assertEqual("ROUTE_CALL", frames[0]["operation"])
            self.assertEqual({"message": "Item 7"}, frames[-1]["structuredContent"])
            self.assertEqual(1, sum(frame["kind"] == "RESULT" for frame in frames))
            if cli:
                command("pack")
                package = next((root/"build/toolgate").glob("*.tgpkg"))
                subprocess.run([cli, "validate", str(package)], check=True, capture_output=True)
                with zipfile.ZipFile(package) as archive:
                    manifest = json.loads(archive.read("toolgate-package.json"))
                    self.assertEqual("PYTHON", manifest["sdk"]["language"])
                    self.assertEqual("python", manifest["tools"][0]["runtimeId"])
                # The shared CLI delegates to Python and reports the same semantic digests.
                result = subprocess.run([cli, "dev", str(root), "--digests"], env=env, capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                self.assertIn(manifest["descriptorSetDigest"], result.stdout)


if __name__ == "__main__":
    unittest.main()
