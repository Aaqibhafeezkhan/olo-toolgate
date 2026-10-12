# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Rust container builds must include every workspace member before invoking Cargo."""
from pathlib import Path, PurePosixPath
import posixpath
import re
import shlex
import tomllib
import unittest


ROOT = Path(__file__).resolve().parents[2]


class RustDockerWorkspaceTests(unittest.TestCase):
    def test_sdk_embedded_resources_are_copied_with_the_cli_source(self):
        dockerfiles = [*ROOT.glob('apps/*/Dockerfile'), *ROOT.glob('debug/*.Dockerfile')]
        for dockerfile in dockerfiles:
            copies = []
            instructions = re.sub(r'\\\n\s*', ' ', dockerfile.read_text(encoding='utf-8')).splitlines()
            for instruction in instructions:
                if instruction.startswith('RUN ') and re.search(r'\bcargo\s+(build|check|fetch|metadata|test)\b', instruction):
                    break
                fields = shlex.split(instruction, comments=True)
                if fields and fields[0].upper() == 'COPY' and '--from=' not in instruction:
                    copies.extend((ROOT/source, fields[-1])
                                  for source in fields[1:-1] if not source.startswith('--'))
            else:
                continue

            def destinations(path):
                result = set()
                for source, destination_value in copies:
                    destination = PurePosixPath(destination_value)
                    if source.is_dir() and path.is_relative_to(source):
                        result.add(destination/path.relative_to(source).as_posix())
                    elif source == path:
                        result.add(destination/path.name if destination_value in ('.', './') or destination_value.endswith('/') else destination)
                return {PurePosixPath('/src')/path if not path.is_absolute() else path for path in result}

            for source in (ROOT/'tool-sdk/cli/src').rglob('*.rs'):
                for target in destinations(source):
                    for include in re.findall(r'include_(?:str|bytes)!\(\s*"([^"]+)"', source.read_text(encoding='utf-8')):
                        resource = (source.parent/include).resolve()
                        expected = PurePosixPath(posixpath.normpath(str(target.parent/include)))
                        with self.subTest(dockerfile=str(dockerfile.relative_to(ROOT)), resource=str(resource.relative_to(ROOT))):
                            self.assertIn(expected, destinations(resource), 'Embedded SDK resources must be available inside the Rust build stage')

    def test_workspace_manifests_are_copied_before_cargo(self):
        workspace = tomllib.loads((ROOT/'Cargo.toml').read_text(encoding='utf-8'))
        dockerfiles = [*ROOT.glob('apps/*/Dockerfile'), *ROOT.glob('debug/*.Dockerfile')]
        for dockerfile in dockerfiles:
            instructions = re.sub(r'\\\n\s*', ' ', dockerfile.read_text(encoding='utf-8')).splitlines()
            copies = []
            for instruction in instructions:
                if instruction.startswith('RUN ') and re.search(r'\bcargo\s+(build|check|fetch|metadata|test)\b', instruction):
                    break
                fields = shlex.split(instruction, comments=True)
                if not fields or fields[0].upper() != 'COPY' or '--from=' in instruction:
                    continue
                sources = [field for field in fields[1:-1] if not field.startswith('--')]
                copies.extend((source, fields[-1]) for source in sources)
            else:
                continue  # Images that receive source at runtime do not build a workspace.

            copied_manifests = set()
            for source, destination in copies:
                source_path = ROOT/source
                destination_path = PurePosixPath(destination)
                if destination_path.is_absolute():
                    destination_path = destination_path.relative_to('/src')
                for member in workspace['workspace']['members']:
                    manifest = ROOT/member/'Cargo.toml'
                    if source_path.is_dir() and manifest.is_relative_to(source_path):
                        copied_manifests.add(destination_path/manifest.relative_to(source_path).as_posix())
                    elif source_path == manifest:
                        copied_manifests.add(destination_path/source_path.name
                                             if destination.endswith('/') else destination_path)
            for member in workspace['workspace']['members']:
                with self.subTest(dockerfile=str(dockerfile.relative_to(ROOT)), member=member):
                    self.assertIn(PurePosixPath(member)/'Cargo.toml', copied_manifests,
                                  'Cargo loads every workspace member, including unselected packages')


if __name__ == '__main__':
    unittest.main()
