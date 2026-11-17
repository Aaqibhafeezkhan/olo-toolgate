# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Live catalog reconciliation without local event admission/deduplication."""
from collections import deque
from contextlib import contextmanager
import json
import threading
import time
import unittest

from toolgate_runtime.subscriptions import Subscriptions
from toolgate_runtime.trigger_bridge import ListenerFailure, Subscription
from toolgate_runtime.trigger_transport import ControlTriggers, TriggerListener


def resource(name,uid,phase='Active'):
    return dict(apiVersion='toolgate.olo/v1',kind='TriggerSubscription',metadata=dict(name=name,uid=uid),status=dict(phase=phase))


class SubscriptionCatalogTests(unittest.TestCase):
    def control(self,pages):
        client=ControlTriggers('https://control.invalid',lambda:'test-token')
        pages=iter(pages);self.requests=[]
        def request(method,path,**options):
            self.requests.append((method,path));return next(pages)
        client._request=request
        return client

    def test_only_active_authorized_resources_are_selected_after_complete_pagination(self):
        control=self.control([dict(items=[resource('one','ts-1')],nextPageToken='page2'),
            dict(items=[resource('paused','ts-2','Paused')],nextPageToken='page3'),dict(items=[resource('two','ts-3')])])
        self.assertEqual((Subscription('one','ts-1'),Subscription('two','ts-3')),control.active_subscriptions())
        self.assertIn('pageToken=page2',self.requests[1][1])

    def test_partial_repeating_or_oversized_catalog_is_refused(self):
        cases=[([dict(items=[resource('one','ts-1')],nextPageToken='again'),dict(items=[],nextPageToken='again')],{}),
            ([dict(items=[resource('one','ts-1')],nextPageToken='next')],dict(maximum=1,catalog_limit=1)),
            ([dict(items=[resource('one','ts-1')],nextPageToken='next'),dict(items=[resource('two','ts-2')])],dict(maximum=1)),
            ([dict(items=[resource('one','ts-1')],nextPageToken='next'),dict(items=[resource('one','ts-2')])],{}),
            ([dict(items=[resource('one','ts-1')],nextPageToken='../escape')],{})]
        for pages,options in cases:
            with self.subTest(pages=pages),self.assertRaises(ListenerFailure):self.control(pages).active_subscriptions(**options)


class SubscriptionReconciliationTests(unittest.TestCase):
    def test_add_remove_and_same_name_new_uid_without_restarting_the_manager(self):
        observed=[];lock=threading.Lock()
        def listen(subscription,stop):
            with lock:observed.append(subscription)
            stop.wait(5)
        manager=Subscriptions(None,retry_seconds=.01,listen=listen)
        def wait(predicate):
            deadline=time.monotonic()+3
            while not predicate() and time.monotonic()<deadline:time.sleep(.01)
            self.assertTrue(predicate())
        first,second=Subscription('one','ts-1'),Subscription('two','ts-2')
        try:
            manager.reconcile([first,second]);wait(lambda:len(observed)==2)
            manager.reconcile([first,second]);self.assertEqual(2,len(observed))
            replacement=Subscription('one','ts-new')
            manager.reconcile([replacement,second])
            wait(lambda:first not in manager.slots or not manager.slots[first].thread.is_alive())
            manager.reconcile([replacement,second]);wait(lambda:replacement in observed)
            self.assertNotIn(first,manager.slots)
            self.assertEqual({'scheduled':2,'draining':0},manager.status())
            manager.reconcile([])
        finally:self.assertTrue(manager.close(3))

    def test_duplicate_or_excessive_desired_set_is_rejected_without_changing_listeners(self):
        manager=Subscriptions(None,maximum=1,listen=lambda sub,stop:stop.wait(5))
        first=Subscription('one','ts-1')
        try:
            manager.reconcile([first])
            with self.assertRaises(ValueError):manager.reconcile([first,Subscription('two','ts-2')])
            self.assertEqual([first],list(manager.slots))
        finally:self.assertTrue(manager.close(3))

    def test_stopping_a_connection_does_not_invent_an_acknowledgement(self):
        stopped=threading.Event();sent=[]
        class Connection:
            subprotocol='toolgate.triggers.v1'
            def send(self,message):sent.append(json.loads(message))
            def recv(self,timeout):
                stopped.set();raise TimeoutError()
        class Control:
            timeout=1
            def verify_subscription(self,sub):pass
            def ticket(self):return 'one-use-ticket'
            @contextmanager
            def connect(self,ticket):yield Connection()
        self.assertEqual((0,None),TriggerListener(Control(),Subscription('one','ts-1')).listen_once(stop_event=stopped))
        self.assertEqual([{'type':'subscribe','subscription':'one'}],sent)
