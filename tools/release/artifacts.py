# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Package already checked libraries, without rebuilding them or changing versions."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def package(root=ROOT):
    output = root/'build/release'
    output.mkdir(parents=True, exist_ok=True)
    version = (root/'packages/contracts/VERSION').read_text().strip()
    subprocess.run(['cargo', 'package', '-p', 'olo-toolgate-contracts', '--no-verify', '--allow-dirty', '--locked'], cwd=root, check=True)
    shutil.copyfile(root/f'target/package/olo-toolgate-contracts-{version}.crate', output/f'olo-toolgate-contracts-{version}.crate')
    npm = 'npm.cmd' if os.name == 'nt' else 'npm'
    subprocess.run([npm, 'pack', './packages/contracts/typescript', '--ignore-scripts', '--pack-destination', str(output)], cwd=root, check=True)
    with zipfile.ZipFile(output/f'olo-toolgate-contracts-php-{version}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((root/'packages/contracts/php').rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(root/'packages/contracts/php').as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, path.read_bytes())
    for path in sorted((root/'tool-sdk/java-sdk').glob('*/build/libs/*.jar')):
        shutil.copyfile(path, output/path.name)
    # Build the Python distribution from these same checked sources and stamped versions.
    subprocess.run([sys.executable, '-m', 'pip', 'wheel', '--no-deps', '--no-build-isolation',
                    '--wheel-dir', str(output), str(root/'tool-sdk/python-sdk')], cwd=root, check=True)
    if (root/'release.json').is_file():
        release = json.loads((root/'release.json').read_text())
        if release['version'] != version or version != (root/'VERSION').read_text().strip():
            raise ValueError('Release package/product versions differ')
        shutil.copyfile(root/'release.json', output/'release.json')


if __name__ == '__main__':
    package()
