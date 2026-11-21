# Execution and verification

Start with the [enterprise model](../security/enterprise-access-control.md) and [Quickstart walkthrough](../getting-started/one-minute-quickstart.md). Fresh installation creates independent administrators, protected default groups, disabled Tools and an unconfigured Agent. Installation, ownership and device approval confer no runtime grant.

Install Java 21, Node, Docker and the pinned Python requirements. Run these owned, disposable checks from the repository:

```sh
python tools/contracts/generate.py --check
python tools/contracts/fixtures.py --check
python tools/enterprise/check.py
python tools/quickstart/check.py --build
python tools/quickstart/check.py --postgresql --no-browser
python tools/control/container.py
python tools/gateway/container.py
python tools/enterprise/helm.py
```

The fresh runtime check exercises real password sessions, verified disabled-user intake, reviewed group configuration, native CSR and mTLS enrollment, SERVICE and direct HUMAN effects, independent ASK approval, exact retries, revocation and restart. Browser checks cover the actual console. PostgreSQL uses an isolated owned database and separate runtime/migration roles. Production image checks validate actual binaries, restricted custody, live online authority and graceful shutdown. These checks never reset existing debug volumes or unrelated services.

Users join Teams; Agents join Agent Groups; Tools have exactly one Tool Group; Devices join one or more Device Groups. Add complete grants and explicit Tool Group/Device Group execution bindings through reviewed configuration. An Agent additionally requires CAPABILITY and either SERVICE authority or verified delegated User/Team authority. Examine impact and inherited provenance before independent approval and apply.

The debug mimic script only calls the two configured tools and logs redacted output. Its mounted token must already match an enabled workload binding and applicable group grants. Configure the diagnostic grants outside the script; device approval supplies no agent credential.

Production deployment requires externally provisioned identity verification, separate PostgreSQL migrator/runtime credentials with `verify-full`, distinct effect/snapshot/device/release signing domains, and current service/workload facts. See [operations and recovery](../enterprise-access-control/operations.md). Preserve identity, key, nonce, audit and retirement history. Recovery is a reviewed current-schema operation; old permissions are never an authorization fallback.
