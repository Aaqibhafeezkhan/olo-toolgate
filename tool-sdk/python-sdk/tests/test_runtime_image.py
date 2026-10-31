# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The OCI image is bound in the manifest without changing the tool executable."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from support import SDK


class RuntimeImageTest(unittest.TestCase):
    def test_image_pin_is_validated_and_preserved_in_the_runtime_declaration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = '[project]\nname="image-test"\nversion="1.0.0"\n[tool.toolgate]\ndescriptor_generation=1\nmodules=["sample"]\n'
            (root/'sample.py').write_text('''from dataclasses import dataclass
from toolgate import tool_package, server_tool
tool_package(id="acme.image-test", name="Image", publisher="Acme")
@dataclass
class Out:
    value: str
@server_tool(name="hello", title="Hello", description="Hello")
def hello() -> Out:
    return Out("Hello")
''')
            environment = dict(os.environ, PYTHONPATH=str(SDK/'src'))
            artifact = None
            for digest in ['sha256:'+'a'*64, 'sha256:'+'b'*64, None]:
                (root/'pyproject.toml').write_text(project + (f'runtime_image_digest="{digest}"\n' if digest else ''))
                result = subprocess.run([sys.executable, '-m', 'toolgate', 'stage'], cwd=root, env=environment, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                stage = root/'build/toolgate/staging'
                manifest = json.loads((stage/'toolgate-package.json').read_text())
                self.assertEqual(digest, manifest['runtimes'][0].get('baseImageDigest'))
                executable = (stage/'artifacts/tool.pyz').read_bytes()
                if artifact is not None:
                    self.assertEqual(artifact, executable)
                artifact = executable
            for invalid in ['python:latest', 'sha256:short', 'sha256:'+'A'*64]:
                (root/'pyproject.toml').write_text(project + f'runtime_image_digest="{invalid}"\n')
                result = subprocess.run([sys.executable, '-m', 'toolgate', 'stage'], cwd=root, env=environment, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('runtime_image_digest', result.stderr)
