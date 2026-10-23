# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Call the HotFolder package's write and read tools on a device, and print their responses."""
import argparse
import json
import os
from pathlib import Path
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = '2026-07-28'
TEXT = 'My Name is Rahul Nigam'

def gateway_origin(gateway):
    origin = urllib.parse.urlsplit(gateway)
    if origin.scheme not in ('http', 'https') or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ('', '/'):
        raise ValueError('Gateway must be an HTTPS origin, or HTTP on localhost/127.0.0.1')
    if origin.scheme == 'http' and origin.hostname not in ('localhost', '127.0.0.1'):
        raise ValueError('HTTP is supported only on localhost/127.0.0.1; use HTTPS for other gateways')
    return origin

def installed_client_config():
    if os.name == 'nt': return Path(os.environ.get('ProgramData', 'C:/ProgramData')) / 'OLO/ToolGate/client.json'
    if sys.platform == 'darwin': return Path('/Library/Application Support/OLO/ToolGate/client.json')
    return Path('/etc/olo-toolgate/client.json')

def resolve_ca(gateway, explicit_ca=None):
    if gateway_origin(gateway).scheme == 'http':
        if explicit_ca is not None: raise ValueError('-CaFile applies only to HTTPS gateways')
        return None
    if explicit_ca is not None: return explicit_ca
    config_path = installed_client_config()
    if config_path.is_file():
        try: config = json.loads(config_path.read_text(encoding='utf-8-sig'))
        except (OSError, ValueError): raise ValueError('Cannot read installed client configuration; supply the existing trusted CA with -CaFile') from None
        if isinstance(config, dict) and config.get('serverUrl', '').rstrip('/').casefold() == gateway.rstrip('/').casefold():
            ca = config.get('caCertificatePath')
            if ca:
                if not isinstance(ca, str) or not Path(ca).is_absolute(): raise ValueError('Installed client CA path must be absolute; supply -CaFile')
                return Path(ca)
    return None

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

class Agent:
    def __init__(self, gateway, token_file, ca_file=None):
        origin = gateway_origin(gateway)
        try: token = Path(token_file).read_text(encoding='utf-8').strip()
        except OSError: raise ValueError('Cannot read agent token file; supply -TokenFile for an existing configured agent. Device approval does not create an agent credential') from None
        if not 32 <= len(token) <= 256 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~' for c in token):
            raise ValueError('Invalid agent token file')
        self.token = token
        self.url = gateway.rstrip('/') + '/mcp'
        handlers = [urllib.request.ProxyHandler({}), NoRedirect()]
        if origin.scheme == 'https':
            try: context = ssl.create_default_context(cafile=str(ca_file) if ca_file else None)
            except (OSError, ssl.SSLError): raise ValueError('Cannot load trusted CA; supply the current gateway CA with -CaFile') from None
            # Python 3.13+ adds VERIFY_X509_STRICT, which rejects Quickstart certificates issued
            # without key identifier extensions. Chain, expiry and hostname checks remain enforced.
            context.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)
            handlers.append(urllib.request.HTTPSHandler(context=context))
        self.opener = urllib.request.build_opener(*handlers)
        self.sequence = 0
        self.run_id = secrets.token_hex(12)

    def rpc(self, method, parameters=None):
        self.sequence += 1
        rpc_id = f'mimic-{self.run_id}-{self.sequence}'
        params = dict(parameters or {})
        params['_meta'] = {'io.modelcontextprotocol/protocolVersion': VERSION, 'io.modelcontextprotocol/clientCapabilities': {'tools': {}}}
        body = {'jsonrpc': '2.0', 'id': rpc_id, 'method': method, 'params': params}
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': VERSION, 'MCP-Method': method, 'X-Request-ID': secrets.token_hex(16)}
        if method == 'tools/call': headers['MCP-Name'] = params['name']
        print(f'SEND {method} id={rpc_id}' + (f' tool={params["name"]}' if method == 'tools/call' else ''), flush=True)
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers, method='POST')
        try:
            with self.opener.open(request, timeout=35) as response:
                raw = response.read(131073)
                server_id = response.headers.get('X-Request-ID', '')
        except urllib.error.HTTPError as response:
            # Print only the fixed code, never raw server/error bodies or headers.
            try: code = json.loads(response.read(131073)).get('code', 'MCP_REJECTED')
            except (ValueError, AttributeError): code = 'MCP_REJECTED'
            if response.code == 401:
                raise ValueError('MCP HTTP 401: agent token is invalid, expired or not configured on this gateway; run configure-debug-agent.bat separately or supply its configured credential with -TokenFile. Device approval does not create an agent credential') from None
            if response.code == 403:
                raise ValueError('MCP HTTP 403: access denied; the target device must be online, approved, and enabled, with matching installed tool profiles and agent group grants. The debug script cannot change these requirements') from None
            raise ValueError(f'MCP HTTP {response.code}: {code}; check enrollment, agent binding and permissions') from None
        except urllib.error.URLError as failure:
            if isinstance(failure.reason, ssl.SSLCertVerificationError):
                raise ValueError('TLS certificate verification failed; use the installed client trust for this gateway or supply its current CA with -CaFile. Local HTTP can use -Gateway http://127.0.0.1:18090 when enabled') from None
            raise ValueError('Cannot connect to gateway; check that it is running and that -Gateway uses the correct scheme and port') from None
        if len(raw) > 131072: raise ValueError('MCP response exceeds the limit')
        reply = json.loads(raw)
        print(f'RECEIVE {method} id={rpc_id} serverRequestId={server_id}', flush=True)
        if not isinstance(reply, dict) or reply.get('jsonrpc') != '2.0' or reply.get('id') != rpc_id:
            raise ValueError('MCP response identity mismatch')
        if 'error' in reply:
            data = reply['error'].get('data') or {}
            raise ValueError(f'MCP error {reply["error"].get("code")}: {data.get("code", "REQUEST_REJECTED")}')
        result = reply.get('result')
        if not isinstance(result, dict) or result.get('isError'): raise ValueError('MCP tool failed')
        return result

def mimic(agent, file_path, duration_seconds=0):
    if not isinstance(duration_seconds, int) or isinstance(duration_seconds, bool) or not 0 <= duration_seconds <= 60:
        raise ValueError('Duration seconds must be between 0 and 60')
    if duration_seconds:
        print(f'Open the client tray > Show Status. Repeating examples.hotfolder/write_text for {duration_seconds} seconds; each request uses the existing tool.', flush=True)
    deadline = time.monotonic() + duration_seconds
    while True:
        write = agent.rpc('tools/call', {'name': 'examples.hotfolder/write_text', 'arguments': {'path': file_path, 'text': TEXT}})
        print('Client file-write response:', flush=True)
        print(json.dumps(write, ensure_ascii=True, indent=2), flush=True)
        if write.get('structuredContent', {}).get('written') is not True: raise ValueError('Client did not confirm file creation')
        remaining = deadline - time.monotonic()
        if remaining <= 0: break
        time.sleep(min(0.25, remaining))
        if time.monotonic() >= deadline: break
    read = agent.rpc('tools/call', {'name': 'examples.hotfolder/read_text', 'arguments': {'path': file_path}})
    print('Client file-read response:', flush=True)
    print(json.dumps(read, ensure_ascii=True, indent=2), flush=True)
    content = read.get('structuredContent', {})
    if content.get('exists') is not True or content.get('text') != TEXT: raise ValueError('Client did not read back the written file')
    return content

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default='https://localhost:18450')
    parser.add_argument('--token-file', type=Path, default=Path(os.environ.get('TOOLGATE_AGENT_TOKEN_PATH', ROOT / '.dev/debug/client-agent-token')))
    parser.add_argument('--ca-file', type=Path)
    parser.add_argument('--file', default='rahul-nigam.txt')
    parser.add_argument('--duration-seconds', type=int, default=8,
                        help='Repeat the existing file-write tool for 0..60 seconds (default: 8); 0 sends one write')
    args = parser.parse_args()
    try: mimic(Agent(args.gateway, args.token_file, resolve_ca(args.gateway, args.ca_file)), args.file, args.duration_seconds)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        # Exceptions from OS/TLS can include private filenames; keep those generic.
        print('FAILED: ' + (str(failure) if isinstance(failure, ValueError) else 'Cannot connect or load the agent credential/CA; check configuration and Gateway health'), file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
