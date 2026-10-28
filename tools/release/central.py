# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Sign a Maven-layout bundle and publish it through Sonatype Central's Portal API."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

UPLOAD_URL = 'https://central.sonatype.com/api/v1/publisher/upload'
API_URL = 'https://central.sonatype.com/api/v1/publisher/'


def gpg(home, *args, input=None):
    result = subprocess.run(['gpg', '--homedir', str(home), '--batch', '--yes', *args],
                            input=input, text=True, capture_output=True)
    if result.returncode:
        raise ValueError('GPG failed; check MAVEN_GPG_PRIVATE_KEY and MAVEN_GPG_PASSPHRASE')
    return result.stdout


def bundle(assets, version, folder, pom_path=None):
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z_.-]*', version) or version.endswith('-SNAPSHOT'):
        raise ValueError('Central requires a safe, non-SNAPSHOT version')
    prefix = 'toolgate-contracts-' + version
    files = [(assets/(prefix+suffix), prefix+suffix)
             for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom')]
    if pom_path is not None:
        files[-1] = (pom_path, prefix+'.pom')
    module = assets/(prefix+'.module')
    if module.exists():
        identity = json.loads(module.read_text(encoding='utf-8')).get('component', {})
        if [identity.get(k) for k in ('group', 'module', 'version')] != ['io.github.olo-labs', 'toolgate-contracts', version]:
            raise ValueError('Gradle module identity mismatch')
        files.append((module, module.name))
    for source, _ in files:
        if not source.is_file():
            raise ValueError('Missing Maven artifact: ' + source.name)
    pom = ET.parse(files[3][0]).getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    if [pom.findtext('m:'+field, namespaces=ns) for field in ('groupId', 'artifactId', 'version')] != ['io.github.olo-labs', 'toolgate-contracts', version]:
        raise ValueError('Maven POM identity mismatch')
    for field in ('name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                  'developers/developer/name', 'scm/connection', 'scm/url'):
        if not (pom.findtext('/'.join('m:'+part for part in field.split('/')), namespaces=ns) or '').strip():
            raise ValueError('Central requires POM metadata: ' + field)
    key = os.environ.get('MAVEN_GPG_PRIVATE_KEY', '')
    if not key:
        raise ValueError('Central requires MAVEN_GPG_PRIVATE_KEY (ASCII-armored private signing key)')
    home = folder/'gnupg'
    home.mkdir(mode=0o700)
    try:
        gpg(home, '--import', input=key)
        destination = folder/'repository/io/github/olo-labs/toolgate-contracts'/version
        destination.mkdir(parents=True)
        for source, name in files:
            target = destination/name
            shutil.copyfile(source, target)
            gpg(home, '--pinentry-mode', 'loopback', '--passphrase-fd', '0',
                '--armor', '--detach-sign', str(target),
                input=os.environ.get('MAVEN_GPG_PASSPHRASE', '')+'\n')
            for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                (destination/(name+'.'+algorithm)).write_text(
                    hashlib.new(algorithm, target.read_bytes()).hexdigest(), encoding='ascii')
        archive = folder/'central-bundle.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
            for path in sorted(destination.iterdir()):
                output.write(path, path.relative_to(folder/'repository').as_posix())
        return archive
    finally:
        # Release the private keyring before TemporaryDirectory cleanup (also on Windows).
        subprocess.run(['gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'],
                       capture_output=True, check=False)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(endpoint, token, data=b'', content_type=None):
    headers = {'Authorization': 'Bearer '+token}
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(API_URL+endpoint, data=data, headers=headers, method='POST')
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=120) as response:
            return response.read().decode('utf-8')
    except urllib.error.HTTPError as error:
        raise ValueError(f'Central API {endpoint.split("?")[0]} returned HTTP {error.code}; check Portal token and namespace access') from None


def publish(assets, version, pom_path=None, deployment_id=None, on_deployment=None):
    if os.environ.get('MAVEN_REPOSITORY_URL') != UPLOAD_URL:
        raise ValueError('Central publication requires '+UPLOAD_URL)
    username = os.environ.get('MAVEN_USERNAME', '')
    password = os.environ.get('MAVEN_PASSWORD', '')
    if not username or not password:
        raise ValueError('Central requires MAVEN_USERNAME and MAVEN_PASSWORD from a Portal user token')
    token = base64.b64encode((username+':'+password).encode()).decode('ascii')
    with tempfile.TemporaryDirectory(prefix='toolgate-central-') as temporary:
        if deployment_id is None:
            archive = bundle(Path(assets), version, Path(temporary), pom_path)
            boundary = 'toolgate-'+uuid.uuid4().hex
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
                    'Content-Type: application/octet-stream\r\n\r\n').encode()+archive.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
            deployment = str(uuid.UUID(request('upload?publishingType=AUTOMATIC', token, body,
                                              'multipart/form-data; boundary='+boundary).strip()))
        else:
            deployment = str(uuid.UUID(deployment_id))
        print('Central deployment: '+deployment, flush=True)
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a', encoding='utf-8') as output:
                output.write(f'Central deployment `{deployment}` for `io.github.olo-labs:toolgate-contracts:{version}`\n')
        if deployment_id is None and on_deployment:
            on_deployment(deployment)
        deadline = time.monotonic()+1200
        previous = None
        while time.monotonic() < deadline:
            status = json.loads(request('status?id='+deployment, token))
            state = status.get('deploymentState')
            if state != previous:
                print('Central state: '+str(state), flush=True)
                previous = state
            if state == 'PUBLISHED':
                return
            if state == 'FAILED':
                raise ValueError('Central validation failed: '+json.dumps(status.get('errors', {})))
            if state not in ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING'):
                raise ValueError('Unexpected Central deployment state: '+str(state))
            time.sleep(10)
        raise TimeoutError('Central publication timed out; inspect deployment '+deployment+' before retrying')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--pom', type=Path)
    args = parser.parse_args()
    publish(args.assets, args.version, args.pom)


if __name__ == '__main__':
    main()
