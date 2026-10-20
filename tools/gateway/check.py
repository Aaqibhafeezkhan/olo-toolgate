# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Gateway Helm/render/Kubernetes-schema gates, usable locally and in CI."""
import hashlib
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

import yaml
from jsonschema import Draft7Validator

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from check import command, run


def monitor_validator():
    """Validate the optional CRD against the pinned upstream structural schema."""
    digest = '5b3c799bf99cf13012d4f6882a59a79bfd78c6fb8cdd34cecd4408ad7debe931'
    path = ROOT/'.dev/servicemonitor-crd.yaml'
    if not path.is_file():
        url = 'https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/v0.80.1/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml'
        with urllib.request.urlopen(url, timeout=30) as response: data = response.read()
        if hashlib.sha256(data).hexdigest() != digest: raise SystemExit('ServiceMonitor upstream checksum mismatch')
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest: raise SystemExit('ServiceMonitor cached checksum mismatch')
    crd = yaml.safe_load(data)
    schema = next(v['schema']['openAPIV3Schema'] for v in crd['spec']['versions'] if v['name']=='v1')
    return Draft7Validator(schema)


def helm_checks():
    chart = 'deploy/helm/olo-toolgate'
    run(['helm','lint','--strict',chart])
    for upgrade in ([], ['--is-upgrade']):
        rendered = run(['helm','template','foundation',chart,*upgrade], capture=True)
        if rendered.stdout.strip(): raise SystemExit('Disabled chart emitted resources')
    base = ['--set','gateway.enabled=true','--set','gateway.credentialsSecret=gateway-runtime',
            '--set','gateway.control.tokenSecret=gateway-authority',
            '--set','gateway.control.tenantId=10000000-0000-4000-8000-000000000001',
            '--set','gateway.config.tenantHosts[0]=gateway.example.invalid',
            '--set','gateway.networkPolicy.controlTo[0].podSelector.matchLabels.app=control',
            '--set',r'gateway.networkPolicy.dnsTo[0].namespaceSelector.matchLabels.kubernetes\.io/metadata\.name=kube-system']
    variants = {
        'base': [],
        'ha': ['--set','gateway.autoscaling.enabled=true','--set','gateway.replicaCount=3'],
        'ingress': ['--set','gateway.ingress.enabled=true','--set','gateway.ingress.className=nginx','--set','gateway.ingress.tlsSecret=gateway-tls'],
        'monitor': ['--set','gateway.serviceMonitor.enabled=true'],
        'selectors': ['--set','gateway.networkPolicy.runtimeFrom[0].namespaceSelector.matchLabels.access=gateway','--set','gateway.networkPolicy.managementFrom[0].podSelector.matchLabels.role=prometheus'],
    }
    evidence = ROOT/'build/gateway/render'; evidence.mkdir(parents=True, exist_ok=True)
    monitor_schema = monitor_validator()
    for name, flags in variants.items():
        for upgrade in ([], ['--is-upgrade']):
            rendered = run(['helm','template','gateway',chart,*base,*flags,*upgrade], capture=True).stdout
            docs = [d for d in yaml.safe_load_all(rendered) if d]
            kinds = {d['kind'] for d in docs}
            expected = {'Deployment','Service','ServiceAccount','ConfigMap','NetworkPolicy','PodDisruptionBudget'}
            if not expected <= kinds: raise SystemExit('Missing gateway resource in '+name)
            deployment = next(d for d in docs if d['kind'] == 'Deployment')
            pod = deployment['spec']['template']['spec']; container = pod['containers'][0]
            if pod['automountServiceAccountToken'] or not pod['securityContext']['runAsNonRoot'] or not container['securityContext']['readOnlyRootFilesystem']:
                raise SystemExit('Gateway security defaults weakened')
            if {p['containerPort'] for p in container['ports']} != {8081,9091}: raise SystemExit('Wrong ports')
            config = json.loads(next(d for d in docs if d['kind'] == 'ConfigMap')['data']['gateway.json'])
            if not config['trustedTlsProxy'] or set(config)!={'listen','managementListen','trustedTlsProxy','allowedOrigins','limits','control','tenantHosts'}: raise SystemExit('Closed online authority configuration required')
            if config['control']['tokenPath']!='/etc/toolgate/control-auth/access-token' or config['control']['developmentLoopbackHttp']:raise SystemExit('Protected service authority required')
            if any(v['name'] in ('permit-signing','bundle-keys','approval-auth') for v in pod['volumes']):raise SystemExit('Gateway cannot hold effect signing custody')
            monitor = next((d for d in docs if d['kind'] == 'ServiceMonitor'), None)
            if monitor and monitor['spec']['endpoints'][0]['path'] != '/v1/metrics': raise SystemExit('Wrong monitor path')
            if monitor: monitor_schema.validate(monitor)
            suffix = '-upgrade' if upgrade else ''
            path = evidence/f'{name}{suffix}.yaml'; path.write_text(rendered,encoding='utf-8',newline='\n')
            # Standard Kubernetes objects must resolve and validate. The optional
            # Prometheus CRD is validated against its pinned upstream schema above.
            run(['docker','run','--rm','-v',f'{ROOT.as_posix()}:/work:ro','ghcr.io/yannh/kubeconform:v0.6.7','-strict','-summary','-kubernetes-version','1.32.0','-skip','ServiceMonitor',f'/work/{path.relative_to(ROOT).as_posix()}'])
    for flags in ([], ['--set','gateway.config.limits.maxBodyBytes=0'], ['--set','gateway.securityContext.allowPrivilegeEscalation=true'], ['--set','gateway.config.listen=0.0.0.0:80'], ['--set','gateway.ingress.enabled=true'], ['--set','gateway.autoscaling.enabled=true','--set','gateway.autoscaling.minReplicas=9'], ['--set','gateway.terminationGracePeriodSeconds=1']):
        args = ['helm','template','invalid',chart,*base]
        if flags: args += flags
        else: args += ['--set','gateway.credentialsSecret=']
        run(args, expect_failure=True)
    print('Gateway Helm install/upgrade variants, core Kubernetes schemas and negative values passed')


if __name__ == '__main__':
    helm_checks()
