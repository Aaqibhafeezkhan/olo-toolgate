# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Expose this agent's live Gateway tools to MCP stdio AI clients; never configure access."""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('agent_mimic', ROOT/'debug/agent-mimic.py')
mimic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mimic)
PROTOCOLS = ('2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05')
MAX_MESSAGE = 131072


class Bridge:
    def __init__(self, agent):
        self.agent = agent
        self.initializing = False
        self.ready = False

    def handle(self, request):
        rpc_id = request.get('id') if isinstance(request, dict) else None
        def error(code, message):
            return {'jsonrpc':'2.0', 'id':rpc_id, 'error':{'code':code, 'message':message}}
        def result(value):
            return {'jsonrpc':'2.0', 'id':rpc_id, 'result':value}
        if not isinstance(request, dict) or request.get('jsonrpc') != '2.0' or not isinstance(request.get('method'), str):
            return error(-32600, 'Invalid request')
        params = request.get('params', {})
        if not isinstance(params, dict): return error(-32602, 'Parameters must be an object')
        method = request['method']
        if 'id' not in request:
            if method == 'notifications/initialized' and self.initializing: self.ready = True
            return None
        if isinstance(rpc_id, bool) or not isinstance(rpc_id, (str, int)):
            rpc_id = None
            return error(-32600, 'Invalid request ID')
        if method == 'ping': return result({})
        if method == 'initialize':
            if self.initializing: return error(-32600, 'Already initialized')
            if not isinstance(params.get('protocolVersion'), str) or not isinstance(params.get('capabilities'), dict) or not isinstance(params.get('clientInfo'), dict):
                return error(-32602, 'Initialization requires protocolVersion, capabilities and clientInfo')
            self.initializing = True
            version = params['protocolVersion'] if params['protocolVersion'] in PROTOCOLS else PROTOCOLS[0]
            return result({'protocolVersion':version, 'capabilities':{'tools':{}},
                'serverInfo':{'name':'olo-toolgate-debug-agent', 'version':'1.0.0'},
                'instructions':'Tools run on the device bound to the configured agent credential. Discover tools with tools/list. The diagnostic setup uses the standard ReadAndWrite groups for bounded hotfolder writes through the installed HotFolder package. Gateway group permissions apply to every call.'})
        if not self.ready: return error(-32002, 'Initialize the MCP connection first')
        if method not in ('tools/list', 'tools/call'): return error(-32601, 'Method not found')
        if method == 'tools/list':
            if set(params)-{'_meta'}: return error(-32602, 'This bridge does not use pagination')
            outgoing = {}
        else:
            if set(params)-{'name','arguments','_meta'} or not isinstance(params.get('name'), str) or not params['name'] or not isinstance(params.get('arguments', {}), dict):
                return error(-32602, 'Tool call requires a name and an arguments object')
            outgoing = {'name':params['name'], 'arguments':params.get('arguments', {})}
        try:
            # The shared HTTP client logs request IDs to stderr; stdout is MCP JSON only.
            with contextlib.redirect_stdout(sys.stderr): payload = self.agent.rpc(method, outgoing)
            if method == 'tools/list': return result({'tools':payload['tools']})
            return result({k:payload[k] for k in ('content','structuredContent','isError') if k in payload})
        except (OSError, ValueError, KeyError, TypeError):
            # Error details may contain private paths or upstream values. Do not reflect them.
            return error(-32000, 'Gateway rejected or could not complete this request; check credential, device connection and group grants')


def serve(agent, source, destination):
    bridge = Bridge(agent)
    while True:
        line = source.readline(MAX_MESSAGE+1)
        if not line: break
        if len(line.encode('utf-8')) > MAX_MESSAGE:
            while line and not line.endswith('\n'): line = source.readline(MAX_MESSAGE+1)
            reply = {'jsonrpc':'2.0','id':None,'error':{'code':-32600,'message':'Message exceeds the limit'}}
        else:
            try: reply = bridge.handle(json.loads(line))
            except (ValueError, RecursionError):
                reply = {'jsonrpc':'2.0','id':None,'error':{'code':-32700,'message':'Parse error'}}
        if reply is not None:
            destination.write(json.dumps(reply, ensure_ascii=True, separators=(',',':'))+'\n')
            destination.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default='https://localhost:18450')
    parser.add_argument('--token-file', type=Path, default=Path(os.environ.get('TOOLGATE_AGENT_TOKEN_PATH', ROOT/'.dev/debug/client-agent-token')))
    parser.add_argument('--ca-file', type=Path)
    args = parser.parse_args()
    if hasattr(sys.stdin, 'reconfigure'): sys.stdin.reconfigure(encoding='utf-8')
    if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
    try:
        agent = mimic.Agent(args.gateway, args.token_file, mimic.resolve_ca(args.gateway, args.ca_file))
        serve(agent, sys.stdin, sys.stdout)
    except (OSError, ValueError, TypeError):
        print('Cannot start MCP bridge; check the configured credential and trusted Gateway CA.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
