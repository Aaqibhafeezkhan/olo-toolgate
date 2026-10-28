# Platform requirement references

This compact index connects stable requirement IDs in conformance fixtures to the
maintained specifications. It replaces the obsolete SDK planning narrative; the
IDs remain stable so test intent stays traceable. The referenced specification,
its current ADR amendments and the case's explicit assertions define the behavior.
An ID or an authored case does not establish implementation or a passing test.

Use the [component guides](../README.md) for supported behavior, limitations and
operational instructions. In particular,
host isolation, result spooling, kill delivery and language support must not be
inferred available from the requirement descriptions below.

Source citations use this file name and the numeric IDs below. Multiple-ID
citations resolve each ID independently; broader IDs such as §12 refer to the
corresponding family's maintained sources.
Historical database migrations retain their original `plan.md` citations to
preserve migration checksums; those numeric IDs resolve through this index too.

## Requirement index

| ID | Maintained source and scope |
|---|---|
| §2.1 | [Current component and authority boundaries](../../ARCHITECTURE.md) |
| §4 | [SDK/CLI layout and packaging ownership](../../tool-sdk/README.md) |
| §5.2 | [Declarations and value types](../../tool-sdk/spec/authoring/README.md) |
| §5.3 | [ToolContext and declaration scope](../../tool-sdk/spec/authoring/README.md) |
| §5.4 | [Routes and network access](../../tool-sdk/spec/authoring/http.md) |
| §5.5 | [Starter and development commands](../../tool-sdk/cli/README.md) |
| §5.6 | [Descriptor generations, semantic equality and digests](../../tool-sdk/spec/descriptor/README.md) |
| §5.7 | [Authoring execution/error contract](../../tool-sdk/spec/authoring/execution.md) |
| §5.8 | [Mode equivalence and capability matrix](../../tool-sdk/spec/runtime-modes.md) |
| §5.9 | [Verified standalone identity](../../tool-sdk/spec/runtime-modes.md) |
| §5.10 | [Official SDK adapters A1–A5; pinned compatibility evidence](../../tool-sdk/spec/protocol-layer.md) |
| §7 | [Package format and release trust](../adr/014-tool-package-format-v2.md) |
| §7.2 | [Descriptor/artifact/package identity](../../tool-sdk/spec/descriptor/README.md) |
| §7.3 | [Semantic descriptor format](../../tool-sdk/spec/descriptor/README.md) |
| §7.4 | [Runtime artifact and launcher boundary](../../apps/tool-host/README.md) |
| §7.6 | [Archive validation and packing rules](../../tool-sdk/cli/README.md) |
| §7.7 | [Library composition and narrowing mappings](../../tool-sdk/spec/composition.md) |
| §8.1 | [Upload, review and enable lifecycle](../adr/014-tool-package-format-v2.md) |
| §8.3 | [API/security delta, including service-profile changes](../adr/014-tool-package-format-v2.md) |
| §8.4 | [Version lifecycle and rollout requirements](../adr/014-tool-package-format-v2.md) |
| §8.5 | [Deployment binding and configuration precedence](../adr/020-deployment-binding.md) |
| §9.1 | [Identity and authorization separation](../adr/015-credentials-catalog-scope-routes.md) |
| §9.2 | [Credential classification; exactly one validator](../adr/015-credentials-catalog-scope-routes.md) |
| §9.3 | [Verified claims and bounded delegation](../adr/015-credentials-catalog-scope-routes.md) |
| §9.4 | [Catalog scope can narrow, never widen access](../adr/015-credentials-catalog-scope-routes.md) |
| §10.1 | [Current MCP version requirements](../adr/018-mcp-versions-legacy-adapter.md) |
| §10.2 | [Legacy adapter withdrawal; read the ADR amendment](../adr/018-mcp-versions-legacy-adapter.md) |
| §10.3 | [Tool names and compatibility aliases](../adr/018-mcp-versions-legacy-adapter.md) |
| §10.4 | [Scoped MCP routes](../adr/015-credentials-catalog-scope-routes.md) |
| §11 | [Tool Host trust domain](../adr/013-tool-host-egress-broker-secrets.md) |
| §11.2 | [Control-issued permits and pinned admission inputs](../adr/013-tool-host-egress-broker-secrets.md) |
| §11.3 | [Single-use consumption, ledger and fencing](../control-plane/invocation-state-v2.md) |
| §11.4 | [Admission responses and recovery requirements](../../tool-sdk/conformance/security/admission.json) |
| §11.5 | [Isolation tiers; see backlog for unimplemented enforcement](../security/threat-models/tool-host.md) |
| §11.6 | [Brokered versus tunnel egress requirements](../adr/013-tool-host-egress-broker-secrets.md) |
| §11.7 | [Effect safety, business-key namespaces, reservations and retention](../control-plane/invocation-state-v2.md) |
| §11.8 | [Invocation-bound broker, route constraints and secret custody](../../tool-sdk/conformance/security/broker.json) |
| §11.9 | [Transport matrix and explicit TCP proxy support](../../tool-sdk/spec/authoring/http.md) |
| §12.1 | [Invocation/task creation and ownership](../control-plane/invocation-state-v2.md) |
| §12.2 | [Task transitions, cancellation and unknown outcomes](../control-plane/invocation-state-v2.md) |
| §12.3 | [Business-key handling and upstream idempotency values](../../tool-sdk/java-sdk/README.md) |
| §12.4 | [Tasks extension and protocol adapter](../../tool-sdk/spec/protocol-layer.md) |
| §12.5 | [Task capability negotiation and requestState](../../tool-sdk/spec/protocol-layer.md) |
| §12.6 | [Task identity, visibility and result ownership](../control-plane/invocation-state-v2.md) |
| §12.8 | [Control-owned transaction model; no Gateway database role](../control-plane/invocation-state-v2.md) |
| §13.1 | [Server-tool execution](../../apps/tool-host/README.md) |
| §13.3 | [Tool protocol v2 frames](../../packages/contracts/schemas/v2/protocol.schema.json) |
| §13.4 | [Environment and process confinement requirements](../security/threat-models/tool-host.md) |
| §13.5 | [Input/output, time and resource ceilings](../../tool-sdk/conformance/security/sandbox.json) |
| §14.1 | [MCP proxy acceptance cases](../../tool-sdk/conformance/protocol/mcp-proxy.json) |
| §14.2 | [Planned OpenAPI transformation acceptance cases](../../tool-sdk/conformance/integration/openapi-import.json) |
| §14.3 | [Credential connection requirements](../../tool-sdk/spec/authoring/http.md) |
| §15 | [Emergency kill specification](../control-plane/kill-switch.md) |
| §15.1 | [Immutable kill/recovery events and merge](../control-plane/kill-switch.md) |
| §15.2 | [Delivery and authorization epoch](../control-plane/kill-switch.md) |
| §15.3 | [Online Control authority; no Gateway grace](../control-plane/kill-switch.md) |
| §15.4 | [Break-glass scope and threshold operator keys](../control-plane/kill-switch.md) |
| §15.5 | [Running-work termination and control loss](../control-plane/kill-switch.md) |
| §15.6 | [Reviewed recovery and retention](../control-plane/kill-switch.md) |
| §16 | [Catalog revision and package-version lifecycle](../adr/014-tool-package-format-v2.md) |
| §17 | [Client check-in settings](../control-plane/configuration.md) |
| §17.1 | [Environment-owned client timing fields](../control-plane/configuration.md) |
| §17.2 | [Server-directed polling and bounds](../control-plane/configuration.md) |
| §17.3 | [Fleet reconciliation and delivery](../adr/010-signed-fleet-reconciliation.md) |
| §17.4 | [Desired and reported state](../architecture/desired-vs-reported-state.md) |
| §18 | [Implemented Administration menu and settings ownership](../control-plane/configuration-menu.md) |
| §20 | [Contract-set generation and component integration](../contracts/v2-generation.md) |
| §21 | [Suite definitions and evidence rules](../../tool-sdk/conformance/README.md) |
| §22.1 | [Verification and acceptance evidence](../testing/README.md) |
| §22.2 | [Release qualification](../development/how-to-release.md) |

## 12.7 Persistence and failure requirements

These row IDs are used by failure-injection conformance cases. Control's actual
states and transaction boundaries are specified in
[invocation state v2](../control-plane/invocation-state-v2.md). Control owns all
durable authority; Gateway and Tool Host never write its database directly.
Host isolation and durable result spooling are requirements, not support claims (implementation: [#230](https://github.com/olo-labs/olo-toolgate/issues/230));
see the [Tool Host guide](../../apps/tool-host/README.md) for implemented boundaries.

| Row | Boundary | Required durability and failure behavior |
|---|---|---|
| 1 | Request accepted | Persist an accepted request ID/digest before acknowledging durable acceptance. If the required spool cannot append, reject before effects. |
| 2 | Reservation and invocation | Commit the business-key reservation, invocation and applicable task state atomically in Control. Resolve an uncertain commit by reading the original key/ID; a concurrent loser observes the winner. |
| 3 | ASK decision | Commit the exact approval decision and corresponding invocation transition atomically. Unknown commit outcomes are read back; unavailable authority never approves execution. |
| 4 | Approval/permit consumption | Bind consumption to the invocation and exact request. Commit single-use consumption before effects; a replay cannot start a second execution. |
| 5 | Permit dispatch | Control issues the permit. Record dispatch metadata within the required audit durability boundary; inability to record required evidence fails closed. |
| 6 | Host admission | Consume at Control, then persist required local admission evidence before starting. A failed acknowledgement never justifies a duplicate effect; retain not-started evidence or fence as OUTCOME_UNKNOWN. |
| 7 | Running work | Renew bounded leases and validate fencing for protected operations. Lost authority or expired lease stops further effects; uncertain ledger outcomes remain fenced. |
| 8 | Result delivery | Persist a local result outbox before reporting to Control. Retry delivery idempotently, never execution. Stale reports become evidence; a valid late report may resolve an unknown outcome only under the specified transition. |
| 9 | Retention | Retain results for the required task/business-key window, then replace payloads with tombstones. Purge is resumable and cannot permit key reuse to duplicate an effect. |
| 10 | Audit shipping | Deliver records at least once with dedupe IDs; truncate acknowledged segments only. Receiver failure retains unacknowledged evidence. |
| 11 | Spool exhaustion | Reserve headroom for terminal evidence, stop new admission before exhaustion and fence unsafe work. Full storage must not silently discard required audit or result evidence. |

The [conformance suite index](../../tool-sdk/conformance/suites.json) and
[case files](../../tool-sdk/conformance/README.md) retain the individual assertions,
fixtures and runner evidence. They are the detailed acceptance record, not a
second implementation schedule.
