# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Cloud trace destinations are installation configuration, with isolated credential headers."""
import importlib.util
from pathlib import Path
import unittest

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


if __name__=='__main__':unittest.main()
