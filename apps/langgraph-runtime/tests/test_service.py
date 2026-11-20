# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Dependency/component failures do not terminate the stateless runtime service."""
import json
import subprocess
import sys
import threading
import unittest
from unittest.mock import patch
from toolgate_runtime.service import commands,supervise


class ServiceTests(unittest.TestCase):
    def test_composition_has_no_listener_admin_identity_or_result_storage(self):
        value=dict(graphConfiguration='/installation/graph.json',controlOrigin='https://control',
            gatewayOrigin='https://gateway',tokenFile='/installation/worker.token',caFile='/installation/ca.crt')
        rendered=json.dumps(commands(value))
        self.assertIn('toolgate_runtime.graph_server',rendered)
        self.assertIn('toolgate_runtime.worker',rendered)
        self.assertNotIn('listener',rendered);self.assertNotIn('result-directory',rendered)
        with self.assertRaises(ValueError):commands({**value,'adminToken':'forbidden'})

    def test_component_failure_restarts_only_that_child_and_preserves_real_sibling(self):
        children=[];start=subprocess.Popen;restarted=threading.Event();stop=threading.Event()
        def spawn(*args,**kwargs):
            child=start(*args,**kwargs);children.append(child)
            if len(children)>=3:restarted.set()
            return child
        ready=threading.Event();failures=[]
        def run():
            try:supervise([[sys.executable,'-c','import time;time.sleep(60)'],
                [sys.executable,'-c','raise SystemExit(2)']],stop,ready)
            except BaseException as failure:failures.append(failure)
        with patch('toolgate_runtime.service.subprocess.Popen',side_effect=spawn):
            thread=threading.Thread(target=run);thread.start()
            try:
                self.assertTrue(restarted.wait(8))
                self.assertIsNone(children[0].poll(),'graph API sibling must stay alive')
                self.assertTrue(thread.is_alive())
            finally:stop.set();thread.join(8)
        self.assertEqual([],failures);self.assertTrue(all(child.poll() is not None for child in children))
        self.assertFalse(ready.is_set())

    def test_requested_shutdown_clears_readiness_and_reaps_children(self):
        stop=threading.Event();ready=threading.Event();failures=[]
        def run():
            try:supervise([[sys.executable,'-c','import time;time.sleep(60)']],stop,ready)
            except BaseException as error:failures.append(error)
        thread=threading.Thread(target=run);thread.start()
        try:self.assertTrue(ready.wait(5))
        finally:stop.set();thread.join(8)
        self.assertFalse(thread.is_alive());self.assertFalse(ready.is_set());self.assertEqual([],failures)


if __name__=='__main__':unittest.main()
