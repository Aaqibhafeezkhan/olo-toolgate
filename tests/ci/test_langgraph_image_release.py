# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The standalone worker must reach the same gated registries as other images."""
import json
import os
from pathlib import Path
import shlex
import unittest
from unittest.mock import patch

import yaml
from tools.release import development, manual_development, pipeline, prepare

ROOT = Path(__file__).resolve().parents[2]


class LangGraphImageReleaseTests(unittest.TestCase):
    def test_image_suite_preserves_repository_layout_and_native_graph_fixtures(self):
        jobs = yaml.safe_load((ROOT/'.github/workflows/images.yml').read_text())['jobs']
        script = '\n'.join(step.get('run', '') for step in jobs['langgraph-runtime']['steps'])
        command, = [shlex.split(line) for line in script.splitlines()
                    if '-m unittest discover' in line]
        options = command[:command.index('--entrypoint')]
        mounts = [options[index + 1] for index, arg in enumerate(options) if arg == '-v']
        self.assertCountEqual(mounts, [
            '$PWD/apps/langgraph-runtime/tests:/workspace/apps/langgraph-runtime/tests:ro',
            '$PWD/tools/workflows:/workspace/tools/workflows:ro',
            '$PWD/examples/native-graphs:/workspace/examples/native-graphs:ro',
            '$PWD/bot/configurations/text-summary:/workspace/bot/configurations/text-summary:ro',
        ])
        self.assertEqual('/workspace/apps/langgraph-runtime/tests', command[command.index('-s') + 1])
        for mount in mounts:
            source = mount.split(':')[0].replace('$PWD', str(ROOT))
            self.assertTrue(Path(source).is_dir(), source)
        self.assertIn('--read-only', command)
        self.assertEqual('none', command[command.index('--network') + 1])
        self.assertIn('--target verification', script)
        self.assertIn('olo-toolgate-langgraph-runtime:verification', command)

    def test_image_evidence_is_required_and_manual_build_saves_the_image(self):
        jobs = yaml.safe_load((ROOT/'.github/workflows/images.yml').read_text())['jobs']
        self.assertIn('langgraph-runtime', jobs['verify']['needs'])
        self.assertIn('"langgraph-runtime"', jobs['verify']['steps'][0]['run'])
        steps = jobs['langgraph-runtime']['steps']
        script = '\n'.join(step.get('run', '') for step in steps)
        for required in ('--build-arg VERSION=', '--build-arg REVISION=', '--read-only',
                         'toolgate_runtime.trigger_listener --help', '-m pip check',
                         '--severity HIGH,CRITICAL', 'docker save', 'sha256sum'):
            self.assertIn(required, script)
        self.assertTrue(any(step.get('with', {}).get('name') == 'langgraph-runtime-evidence' for step in steps))
        manual = '\n'.join(step.get('run', '') for step in jobs['build']['steps'])
        self.assertIn('olo-toolgate-langgraph-runtime:manual-development', manual)
        self.assertEqual(development.IMAGES['langgraph_runtime'],
                         ('olo-toolgate-langgraph-runtime:tested', 'olo-toolgate-langgraph-runtime'))
        self.assertEqual(manual_development.IMAGES['langgraph_runtime'], 'olo-toolgate-langgraph-runtime')

    def test_both_registries_check_and_publish_the_runtime_identity(self):
        info = {'version': '1.0.0-beta.42', 'commit': 'a' * 40}
        doc = {'Id': 'sha256:checked', 'Config': {'Labels': {
            'org.opencontainers.image.version': info['version'],
            'org.opencontainers.image.revision': info['commit']}}}
        remote = {'Descriptor': {'digest': 'sha256:remote'},
                  'SchemaV2Manifest': {'config': {'digest': doc['Id']}}}
        images = {'langgraph_runtime': development.IMAGES['langgraph_runtime']}
        with patch.dict(os.environ, GITHUB_REPOSITORY='olo-labs/olo-toolgate', GITHUB_ACTOR='publisher',
                        GH_TOKEN='private', DOCKERHUB_NAMESPACE='ololab', DOCKERHUB_USERNAME='publisher',
                        DOCKERHUB_TOKEN='private', GITHUB_OUTPUT=''), \
                patch.object(pipeline, 'IMAGES', images), patch.object(pipeline, 'run') as run, \
                patch.object(pipeline, 'remote_manifest') as manifest, patch.object(prepare, 'create_tag'):
            run.return_value.stdout = json.dumps([doc])
            for publisher, registry in ((pipeline.publish_images, 'ghcr.io/olo-labs'),
                                        (pipeline.publish_dockerhub, 'docker.io/ololab')):
                with self.subTest(registry=registry):
                    manifest.side_effect = [None, remote]
                    result = publisher(info)
                    self.assertEqual(result['langgraph_runtime'], registry + '/olo-toolgate-langgraph-runtime@sha256:remote')
                    run.assert_any_call('docker', 'push', registry + '/olo-toolgate-langgraph-runtime:' + info['version'])
                    doc['Config']['Labels']['org.opencontainers.image.revision'] = 'b' * 40
                    run.return_value.stdout = json.dumps([doc])
                    with self.assertRaisesRegex(ValueError, 'revision|identity'):
                        publisher(info)
                    doc['Config']['Labels']['org.opencontainers.image.revision'] = info['commit']
                    run.return_value.stdout = json.dumps([doc])
