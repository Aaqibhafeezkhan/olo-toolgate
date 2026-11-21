# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Example signed-notification receiver; loopback only, behind the operator's public HTTPS ingress."""
import argparse
import base64
from contextlib import closing
import hashlib
import hmac
import http.server
import json
import os
from pathlib import Path
import re
import sqlite3
import time

IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}')


def initialize_state(path):
    if path.is_symlink(): raise ValueError('Linked receiver state is unsupported')
    exists=path.exists()
    with closing(sqlite3.connect(path)) as db, db:
        if not exists:
            db.execute('CREATE TABLE received(tenant_id TEXT NOT NULL,event_id TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(tenant_id,event_id))')
            db.execute('PRAGMA user_version=1')
        version=db.execute('PRAGMA user_version').fetchone()[0]
        objects=db.execute("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall()
        columns=[(row[1],row[2],row[3],row[5]) for row in db.execute('PRAGMA table_info(received)')]
        if version!=1 or objects!=[('table','received')] or columns!=[
                ('tenant_id','TEXT',1,1),('event_id','TEXT',1,2),('digest','TEXT',1,0)]:
            raise ValueError('Unsupported receiver state; preserve it and choose a fresh state path')


def strict(raw):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            if name in result: raise ValueError('Duplicate JSON field')
            result[name] = value
        return result
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite JSON')))


def verify(raw, headers, registrations, now):
    if not isinstance(registrations, dict): raise ValueError()
    document = strict(raw)
    if not isinstance(document, dict) or not isinstance(document.get('subscriptionId'), str): raise ValueError()
    registration = registrations.get(document['subscriptionId'])
    if not registration: raise PermissionError()
    encoded = registration['signingSecret']
    if not isinstance(encoded, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', encoded): raise ValueError()
    secret = base64.urlsafe_b64decode(encoded+'=')
    timestamp = headers.get('Toolgate-Event-Timestamp', '')
    if not re.fullmatch(r'[0-9]{1,12}', timestamp) or abs(now-int(timestamp))>300: raise PermissionError()
    expected = 'sha256='+hmac.new(secret, timestamp.encode()+b'.'+raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, headers.get('Toolgate-Event-Signature', '')): raise PermissionError()
    if document.get('type') == 'VERIFY':
        if set(document) != {'type', 'subscriptionId', 'challenge'} or not isinstance(document['challenge'], str) or not IDENTIFIER.fullmatch(document['challenge']): raise ValueError()
        proof = hmac.new(secret, ('verify.'+document['subscriptionId']+'.'+document['challenge']).encode(), hashlib.sha256).hexdigest()
        return {'proof': proof}, None
    if set(document) != {'subscriptionId', 'event'}: raise ValueError()
    event = document['event']
    if not isinstance(event, dict) or event.get('tenantId') != registration['tenantId']: raise PermissionError()
    if (not isinstance(event.get('eventId'), str) or not IDENTIFIER.fullmatch(event['eventId'])
            or headers.get('Toolgate-Event-ID') != event['eventId']
            or event.get('sourceType') not in ('WORKFLOW', 'TRIGGER')
            or event.get('kind') not in ('PROGRESS', 'RESULT', 'TRIGGER_STATUS')
            or type(event.get('sequence')) is not int or not 1<=event['sequence']<=9007199254740991): raise ValueError()
    return None, event


class Handler(http.server.BaseHTTPRequestHandler):
    def setup(self):
        super().setup(); self.connection.settimeout(5)

    def log_message(self, *_): pass  # Never log authentication headers, keys or raw payloads.

    def reply(self, status, document):
        raw = json.dumps(document, separators=(',', ':')).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw))); self.send_header('Cache-Control', 'no-store')
        self.end_headers(); self.wfile.write(raw)

    def do_POST(self):
        try:
            if self.path != '/events' or self.headers.get('Transfer-Encoding'): raise ValueError()
            for name in ('Content-Length', 'Toolgate-Event-Timestamp', 'Toolgate-Event-Signature', 'Toolgate-Event-ID'):
                if len(self.headers.get_all(name, [])) != 1: raise ValueError()
            length = self.headers['Content-Length']
            if not re.fullmatch(r'[0-9]{1,5}', length) or not 1<=int(length)<=65536: raise ValueError()
            self.connection.settimeout(5)
            raw = self.rfile.read(int(length))
            if len(raw) != int(length): raise ValueError()
            path = self.server.keys_path
            if path.is_symlink() or path.stat().st_size>65536 or os.name!='nt' and path.stat().st_mode&0o077:
                raise ValueError('Signing configuration must be a private local file')
            proof, event = verify(raw, self.headers, strict(path.read_bytes()), int(time.time()))
            if proof is not None: return self.reply(200, proof)
            # Store a semantic digest, not private output; retries across subscriptions/replicas
            # can use different JSON key ordering while retaining the same event identity.
            digest = hashlib.sha256(json.dumps(event, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            with closing(sqlite3.connect(self.server.state_path, timeout=3)) as db, db:
                previous = db.execute('SELECT digest FROM received WHERE tenant_id=? AND event_id=?',
                                      (event['tenantId'], event['eventId'])).fetchone()
                if previous and previous[0] != digest: return self.reply(409, {'error':'EVENT_ID_CONFLICT'})
                db.execute('INSERT INTO received(tenant_id,event_id,digest) VALUES(?,?,?) ON CONFLICT DO NOTHING',
                           (event['tenantId'], event['eventId'], digest))
            # Notification ACK only. Fetch a RESULT through OLO with a current authorized
            # user token before consuming private output or making a business decision.
            self.reply(200, {'accepted':True, 'duplicate':previous is not None})
        except PermissionError: self.reply(401, {'error':'INVALID_SIGNATURE_OR_REGISTRATION'})
        except (ValueError, KeyError, TypeError, OSError): self.reply(400, {'error':'INVALID_NOTIFICATION'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keys', type=Path, required=True, help='Private JSON registration/secret file outside the repository')
    parser.add_argument('--state', type=Path, required=True, help='Private SQLite deduplication file outside the repository')
    parser.add_argument('--port', type=int, default=8787); args = parser.parse_args()
    if not args.keys.is_absolute() or not args.state.is_absolute(): parser.error('Use absolute private file paths')
    os.umask(0o077)
    initialize_state(args.state)
    server = http.server.HTTPServer(('127.0.0.1', args.port), Handler)
    server.keys_path = args.keys; server.state_path = args.state
    try: server.serve_forever()
    finally: server.server_close()


if __name__ == '__main__': main()
