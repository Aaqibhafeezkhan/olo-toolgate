# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Consumer-side signature and tenant rejection using the shipped receiver example."""
import base64
from contextlib import closing
import hashlib
import hmac
import importlib.util
import json
import sqlite3
import tempfile
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('application_receiver', ROOT/'deploy/docker/quickstart/dev/externa-workflow-integration/postman/webhook_receiver.py')
receiver=importlib.util.module_from_spec(spec);spec.loader.exec_module(receiver)


class ApplicationReceiverTests(unittest.TestCase):
    secret=bytes(range(32))
    registrations={'sub-1':{'signingSecret':base64.urlsafe_b64encode(secret).decode().rstrip('='),'tenantId':'tenant-1'}}

    def test_current_state_reopens_and_unsupported_state_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            current=Path(directory)/'current.db';receiver.initialize_state(current);receiver.initialize_state(current)
            unsupported=Path(directory)/'unsupported.db'
            with closing(sqlite3.connect(unsupported)) as db, db:db.execute('CREATE TABLE old_data(value TEXT)')
            before=unsupported.read_bytes()
            with self.assertRaises(ValueError):receiver.initialize_state(unsupported)
            self.assertEqual(before,unsupported.read_bytes())

    def signed(self, document):
        raw=json.dumps(document,separators=(',',':')).encode()
        return raw,{'Toolgate-Event-ID':'event-1','Toolgate-Event-Timestamp':'1800000000',
                    'Toolgate-Event-Signature':'sha256='+hmac.new(self.secret,b'1800000000.'+raw,hashlib.sha256).hexdigest()}

    def event(self):
        return {'subscriptionId':'sub-1','event':{'tenantId':'tenant-1','eventId':'event-1','sequence':1,'sourceType':'WORKFLOW','kind':'RESULT'}}

    def test_signed_event_and_ownership_proof(self):
        raw,headers=self.signed(self.event())
        proof,event=receiver.verify(raw,headers,self.registrations,1800000000)
        self.assertIsNone(proof);self.assertEqual('event-1',event['eventId'])
        raw,headers=self.signed({'type':'VERIFY','subscriptionId':'sub-1','challenge':'nonce'})
        proof,event=receiver.verify(raw,headers,self.registrations,1800000000)
        self.assertIsNone(event)
        self.assertEqual(hmac.new(self.secret,b'verify.sub-1.nonce',hashlib.sha256).hexdigest(),proof['proof'])

    def test_tampering_stale_delivery_and_cross_tenant_are_rejected(self):
        raw,headers=self.signed(self.event())
        with self.assertRaises(PermissionError):receiver.verify(raw+b' ',headers,self.registrations,1800000000)
        with self.assertRaises(PermissionError):receiver.verify(raw,headers,self.registrations,1800000301)
        document=self.event();document['event']['tenantId']='foreign'
        raw,headers=self.signed(document)
        with self.assertRaises(PermissionError):receiver.verify(raw,headers,self.registrations,1800000000)

    def test_header_event_substitution_and_boolean_sequence_are_rejected(self):
        raw,headers=self.signed(self.event());headers['Toolgate-Event-ID']='another-event'
        with self.assertRaises(ValueError):receiver.verify(raw,headers,self.registrations,1800000000)
        document=self.event();document['event']['sequence']=True
        raw,headers=self.signed(document)
        with self.assertRaises(ValueError):receiver.verify(raw,headers,self.registrations,1800000000)


if __name__=='__main__':unittest.main()
