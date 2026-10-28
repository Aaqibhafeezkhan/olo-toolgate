# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Qualify live tenant routing, device identity and key rotation in an isolated local image."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import secrets

ROOT = Path(__file__).resolve().parents[2]


def run(*arguments, **options):
    return subprocess.run(['docker', *arguments], check=True, text=True, capture_output=True, **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='ololab/olo-toolgate-quickstart:dev')
    parser.add_argument('--control-app', type=Path, help='Matching packaged quarkus-app directory; otherwise use image contents')
    parser.add_argument('--gateway-binary', type=Path, help='Matching Linux Gateway binary; otherwise use image contents')
    parser.add_argument('--output', type=Path, default=ROOT/'.dev/tenant-runtime-evidence.json')
    parser.add_argument('--postgresql',action='store_true',help='Fresh PostgreSQL 17 with separate runtime, migrator and platform credentials')
    args = parser.parse_args()
    for path, directory in [(args.control_app, True), (args.gateway_binary, False)]:
        if path is not None and not (path.is_dir() if directory else path.is_file()):
            parser.error('A supplied runtime artifact is missing')
    # Only this invocation's fresh names can be cleaned up. No existing application volumes are read.
    volume = 'toolgate-tenant-check-'+uuid.uuid4().hex
    container = None;postgres=None
    run('volume', 'create', volume)
    try:
        mounts = []
        sources = [
            (ROOT/'tools/tenancy/runtime_fixture.py', '/tmp/runtime-fixture.py'),
            (ROOT/'apps/quickstart/supervisor.py', '/opt/quickstart/supervisor.py'),
            (ROOT/'apps/quickstart/enterprise_bootstrap.py', '/opt/quickstart/enterprise_bootstrap.py'),
            (ROOT/'tools/enterprise/restore.py', '/opt/quickstart/enterprise_restore.py'),
        ]
        if args.control_app is not None:
            sources.append((args.control_app.resolve(), '/app'))
        if args.gateway_binary is not None:
            sources.append((args.gateway_binary.resolve(), '/usr/local/bin/olo-toolgate-gateway'))
        for source, target in sources:
            mounts += ['-v', source.as_posix()+':'+target+':ro']
        command = ['run', '--pull=never', '-d', '--network', 'none', '--memory=1800m', '--cpus=3']
        for host in ['tenant-a.example.test', 'tenant-b.example.test', 'unknown.example.test']:
            command += ['--add-host', host+':127.0.0.1']
        command += ['-v', volume+':/data', *mounts,
                    '-e', 'TOOLGATE_CONTROL_BUILDER_ENABLED=false',
                    '-e', 'TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH=',
                    '-e', 'TOOLGATE_CONTROL_PROFILE_PATH=',
                    ]
        environment=dict(os.environ)
        if args.postgresql:
            password=secrets.token_hex(32)
            environment.update(POSTGRES_PASSWORD=password,QUARKUS_DATASOURCE_PASSWORD=password,QUARKUS_FLYWAY_PASSWORD=password,TOOLGATE_TEST_PLATFORM_PASSWORD=password)
            command+=['-e','TOOLGATE_TEST_POSTGRESQL=true','-e','TOOLGATE_QUICKSTART_DATABASE_MODE=postgresql',
                      '-e','QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/control?sslmode=disable',
                      '-e','QUARKUS_DATASOURCE_USERNAME=control_app','-e','QUARKUS_DATASOURCE_PASSWORD',
                      '-e','QUARKUS_FLYWAY_USERNAME=control_migrator','-e','QUARKUS_FLYWAY_PASSWORD',
                      '-e','TOOLGATE_CONTROL_PLATFORM_USERNAME=control_provisioner','-e','TOOLGATE_CONTROL_PLATFORM_PASSWORD_PATH=/data/platform-password',
                      '-e','TOOLGATE_TEST_PLATFORM_PASSWORD','-e','TOOLGATE_CONTROL_DEVELOPMENT_MODE=true']
        command+=['--entrypoint','/opt/quickstart-python/bin/python',args.image,'/tmp/runtime-fixture.py']
        container = run(*command,env=environment).stdout.strip()
        if args.postgresql:
            postgres=run('run','-d','--memory=512m','--cpus=2','--network','container:'+container,'-e','POSTGRES_PASSWORD',
                         'postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652',env=environment).stdout.strip()
            deadline=time.monotonic()+60
            while subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True).returncode:
                if time.monotonic()>deadline:raise TimeoutError('Fresh shared PostgreSQL readiness timeout')
                time.sleep(.25)
            statements="CREATE ROLE toolgate_control_runtime NOLOGIN;CREATE ROLE toolgate_control_platform NOLOGIN;"
            for role,extra in [('control_migrator','CREATEDB'),('control_app','IN ROLE toolgate_control_runtime'),('control_provisioner','IN ROLE toolgate_control_platform')]:
                statements+=f"CREATE ROLE {role} LOGIN PASSWORD '{password}' {extra};"
            statements+='CREATE DATABASE control OWNER control_migrator;'
            run('exec','-i',postgres,'psql','-U','postgres','-v','ON_ERROR_STOP=1',input=statements)
            run('exec',container,'/opt/quickstart-python/bin/python','-c',"from pathlib import Path;Path('/data/postgres-ready').touch()")
        deadline = time.monotonic()+480
        while True:
            state = json.loads(run('inspect', '--format', '{{json .State}}', container).stdout)
            if not state['Running']:
                logs = run('logs', '--tail', '20', container)
                print(logs.stdout, end='')
                if state['ExitCode'] != 0:
                    raise RuntimeError('Tenant runtime fixture failed: '+logs.stderr[-2000:])
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('Tenant runtime fixture exceeded its deadline')
            time.sleep(1)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        run('cp', container+':/data/evidence.json', str(args.output.resolve()))
        print('Public signed-discovery/certificate evidence: '+str(args.output.resolve()))
    finally:
        if postgres:subprocess.run(['docker','rm','-fv',postgres],capture_output=True)
        if container:
            subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        subprocess.run(['docker', 'volume', 'rm', volume], capture_output=True)


if __name__ == '__main__':
    main()
