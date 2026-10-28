# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Select an available gateway, retain its enrollment, and supervise the native Linux client."""
import json
import os
from pathlib import Path
import shutil
import signal
import ssl
import subprocess
import sys
import threading
import urllib.parse
import urllib.request

import device_entrypoint as device

CONFIG = Path('/etc/olo-toolgate/client.json')
SOURCE = '/opt/toolgate/client'
SELECTED = Path('/etc/olo-toolgate/container-gateway.json')


def gateway_urls(value):
    urls = []
    for candidate in value.split(','):
        candidate = candidate.strip()
        url = urllib.parse.urlsplit(candidate if '://' in candidate or not candidate else 'https://'+candidate)
        if (url.scheme != 'https' or not url.hostname or url.username is not None or
                url.password is not None or url.query or url.fragment or url.path not in ('', '/') or
                url.hostname in ('169.254.169.254', 'metadata.google.internal', '0.0.0.0', '::')):
            raise ValueError('TOOLGATE_GATEWAY_URLS requires comma-separated HTTPS gateway origins')
        # Validate the port before any installation takes place.
        port = url.port
        host = url.hostname.lower()
        authority = '['+host+']' if ':' in host else host
        origin = 'https://'+authority+(':'+str(port) if port and port != 443 else '')
        if origin not in urls:
            urls.append(origin)
    if len(urls) > 16:
        raise ValueError('At most 16 gateway URLs are supported')
    return urls


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def ready(opener, server):
    try:
        with opener.open(server+'/.well-known/olo-toolgate-client', timeout=3) as response:
            document = json.loads(response.read(131200))
            # Tenant discovery v2 is a compact JWS envelope; the client verifies it before enrollment.
            return (response.status == 200 and isinstance(document, dict) and
                    isinstance(document.get('jws'), str) and document['jws'].count('.') == 2)
    except (OSError, ValueError):
        return False


def console_urls(urls, value='', ca=None):
    if value:
        consoles = [item.strip() for item in value.split(',')]
        if len(consoles) != len(urls):
            raise ValueError('TOOLGATE_GATEWAY_CONSOLE_URLS must match the gateway URL count')
    else:
        consoles = []
        for server in urls:
            url = urllib.parse.urlsplit(server)
            if not ca and url.hostname == 'host.docker.internal' and 18450 <= (url.port or 443) <= 18549:
                consoles.append('http://host.docker.internal:'+str(url.port-360))
            elif not ca and url.hostname != 'localhost' and '.' not in url.hostname and ':' not in url.hostname:
                consoles.append('http://'+url.hostname+':8080')
            else:
                consoles.append('')
    for server, console in zip(urls, consoles):
        if not console:
            continue
        url, gateway = urllib.parse.urlsplit(console), urllib.parse.urlsplit(server)
        if (url.scheme != 'http' or url.hostname != gateway.hostname or not url.port or
                url.username is not None or url.password is not None or url.query or url.fragment or
                url.path not in ('', '/')):
            raise ValueError('A local console must be an HTTP origin on its specified gateway host')
    return dict(zip(urls, consoles))


def local_trust(console):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    # The local-download API accepts the same loopback Host used by the native
    # install command through our byte-preserving HTTP relay.
    request = urllib.request.Request(console.rstrip('/')+'/api/public/v1/clients/local-trust',
                                     headers={'Host': '127.0.0.1:'+str(urllib.parse.urlsplit(console).port)})
    with opener.open(request, timeout=3) as response:
        trust = json.loads(response.read(20001))
    origin = gateway_urls(trust['serverUrl'])[0]
    url = urllib.parse.urlsplit(origin)
    if url.hostname not in ('localhost', '127.0.0.1') or not url.port:
        raise ValueError('Docker console bootstrap requires an advertised loopback HTTPS gateway')
    return origin, trust['caCertificatePem']


def ready_local(server, console):
    relay = None
    thread = None
    try:
        origin, ca = local_trust(console)
        context = ssl.create_default_context(cadata=ca)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                             urllib.request.HTTPSHandler(context=context))
        gateway, advertised = urllib.parse.urlsplit(server), urllib.parse.urlsplit(origin)
        relay = device.Relay(advertised.port, gateway.hostname, gateway.port or 443)
        thread = threading.Thread(target=relay.serve_forever, daemon=True)
        thread.start()
        return ready(opener, origin)
    except (OSError, ValueError, KeyError):
        return False
    finally:
        if relay is not None:
            if thread is not None:
                relay.shutdown()
                thread.join(timeout=5)
            relay.server_close()


def select_gateway(urls, opener, saved=None, consoles=None):
    # Enrollment belongs to an exact gateway. An outage never moves that identity elsewhere.
    if saved is not None:
        if saved not in urls:
            raise ValueError('The enrolled gateway is absent from TOOLGATE_GATEWAY_URLS; use separate device volumes for a new gateway')
        return saved
    while not device.STOP.is_set():
        device.publish({'state': 'WAITING_GATEWAY', 'gatewayUrls': urls}, None)
        for server in urls:
            if device.STOP.is_set():
                return None
            console = (consoles or {}).get(server)
            if (ready_local(server, console) if console else ready(opener, server)):
                return server
        print('Waiting for an available gateway; retrying in 5 seconds.', flush=True)
        device.STOP.wait(5)
    return None


def main():
    signal.signal(signal.SIGTERM, lambda *_: device.STOP.set())
    signal.signal(signal.SIGINT, lambda *_: device.STOP.set())
    urls = gateway_urls(os.environ.get('TOOLGATE_GATEWAY_URLS', ''))
    ca = os.environ.get('TOOLGATE_GATEWAY_CA_FILE')
    consoles = console_urls(urls, os.environ.get('TOOLGATE_GATEWAY_CONSOLE_URLS', ''), ca)
    context = ssl.create_default_context(cafile=ca)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                         urllib.request.HTTPSHandler(context=context))
    device.STATUS.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    saved = json.loads(CONFIG.read_text()) if CONFIG.exists() else None
    selected = json.loads(SELECTED.read_text())['sourceUrl'] if SELECTED.exists() else None
    server = select_gateway(urls, opener, selected or (saved['serverUrl'] if saved else None), consoles)
    if server is None:
        return
    if consoles[server]:
        upstream, console = urllib.parse.urlsplit(server), urllib.parse.urlsplit(consoles[server])
        if saved:
            origin = saved['serverUrl']
        else:
            origin, _ = local_trust(consoles[server])
        advertised = urllib.parse.urlsplit(origin)
        os.environ.update(TOOLGATE_DEVICE_UPSTREAM=upstream.hostname,
            TOOLGATE_DEVICE_UPSTREAM_HTTP_PORT=str(console.port),
            TOOLGATE_DEVICE_UPSTREAM_TLS_PORT=str(upstream.port or 443),
            TOOLGATE_DEVICE_HTTP_PORT=str(console.port), TOOLGATE_DEVICE_TLS_PORT=str(advertised.port),
            TOOLGATE_DEVICE_SERVER_URL=origin, TOOLGATE_DEVICE_WAIT_TIMEOUT='0')
        SELECTED.write_text(json.dumps(dict(sourceUrl=server)))
        device.main()
        return
    if saved is None:
        print('Available gateway: '+server+'; installing the published client.', flush=True)
        # Install verifies signed discovery over HTTPS before any config exists, so the
        # private gateway CA must already be the trust root for that one command.
        environment = dict(os.environ, SSL_CERT_FILE=ca) if ca else None
        subprocess.run([SOURCE, 'install', '--server', server], check=True, timeout=60, env=environment)
        if ca:
            settings = json.loads(CONFIG.read_text())
            installed_ca = CONFIG.parent/'gateway-trust.crt'
            installed_ca.write_bytes(Path(ca).read_bytes())
            installed_ca.chmod(0o644)
            settings['caCertificatePath'] = str(installed_ca)
            CONFIG.write_text(json.dumps(settings))
    else:
        # The config/state volumes hold the key; the executable always comes from this image.
        shutil.copyfile(SOURCE, device.CLIENT)
        os.chmod(device.CLIENT, 0o700)
    device.supervise()


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        print('Device startup failed: '+str(failure), file=sys.stderr, flush=True)
        raise SystemExit(1)
