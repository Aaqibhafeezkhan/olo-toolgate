# Coordinated current Gateway deployment

Deploy the Gateway version tested with your Control, console, clients and contract
set. Earlier wire formats and mixed-version operation are unsupported. Use immutable image references, validate mounted credentials and online Control
configuration, and render/lint the deployment before changing replicas.

Check readiness, authenticated discovery, a permitted effect and a denied/revoked
effect after deployment. A Control outage must stop new protected effects. Old static
policy, bundle evaluation and Gateway permit-signing settings are retired.

Rolling back application manifests does not roll back Control's database, identity,
authorization epochs or spent nonce history. Do not restore an old evaluator or
credential snapshot to regain access. Follow [fresh Control initialization](../control-plane/upgrades.md)
and [reviewed recovery](../enterprise-access-control/operations.md).

See [configuration](configuration.md), [deployment](deployment.md),
[current-contract policy](../architecture/compatibility.md) and
[release process](../development/release-process.md).
