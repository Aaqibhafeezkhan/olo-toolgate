# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Cloud trace destinations are installation configuration, with isolated credential headers."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('profiled_observability',ROOT/'debug/profiled/observability/manage.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class ObservabilityConfigurationTests(unittest.TestCase):
    def test_three_clouds_share_the_pipeline_but_not_credentials(self):
        settings={'langfuse':{'publicKey':'public-fixture','secretKey':'private-fixture'},
            'langsmith':{'apiKey':'smith-fixture','project':'test'},
            'phoenix':{'apiKey':'phoenix-fixture','baseUrl':'https://app.phoenix.arize.com/s/test'}}
        document,environment,enabled=module.configuration(settings)
        self.assertEqual(['langfuse','langsmith','phoenix'],enabled)
        self.assertEqual(3,len(document['service']['pipelines']['traces']['exporters']))
        for name,row in document['exporters'].items():
            self.assertEqual(1,sum('env:' in value for value in row['headers'].values()))
            self.assertEqual('https',row['endpoint'].split(':')[0])
            self.assertNotIn('private-fixture',str(row));self.assertNotIn('smith-fixture',str(row));self.assertNotIn('phoenix-fixture',str(row))
        self.assertEqual('smith-fixture',environment['LANGSMITH_API_KEY'])
    def test_missing_destinations_are_disabled_and_untrusted_urls_and_newlines_refused(self):
        document,_,enabled=module.configuration({'langfuse':{'publicKey':'public-fixture','secretKey':'private-fixture'}})
        self.assertEqual(['langfuse'],enabled);self.assertEqual(1,len(document['exporters']))
        for settings in ({},{'langsmith':{'apiKey':'bad\nvalue'}},{'langsmith':{'apiKey':'fixture','baseUrl':'http://insecure'}},
                {'phoenix':{'apiKey':'fixture','baseUrl':'https://user:secret@collector'}}):
            with self.assertRaises(ValueError):module.configuration(settings)

    def test_explicit_local_collector_needs_no_cloud_credentials(self):
        document,environment,enabled=module.configuration({},allow_local=True)
        self.assertEqual({},environment);self.assertEqual(['local (no cloud keys configured)'],enabled)
        self.assertEqual({'debug/local':{'verbosity':'basic'}},document['exporters'])
        self.assertEqual(['debug/local'],document['service']['pipelines']['traces']['exporters'])

    def test_first_setup_creates_template_and_restart_preserves_operator_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            here=Path(temporary)/'debug/profiled/observability';here.mkdir(parents=True)
            (here/'credentials.example.json').write_text('{}',encoding='utf-8')
            with patch.object(module,'HERE',here),patch.object(module.sys,'argv',['manage.py','configure']),patch.object(module.sys,'path',list(module.sys.path)):
                module.main();private=here.parent/'credentials'
                self.assertEqual({},json.loads((private/'observability.json').read_text(encoding='utf-8')))
                self.assertIn('debug/local',json.loads((private/'collector.json').read_text(encoding='utf-8'))['exporters'])
                saved=b'{"langfuse":{"publicKey":"public-fixture","secretKey":"private-fixture"}}'
                (private/'observability.json').write_bytes(saved);module.main()
                self.assertEqual(saved,(private/'observability.json').read_bytes())
                self.assertIn('otlp_http/langfuse',json.loads((private/'collector.json').read_text(encoding='utf-8'))['exporters'])


if __name__=='__main__':unittest.main()
