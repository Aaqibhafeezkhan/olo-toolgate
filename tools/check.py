# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Required foundation gates, without silently skipping missing tools.

Windows contributors may set TOOLGATE_DOCKER_TOOLS=1 to use isolated Rust,
PHP and Helm tooling. Java, Node and Python still execute on the host.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCKER = os.environ.get('TOOLGATE_DOCKER_TOOLS') == '1'
# Independent groups of the shared CI gates. The foundation workflow runs each as its own job;
# --ci-common without --ci-part runs all of them in this process, in the original order.
CI_PARTS = ('python', 'ui', 'browser', 'java', 'rust', 'examples')


def command(args):
    exe, *rest = args
    if exe == 'gradle':
        return [str(ROOT/('gradlew.bat' if os.name == 'nt' else 'gradlew')), '--no-daemon', *rest]
    if exe == 'npm' and os.name == 'nt': exe = 'npm.cmd'
    if DOCKER and exe in ('cargo','php','helm'):
        images = {'cargo':'rust:1.94-bookworm','php':'php:8.2-cli','helm':'alpine/helm:3.17.3'}
        prefix = ['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work','-w','/work']
        if exe == 'cargo':
            registry = ROOT/'.dev/cargo-registry'
            registry.mkdir(parents=True, exist_ok=True)
            prefix += ['-v',f'{registry.as_posix()}:/usr/local/cargo/registry']
            # bash positional arguments preserve argument boundaries without shell interpolation.
            return prefix + [images[exe], 'sh','-c','rustup component add rustfmt clippy >/dev/null && exec cargo "$@"','toolgate-cargo', *rest]
        return prefix + (['--entrypoint','helm'] if exe == 'helm' else []) + [images[exe]] + ([] if exe == 'helm' else [exe]) + rest
    if not shutil.which(exe): raise SystemExit(f'Required tool missing: {exe}; see docs/development/foundation.md')
    return [exe,*rest]


def run(args, capture=False, expect_failure=False):
    print('+ ' + ' '.join(args), flush=True)
    result = subprocess.run(command(args), cwd=ROOT, text=True, capture_output=capture or expect_failure)
    if expect_failure:
        if result.returncode == 0: raise SystemExit('Expected rejection but command passed: '+args[0])
        print('Expected negative-path rejection verified')
    elif result.returncode:
        if result.stdout: print(result.stdout)
        if result.stderr: print(result.stderr, file=sys.stderr)
        raise SystemExit(result.returncode)
    return result


def python(*args):
    return run([sys.executable,*args])


def publication_proof(prebuilt_ui=False):
    local = ROOT/'.dev/maven-local-proof'
    local.mkdir(parents=True, exist_ok=True)
    version = (ROOT/'packages/contracts/VERSION').read_text().strip()
    run(['gradle', f'-Dmaven.repo.local={local.as_posix()}', ':contracts-java:publishToMavenLocal'])
    artifact = local/'io/github/olo-labs/toolgate-contracts'/version
    for classifier in ('','-sources','-javadoc'):
        file = artifact/f'toolgate-contracts-{version}{classifier}.jar'
        if not file.is_file(): raise SystemExit('Publication artifact missing: '+file.name)
        with zipfile.ZipFile(file) as jar:
            for document in ('LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md'):
                if jar.read('META-INF/'+document) != (ROOT/document).read_bytes():
                    raise SystemExit('Published release document mismatch: '+file.name+'/'+document)
    pom = ET.parse(artifact/f'toolgate-contracts-{version}.pom').getroot()
    namespace = {'m':'http://maven.apache.org/POM/4.0.0'}
    if pom.findtext('m:groupId', namespaces=namespace) != 'io.github.olo-labs': raise SystemExit('Incorrect Maven group')
    if pom.findtext('m:artifactId', namespaces=namespace) != 'toolgate-contracts': raise SystemExit('Incorrect Maven identity')
    if pom.findtext('m:version', namespaces=namespace) != version: raise SystemExit('Incorrect Maven version')
    if not pom.findall('m:dependencies/m:dependency',namespace): raise SystemExit('Transitive serializer dependencies missing from POM')
    with zipfile.ZipFile(artifact/f'toolgate-contracts-{version}.jar') as jar:
        if 'io/ololabs/toolgate/contracts/EnterpriseDecision.class' not in jar.namelist(): raise SystemExit('Published generated models missing')
        if 'META-INF/LICENSE' not in jar.namelist(): raise SystemExit('Published license missing')
    tasks = [f':{service}:build' for service in ('control-plane',)]
    if prebuilt_ui: tasks.insert(0, '-PprebuiltAdminUi=true')
    updates = ['--write-locks'] if os.environ.get('TOOLGATE_UPDATE_LOCKS') == '1' else []
    run(['gradle','-PusePublishedContracts=true',f'-PcontractsRepository={local.as_posix()}','--rerun-tasks',*updates,*tasks])
    (ROOT/'.dev').mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='missing-contracts-', dir=ROOT/'.dev') as empty:
        negative = run(['gradle','-PusePublishedContracts=true',f'-PcontractsRepository={Path(empty).as_posix()}','--rerun-tasks',':control-plane:compileJava'], expect_failure=True)
        if 'toolgate-contracts' not in negative.stdout+negative.stderr:
            raise SystemExit('Negative proof failed for an unrelated reason')
    print('Local Maven JAR/POM/sources/Javadoc and all artifact-mode service builds verified')


def helm_checks():
    from gateway.check import helm_checks as gateway_helm_checks
    gateway_helm_checks()
    from control.helm import checks as control_helm_checks
    control_helm_checks(lint=False)
    from enterprise.helm import checks as enterprise_helm_checks
    enterprise_helm_checks()
    from client.helm import checks as endpoint_helm_checks
    endpoint_helm_checks()
    from deployment.helm import checks as fleet_helm_checks
    fleet_helm_checks()


def scans(staged_scan=True):
    workflows = [p.relative_to(ROOT).as_posix() for p in sorted((ROOT/'.github/workflows').glob('*.yml'))]
    run(['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work:ro','-w','/work','rhysd/actionlint:1.7.7',*workflows])
    run(['npm','audit','--audit-level=high'])
    python('-m','pip_audit','-r','tools/requirements.txt')
    python('-m','pip_audit','-r','apps/quickstart/requirements.txt')
    from dependency_licenses import audit
    metadata = run(['cargo','metadata','--format-version','1','--locked'], capture=True)
    audit(json.loads(metadata.stdout))
    # This read-only scan hook is runnable locally and is mandatory in CI.
    from source_scan import secret_scan
    secret_scan()
    if staged_scan: run(['node', 'tools/ci/secret-scan.mjs'])
    from policy.secret_scan import checks as secret_scan_checks
    secret_scan_checks()
    from source_scan import scan
    scan()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scans', action='store_true')
    parser.add_argument('--contracts-only', action='store_true')
    parser.add_argument('--publication-only', action='store_true')
    parser.add_argument('--gateway-only', action='store_true')
    parser.add_argument('--ci-common', action='store_true', help='Shared CI gates; preflight owns metadata/CI regressions and the image stage owns runtime smoke')
    parser.add_argument('--ci-part', action='append', choices=CI_PARTS, help='With --ci-common, run only this group of shared gates; repeatable')
    args = parser.parse_args()
    if args.ci_part and not args.ci_common: parser.error('--ci-part requires --ci-common')
    def part(name): return not args.ci_part or name in args.ci_part
    if args.scans: scans(staged_scan=not args.ci_common); return
    if args.publication_only:
        from control.check import database
        with database() as db:
            old = {name:os.environ.get(name) for name in ('CONTROL_TEST_URL','CONTROL_TEST_PASSWORD')}
            os.environ.update({name:db[name] for name in old})
            try: publication_proof()
            finally:
                for name,value in old.items():
                    if value is None: os.environ.pop(name,None)
                    else: os.environ[name]=value
        return
    if args.gateway_only:
        run(['cargo','test','-p','olo-toolgate-gateway','--locked']); return
    if not args.ci_common:
        python('tools/ci/preflight.py')
        python('tools/release/notes.py', '--check')
        python('-m','unittest','discover','-s','tests/ci','-v')
    if part('python'):
        python('tools/ui/generate.py','--check')
        python('tools/quality.py')
        python('-m','unittest','discover','-s','tests/contracts','-v')
        python('-m','unittest','discover','-s','tool-sdk/python-sdk/tests','-v')
    if args.ci_common and part('ui'):
        run(['npm','ci','--ignore-scripts'])
        run(['npm','run','contracts:check'])
        run(['npm','run','contracts:build'])
        run(['npm','--workspace','@olo-labs/toolgate-contracts','test'])
        run(['npm','--workspace','@olo-labs/toolgate-admin-ui','run','check'])
        run(['npm','--workspace','@olo-labs/toolgate-admin-ui','test'])
        run(['npm','--workspace','@olo-labs/toolgate-admin-ui','exec','--','vite','build'])
        run(['node','tools/ui/build.mjs','--metadata-only'])
    if args.ci_common and part('browser'):
        # A browser job on its own receives the built UI as an artifact but still needs node_modules.
        if not part('ui'): run(['npm','ci','--ignore-scripts'])
        run(['npm','--workspace','@olo-labs/toolgate-admin-ui','exec','--','playwright','install','--with-deps','chromium'])
        run(['node','tools/ci/browser-downloads.mjs','--skip-build'])
    if part('java'):
        from control.check import database
        with database() as db:
            old = {name:os.environ.get(name) for name in ('CONTROL_TEST_URL','CONTROL_TEST_PASSWORD')}
            os.environ.update({name:db[name] for name in old})
            try:
                run(['gradle',*(['-PprebuiltAdminUi=true'] if args.ci_common else []),'projects','javaCheck','build'])
                publication_proof(prebuilt_ui=args.ci_common)
                if not args.ci_common: run(['gradle',':control-plane:enterpriseTest'])
                python('tools/enterprise/custody.py')
            finally:
                for name,value in old.items():
                    if value is None: os.environ.pop(name,None)
                    else: os.environ[name]=value
    if part('rust'):
        run(['cargo','fmt','--all','--check'])
        run(['cargo','test','--workspace','--locked'])
        run(['cargo','clippy','--workspace','--all-targets','--locked','--','-D','warnings'])
    if part('examples'):
        # The Java SDK's Gradle and Maven plugins on a sample project, end to end through toolgate pack.
        if os.name != 'nt' and not DOCKER and shutil.which('mvn') and shutil.which('cargo'): run(['bash','tool-sdk/java-sdk/e2e/run.sh'])
        else: print('Skipping the Java SDK plugin end-to-end run: it needs bash, mvn and a local cargo')
        # The Java example tool packages under examples/java-tools and examples/mocked-tools: tests, @Examples, packs, and each fat jar answering tools/list.
        if os.name != 'nt' and not DOCKER and shutil.which('cargo'): run(['bash','examples/run-java-examples.sh'])
        else: print('Skipping the Java example tools: they need bash and a local cargo')
    if not args.ci_common:
        run(['npm','ci','--ignore-scripts'])
        run(['npm','run','contracts:check'])
        run(['npm','run','contracts:build'])
        run(['npm','--workspace','@olo-labs/toolgate-contracts','test'])
        run(['npm','run','ui:check'])
        run(['npm','--workspace','@olo-labs/toolgate-admin-ui','test'])
    if not args.contracts_only and not args.ci_common: python('tools/quickstart/check.py','--build')
    if args.ci_common and part('java'):
        python('tools/enterprise/check.py','--evidence-only')
    if part('ui'): python('tools/ui/package.py')
    if part('python'):
        run(['php','packages/contracts/php/tests/roundtrip.php'])
        run(['php','packages/contracts/php/tests/roundtrip_v2.php'])
        for source in sorted((ROOT/'packages/contracts/php/src').rglob('*.php')):
            run(['php','-l',source.relative_to(ROOT).as_posix()], capture=True)
        if not args.contracts_only: helm_checks()
    if args.ci_part: print('Foundation gate groups passed: '+', '.join(args.ci_part))
    else: print('All required foundation, gateway, control and admin UI gates passed')


if __name__ == '__main__': main()
