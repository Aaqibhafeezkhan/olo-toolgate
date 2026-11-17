# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Identity of installed compiler/runtime semantics, independent of checkout line endings."""
import hashlib
from pathlib import Path


def _digest():
    root=Path(__file__).parent
    digest=hashlib.sha256()
    for name in ('compiler_identity.py','graph_api.py','graph_packages.py','json_graph.py','runner.py'):
        digest.update(name.encode('ascii')+b'\0')
        digest.update((root/name).read_bytes().replace(b'\r\n',b'\n')+b'\0')
    return 'sha256:'+digest.hexdigest()


COMPILER_DIGEST=_digest()
