# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Initialize only this deployment's owned private device-files volume."""
import os
from pathlib import Path
import stat

if __name__=='__main__':
    root=Path('/device-files')
    for path in (root,root/'inbox',root/'processed'):
        if path.is_symlink():
            raise ValueError('Device files directory links are unsupported')
        path.mkdir(mode=0o700,exist_ok=True)
        info=path.stat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.getuid():
            raise ValueError('Device files volume must be owned by its service identity')
        path.chmod(0o700)
