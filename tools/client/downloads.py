# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real public-download container and browser proof against the validated native release bundle."""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from control.check import ROOT, request
from control.container import fixture

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',default='olo-toolgate-control:module07-downloads');args=parser.parse_args()
    with fixture(args.image) as installed:
        runtime=installed['origins'][0]
        def fetch(url):
            return request(url,context=installed['sslContext'])
        url=runtime+'/api/public/v1/clients'
        status,raw,_=fetch(url);assert status==200;manifest=json.loads(raw)
        assert {a['platform'] for a in manifest['artifacts']}=={'WINDOWS','MACOS','LINUX'}
        for artifact in manifest['artifacts']:
            status,data,headers=fetch(url+'/'+artifact['filename'])
            if artifact['platform']=='WINDOWS':
                assert status==404;continue
            assert status==200
            assert len(data)==artifact['bytes'] and hashlib.sha256(data).hexdigest()==artifact['sha256']
            assert headers['X-Content-Type-Options']=='nosniff'
        assert fetch(runtime+'/api/control/v1/users')[0]==401
        for name in ['missing.zip','device-key','..%2F..%2Fetc%2Fpasswd']:
            assert fetch(url+'/'+name)[0] in (400,404)
        browser_env=dict(os.environ,UI_TEST_ORIGIN=runtime,UI_TEST_DOWNLOADS_EXPECTED='true',
                         NODE_EXTRA_CA_CERTS=str(installed['caCertificatePath']),
                         UI_TEST_TLS_CERTIFICATE=str(installed['serverCertificatePath']))
        subprocess.run(['npm.cmd' if os.name=='nt' else 'npm','--workspace','@olo-labs/toolgate-admin-ui','run','e2e','--','downloads.spec.ts'],cwd=ROOT,env=browser_env,check=True)
        output=ROOT/'build/client';output.mkdir(parents=True,exist_ok=True)
        (output/'public-downloads-proof.json').write_text(json.dumps({'version':manifest['version'],'manifestPlatforms':3,'downloadedPlatforms':['LINUX','MACOS'],'unsignedWindowsRejected':True,'anonymousArchiveChecksums':True,'privateApisStillProtected':True,'filenameAllowlist':True,'realBrowser':True},indent=2)+'\n')
        print('Linux/macOS packages download anonymously; unsigned Windows is refused; browser/roles/checksums/allowlist passed')

if __name__=='__main__':main()
