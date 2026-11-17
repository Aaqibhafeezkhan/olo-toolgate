# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Equivalent Windows/Linux sources share identity; changing runner semantics does not."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

from toolgate_runtime import compiler_identity


class CompilerIdentityTests(unittest.TestCase):
    def test_line_endings_are_portable_but_execution_changes_invalidate_identity(self):
        source=Path(compiler_identity.__file__).parent
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for name in ('compiler_identity.py','graph_api.py','graph_packages.py','json_graph.py','runner.py'):
                content=(source/name).read_bytes().replace(b'\r\n',b'\n')
                (root/name).write_bytes(content.replace(b'\n',b'\r\n'))
            spec=importlib.util.spec_from_file_location('_compiler_identity_fixture',root/'compiler_identity.py')
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            self.assertEqual(compiler_identity.COMPILER_DIGEST,module.COMPILER_DIGEST)
            with (root/'runner.py').open('ab') as stream:stream.write(b'\r\n# Different installed runner\r\n')
            self.assertNotEqual(compiler_identity.COMPILER_DIGEST,module._digest())
