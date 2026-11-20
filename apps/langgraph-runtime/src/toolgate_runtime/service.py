# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Stateless graph API and execution worker composition; Gateway owns dispatch and durable state."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import tempfile
from .availability import Availability, Backoff, read_status


def commands(configuration,status_file=None):
    if set(configuration)!={'graphConfiguration','controlOrigin','gatewayOrigin','tokenFile','caFile'}:
        raise ValueError('Unsupported runtime installation configuration')
    python=sys.executable
    result=[[python,'-m','toolgate_runtime.graph_server','--configuration',configuration['graphConfiguration']],
        [python,'-m','toolgate_runtime.worker','--control-origin',configuration['controlOrigin'],
         '--gateway-origin',configuration['gatewayOrigin'],'--token-file',configuration['tokenFile'],
         '--control-ca-file',configuration['caFile'],'--gateway-ca-file',configuration['caFile']]]
    if status_file is not None:result[1].extend(['--status-file',str(status_file)])
    return result


def supervise(children_commands, stop, ready, *, probe=lambda:True, grace=5,on_restart=lambda _:None):
    slots=[dict(command=command,child=None,next=0,started=0,backoff=Backoff()) for command in children_commands]
    try:
        while not stop.wait(.2):
            now=time.monotonic()
            for index,slot in enumerate(slots):
                child=slot['child']
                if child is not None and child.poll() is not None:
                    # A failed worker must not leave descendants active beside its replacement.
                    if os.name=='posix':
                        try:os.killpg(child.pid,signal.SIGKILL)
                        except ProcessLookupError:pass
                    if now-slot['started']>=30:slot['backoff'].reset()
                    slot['child']=None;slot['next']=now+slot['backoff'].next()
                    ready.clear()
                    print('{"error":"RUNTIME_COMPONENT_RECONNECTING"}',flush=True)
                if slot['child'] is None and now>=slot['next']:
                    try:
                        on_restart(index)
                        slot['child']=subprocess.Popen(slot['command'],start_new_session=True)
                        slot['started']=now
                    except OSError:
                        slot['next']=now+slot['backoff'].next()
            if all(slot['child'] is not None and slot['child'].poll() is None for slot in slots) and probe():ready.set()
            else:ready.clear()
    finally:
        ready.clear()
        children=[slot['child'] for slot in slots if slot['child'] is not None]
        for child in children:
            if child.poll() is None:
                if os.name=='posix':os.killpg(child.pid,signal.SIGTERM)
                else:child.terminate()
        deadline=time.monotonic()+grace
        for child in children:
            try:child.wait(timeout=max(0,deadline-time.monotonic()))
            except subprocess.TimeoutExpired:
                if os.name=='posix':os.killpg(child.pid,signal.SIGKILL)
                else:child.kill()
                child.wait()


class Health(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        state=read_status(self.server.status_file)
        payload=json.dumps(dict(workerState=state)).encode()
        status=200 if self.path=='/health/live' or self.path=='/health/ready' and self.server.ready.is_set() and state=='READY' else 503
        if self.path not in ('/health/live','/health/ready'):status=404
        self.send_response(status)
        self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',type=Path,required=True)
    args=parser.parse_args()
    stop=threading.Event();ready=threading.Event()
    signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    status_file=Path(tempfile.gettempdir())/'toolgate-runtime-worker-status.json'
    Availability(status_file).set('CONNECTING')
    server=ThreadingHTTPServer(('127.0.0.1',19461),Health);server.ready=ready
    server.status_file=status_file
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:
        # Remain live during initial external provisioning; no credentials are minted here.
        while not args.configuration.is_file():
            if stop.wait(.2):return 0
        configuration=json.loads(args.configuration.read_text(encoding='utf-8'))
        graph=json.loads(Path(configuration['graphConfiguration']).read_text(encoding='utf-8'))
        def probe():
            if read_status(status_file)!='READY':return False
            try:
                with socket.create_connection(('127.0.0.1',graph['port']),timeout=.2):return True
            except OSError:return False
        supervise(commands(configuration,status_file),stop,ready,probe=probe,
            on_restart=lambda index:Availability(status_file).set('CONNECTING') if index==1 else None)
    finally:server.shutdown();server.server_close()
    return 0


if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception:
        print('{"error":"RUNTIME_SERVICE_STOPPED"}',flush=True)
        raise SystemExit(1) from None
