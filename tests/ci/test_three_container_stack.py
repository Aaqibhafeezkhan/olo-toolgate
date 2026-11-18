# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Quickstart composition has three services and least-privilege runtime custody."""
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('three_stack',ROOT/'debug/profiled/stack.py')
stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack)


class CompositionTests(unittest.TestCase):
    def test_three_services_and_scoped_stateless_runtime(self):
        document=stack.definition(dict(quickstart='quickstart',runtime='runtime',device='device'))
        self.assertEqual({'quickstart','langgraph','device'},set(document['services']))
        runtime=document['services']['langgraph']
        self.assertEqual('service:quickstart',runtime['network_mode'])
        self.assertEqual(1,len(runtime['volumes']))
        mount=runtime['volumes'][0]
        self.assertTrue(mount['read_only']);self.assertEqual('langgraph',mount['volume']['subpath'])
        self.assertNotIn('env_file',runtime)
        self.assertEqual('quickstart',document['services']['device']['environment']['TOOLGATE_DEVICE_UPSTREAM'])
        self.assertEqual('true',document['services']['quickstart']['environment']['TOOLGATE_QUICKSTART_WORKFLOW_STACK'])
        self.assertNotIn('listener',str(runtime));self.assertNotIn('admin',str(runtime))


if __name__=='__main__':unittest.main()
