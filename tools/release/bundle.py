# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Create the reproducible raw contract bundle and release metadata."""
import argparse
import gzip
import hashlib
import io
import json
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def bundle_bytes():
    contracts = ROOT/'packages/contracts'
    paths = [ROOT/'LICENSE', ROOT/'NOTICE.md', ROOT/'RELEASE-NOTES.md', contracts/'VERSION', contracts/'contract-set.yaml', contracts/'generated-manifest.json']
    for folder in ('schemas','openapi','events'):
        paths.extend(p for p in (contracts/folder).rglob('*') if p.is_file())
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode='wb', mtime=0, filename='') as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as archive:
            for path in sorted(paths):
                data = path.read_bytes()
                info = tarfile.TarInfo(path.name if path.parent == ROOT else path.relative_to(contracts).as_posix())
                info.size = len(data)
                info.mode = 0o644
                info.mtime = 0
                archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def checksum_assets(folder):
    files = sorted(p for p in folder.iterdir() if p.is_file() and p.name != 'SHA256SUMS')
    (folder/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files), encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'build/release')
    parser.add_argument('--checksums-only', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.checksums_only:
        for document in ('LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md'):
            (args.output/document).write_bytes((ROOT/document).read_bytes())
        version = (ROOT/'packages/contracts/VERSION').read_text().strip()
        product = (ROOT/'VERSION').read_text().strip()
        (args.output/f'olo-toolgate-contracts-{version}.tar.gz').write_bytes(bundle_bytes())
        metadata = {'productVersion':product, 'contractsVersion':version, 'wireSchemaMajor':1, 'java':21, 'rust':'1.94.1', 'typescript':'5.9.3', 'php':'>=8.2', 'helmChartVersion':product,
                    'runtimeComponents':{'gateway':product,'control':product,'adminUi':product,'endpointClient':product,'otherServices':'not yet implemented'},
                    'adminUi':{'path':'/console/','packaging':'embedded in Control; same-origin API; external signed-token session','api':'Control v1'},
                    'policyBundle':{'formatVersion':3,'supportedFormatVersions':[3],'purpose':'metadata and administration; never an effect grant','jwsAlgorithm':'RS256','signedHeaderType':'toolgate-policy-bundle+jws','sequence':'monotonic per tenant'},
                    'approval':{'types':['OPERATION','CONFIGURATION'],'states':'durable per-invocation obligations; no temporary reusable grant','permit':'RS256 dedicated Control key; ten-second maximum; certificate-bound atomic nonce consume'},
                    'enterpriseAccessRevision':2,'gatewayApi':'/access/invocations; /mcp; retired decision and Gateway-consume routes return 404',
                    'controlApi':'/api/control/v1; group-only grants, scoped management and current effect authority',
                    'controlDatabase':{'postgresql':'17.11','flywaySchema':1,'sqliteSchema':1},
                    'endpoint':{'protocolVersion':1,'enrollment':'HTTPS browser code plus verified P-256 CSR','identity':'dedicated RSA device CA; actual mTLS peer; active registry','platforms':['windows-x64','windows-arm64','linux-x64','linux-arm64','macos-x64','macos-arm64'],'execution':True},
                    'mcp':{'version':'2026-07-28','capabilities':'stateless ingress; current group-authorized discovery and device-bound execution; per-invocation approvals'},
                    'gatewayImage':f'ghcr.io/olo-labs/olo-toolgate-gateway:{product}', 'controlImage':f'ghcr.io/olo-labs/olo-toolgate-control:{product}',
                    'compatibilityWindow':'enterprise access revision 2 is a breaking runtime/configuration boundary; preserved archival evidence grants no access', 'maven':f'io.github.olo-labs:toolgate-contracts:{version}', 'helmOci':f'oci://ghcr.io/olo-labs/charts/olo-toolgate:{product}'}
        (args.output/'compatibility.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8', newline='\n')
    checksum_assets(args.output)
    print(f'Release assets prepared: {args.output}')


if __name__ == '__main__': main()
