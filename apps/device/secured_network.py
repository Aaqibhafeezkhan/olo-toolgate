# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Install a default-deny network policy before starting any device networking."""
import ipaddress
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
from urllib.parse import urlsplit


def settings(environ):
    url = urlsplit(environ.get('TOOLGATE_GATEWAY_URLS', ''))
    if (url.scheme != 'https' or not url.hostname or url.username is not None or
            url.password is not None or url.path not in ('', '/') or url.query or url.fragment or
            not re.fullmatch(r'[a-zA-Z0-9_.-]+', url.hostname)):
        raise ValueError('Set exactly one HTTPS Gateway origin (localhost or TLS hostname)')
    try:
        numeric = ipaddress.IPv4Address(url.hostname)
    except ValueError:
        pass
    else:
        if str(numeric) != '127.0.0.1':
            raise ValueError('Use localhost or a TLS hostname, rather than a numeric Gateway URL')
    port = url.port if url.port is not None else 443
    if not 1 <= port <= 65535:
        raise ValueError('Invalid Gateway URL port')
    address = ipaddress.IPv4Address(environ['TOOLGATE_SECURED_GATEWAY_IPV4'])
    if (address.is_unspecified or address.is_loopback or address.is_multicast or
            address.is_link_local or address == ipaddress.IPv4Address('255.255.255.255')):
        raise ValueError('Gateway IPv4 must be a non-loopback unicast address')
    upstream_port = int(environ.get('TOOLGATE_SECURED_GATEWAY_PORT', '8443'))
    if not 1 <= upstream_port <= 65535:
        raise ValueError('Invalid Gateway TCP port')
    ca = Path(environ['TOOLGATE_GATEWAY_CA_FILE'])
    if not ca.is_absolute() or not ca.is_file():
        raise ValueError('Mount the public Gateway CA certificate at an absolute path')
    # Validate trust before launching the native client; never fetch trust over a second port.
    ssl.create_default_context(cafile=str(ca))
    if environ.get('TOOLGATE_GATEWAY_CONSOLE_URLS'):
        raise ValueError('Secured devices do not use an HTTP console')
    return dict(origin='https://'+url.hostname+(':'+str(port) if port != 443 else ''),
                hostname=url.hostname, port=port, address=str(address), upstream_port=upstream_port,
                ca=str(ca))


def rules(config):
    address, port, local = config['address'], config['upstream_port'], config['port']
    # Filter OUTPUT before Docker's embedded-DNS DNAT. A filter-priority rule alone
    # can miss traffic rewritten from 127.0.0.11:53 to the resolver's private port.
    return f'''add table inet olo_toolgate_device
flush table inet olo_toolgate_device
table inet olo_toolgate_device {{
  chain input {{
    type filter hook input priority 0; policy drop;
    ct state invalid drop
    ip saddr {address} tcp sport {port} ct direction reply ct state established accept
    iifname "lo" ip saddr 127.0.0.1 ip daddr 127.0.0.1 tcp dport {local} ct direction original ct state {{ new, established }} accept
    iifname "lo" ip saddr 127.0.0.1 ip daddr 127.0.0.1 tcp sport {local} ct direction reply ct state established accept
  }}
  chain output {{
    type filter hook output priority -150; policy drop;
    ct state invalid drop
    ip daddr {address} tcp dport {port} ct direction original ct state {{ new, established }} accept
    oifname "lo" ip saddr 127.0.0.1 ip daddr 127.0.0.1 tcp dport {local} ct direction original ct state {{ new, established }} accept
    oifname "lo" ip saddr 127.0.0.1 ip daddr 127.0.0.1 tcp sport {local} ct direction reply ct state established accept
  }}
  chain forward {{
    type filter hook forward priority 0; policy drop;
  }}
}}
'''


def main():
    config = settings(os.environ)
    status = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines()
                  if ':' in line)
    # These two capabilities exist only during firewall setup. Refuse broader privileges.
    expected = (1 << 12) | (1 << 8)  # NET_ADMIN and SETPCAP
    if os.getuid() != 0 or any(int(status[key].strip(), 16) != expected for key in ('CapEff', 'CapBnd')):
        raise RuntimeError('Start with --cap-drop=ALL --cap-add=NET_ADMIN --cap-add=SETPCAP')
    if status['NoNewPrivs'].strip() != '1':
        raise RuntimeError('Start with --security-opt=no-new-privileges')
    subprocess.run(['nft', '-f', '-'], input=rules(config), text=True, check=True)
    # The CA's hostname stays intact for TLS verification. Resolution is local and
    # deterministic; the relay's only external destination is the pinned IPv4/port.
    if config['hostname'] not in ('localhost', '127.0.0.1'):
        hosts = Path('/etc/hosts')
        lines = []
        for line in hosts.read_text().splitlines():
            entries = line.split('#', 1)[0].split()
            if config['hostname'] in entries[1:]:
                entries = [entry for entry in entries if entry != config['hostname']]
                if len(entries) < 2:
                    continue
                line = ' '.join(entries)
            lines.append(line)
        hosts.write_text('\n'.join(lines)+'\n127.0.0.1 '+config['hostname']+'\n')
    print('Network locked: TCP '+config['address']+':'+str(config['upstream_port'])+
          '; all other external ingress/egress, DNS and IPv6 blocked.', flush=True)
    os.execv('/usr/bin/setpriv', ['setpriv', '--bounding-set=-all', '--inh-caps=-all',
        '--ambient-caps=-all', '--no-new-privs', sys.executable, '-u', '/opt/toolgate/secured_runtime.py'])


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as failure:
        print('Secured device startup failed: '+str(failure), file=sys.stderr, flush=True)
        raise SystemExit(1)
