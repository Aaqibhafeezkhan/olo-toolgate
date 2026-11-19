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


def commands(configuration):
    if set(configuration)!={'graphConfiguration','controlOrigin','gatewayOrigin','tokenFile','caFile'}:
        raise ValueError('Unsupported runtime installation configuration')
    python=sys.executable
    return [[python,'-m','toolgate_runtime.graph_server','--configuration',configuration['graphConfiguration']],
        [python,'-m','toolgate_runtime.worker','--control-origin',configuration['controlOrigin'],
         '--gateway-origin',configuration['gatewayOrigin'],'--token-file',configuration['tokenFile'],
         '--control-ca-file',configuration['caFile'],'--gateway-ca-file',configuration['caFile']]]


def supervise(children_commands, stop, ready, *, probe=lambda:True, grace=5):
    children=[]
    try:
        for command in children_commands:
            children.append(subprocess.Popen(command,start_new_session=True))
        while not stop.wait(.2):
            if any(child.poll() is not None for child in children):
                raise RuntimeError('Required runtime component stopped')
            if probe():ready.set()
            else:ready.clear()
    finally:
        ready.clear()
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
        self.send_response(200 if self.path=='/health/ready' and self.server.ready.is_set() else 503)
        self.send_header('Content-Length','0');self.end_headers()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',type=Path,required=True)
    args=parser.parse_args()
    stop=threading.Event();ready=threading.Event()
    signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    # Installation provisioning is explicit and external; never mint or renew credentials here.
    deadline=time.monotonic()+180
    while not args.configuration.is_file():
        if stop.wait(.2):return 0
        if time.monotonic()>deadline:raise ValueError('Runtime provisioning required')
    configuration=json.loads(args.configuration.read_text(encoding='utf-8'))
    graph=json.loads(Path(configuration['graphConfiguration']).read_text(encoding='utf-8'))
    def probe():
        try:
            with socket.create_connection(('127.0.0.1',graph['port']),timeout=.2):return True
        except OSError:return False
    server=ThreadingHTTPServer(('127.0.0.1',19461),Health);server.ready=ready
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:supervise(commands(configuration),stop,ready,probe=probe)
    finally:server.shutdown();server.server_close()
    return 0


if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception:
        print('{"error":"RUNTIME_SERVICE_STOPPED"}',flush=True)
        raise SystemExit(1) from None
