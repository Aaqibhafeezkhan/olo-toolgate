# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Private mTLS graph compilation API. No unauthenticated serving mode."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import threading

from .graph_packages import GraphRefused, _object
from .graph_runtime import ControlCandidates, GraphRuntime
from .json_graph import cache_status, clear_cache


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self,*args):
        pass  # Request paths/bodies and exception text may contain private configuration.

    def reply(self,status,value):
        data=json.dumps(value,separators=(',',':')).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length',str(len(data)))
        self.send_header('Connection','close')
        self.end_headers()
        self.wfile.write(data)
        self.close_connection=True

    def authorized(self):
        cert=self.connection.getpeercert()
        names=[value for group in cert.get('subject',()) for key,value in group if key=='commonName']
        return len(names)==1 and names[0] in self.server.client_names

    def handle_operation(self,method):
        if not self.authorized():
            return self.reply(403,{'error':'GRAPH_SERVICE_REFUSED'})
        if method=='GET' and self.path=='/internal/runtime/v1/capabilities':
            return self.reply(200,cache_status())
        if method=='DELETE' and self.path=='/internal/runtime/v1/graphs/cache':
            clear_cache()
            return self.reply(200,cache_status())
        if method!='POST' or self.path!='/internal/runtime/v1/graphs:rebuild':
            return self.reply(404,{'error':'NOT_FOUND'})
        length=self.headers.get('Content-Length','')
        if self.headers.get('Transfer-Encoding') or not length.isascii() or not length.isdecimal() or not 0<int(length)<=8192:
            return self.reply(413,{'error':'GRAPH_BUILD_LIMIT'})
        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':
            return self.reply(415,{'error':'GRAPH_BUILD_CONTENT_TYPE'})
        try:
            request=json.loads(self.rfile.read(int(length)),object_pairs_hook=_object)
            if not self.server.build_slots.acquire(blocking=False):
                return self.reply(503,{'error':'GRAPH_BUILD_BUSY'})
            try:
                result=self.server.runtime.rebuild(request)
            finally:
                self.server.build_slots.release()
            return self.reply(200,result)
        except GraphRefused as failure:
            return self.reply(400,{'error':str(failure)})
        except Exception:
            return self.reply(400,{'error':'GRAPH_BUILD_REFUSED'})

    def do_POST(self):self.handle_operation('POST')
    def do_GET(self):self.handle_operation('GET')
    def do_DELETE(self):self.handle_operation('DELETE')


class Server(ThreadingHTTPServer):
    daemon_threads=True
    block_on_close=False

    def get_request(self):
        connection,address=super().get_request()
        connection.settimeout(5)
        try:
            connection=self.tls_context.wrap_socket(connection,server_side=True)
        except Exception:
            connection.close()
            raise
        connection.settimeout(15)
        return connection,address


def server(address,runtime,*,certificate,key,client_ca,client_names):
    if not client_names or any(not isinstance(name,str) or not name for name in client_names):
        raise ValueError('Explicit Control service client identities are required')
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version=ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certificate,key)
    context.load_verify_locations(client_ca)
    context.verify_mode=ssl.CERT_REQUIRED
    instance=Server(address,Handler)
    instance.runtime,instance.client_names=runtime,frozenset(client_names)
    instance.tls_context=context
    instance.build_slots=threading.BoundedSemaphore(4)
    return instance


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',type=Path,required=True,help='Installation-owned TLS and Control reader paths; never a workflow profile')
    args=parser.parse_args()
    config=json.loads(args.configuration.read_text(encoding='utf-8'),object_pairs_hook=_object)
    runtime=GraphRuntime(ControlCandidates(config['controlOrigin'],config['readers'],ca_file=config['controlCaFile']),
        graph_directory=config.get('graphDirectory'))
    with server((config.get('host','127.0.0.1'),config['port']),runtime,
            certificate=config['certificate'],key=config['key'],client_ca=config['clientCa'],client_names=config['clientNames']) as instance:
        instance.serve_forever()


if __name__=='__main__':main()
