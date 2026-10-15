# ToolGate Helm chart

The chart deploys separate Gateway and Control workloads. Both are disabled by
default. The console is served from Control; no separate UI workload is required.
Use [values.yaml](values.yaml) and [values.schema.json](values.schema.json) for the
current supported fields. Unknown settings and embedded secret values are rejected.

## Configure

- Select tested image versions/digests and existing image-pull Secrets.
- Configure Gateway credentials and `gateway.control` with the current Control
  HTTPS origin and protected service-token Secret. Gateway has no static policy,
  offline bundle evaluator or effect signer.
- Configure Control's external PostgreSQL runtime/migration credentials, identity
  verification and separate purpose-specific custody using existing Secret references.
- Set explicit ingress TLS and NetworkPolicy peers for runtime, management, Control,
  database and DNS traffic. Keep management endpoints restricted.
- Configure replica/resources, PDB and optional HPA/ServiceMonitor for your cluster.

The chart does not provision a database, identity provider or customer key custody.
Read [production prerequisites](../../../docs/deployment/production.md),
[Control configuration](../../../docs/control-plane/configuration.md),
[Gateway configuration](../../../docs/gateway/configuration.md) and
[fleet deployment](../../../docs/client/package-deployment.md).

## Validate

```sh
helm lint --strict deploy/helm/olo-toolgate
helm template toolgate deploy/helm/olo-toolgate
helm template toolgate deploy/helm/olo-toolgate --is-upgrade
python tools/enterprise/helm.py
```

Also render your actual values file before applying. The repository gate validates
enabled/disabled and negative configurations; it does not certify your cluster's
availability, network trust or capacity. Follow [upgrade and recovery](../../../docs/control-plane/upgrades.md)
before rollback: application manifests do not reverse database or authority history.
