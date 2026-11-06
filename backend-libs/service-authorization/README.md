# ToolGate service authorization library

`io.github.olo-labs:toolgate-service-authorization` verifies Control-issued exact-request permits at the downstream
service boundary. Publish with the repository's `publishJavaSdkLocally` task. Requires Java 21, the shared ToolGate
contracts, a verified mTLS listener, and authenticated Control access.

See [the installation and transaction contract](../../docs/control-plane/olo-service-permits.md). Use
`ControlServiceAuthorization` for bounded authenticated key/epoch refresh and online write consumption. Pass actual
request bytes and endpoint resources with the accepted TLS session. Check service ownership/ACLs in storage as well,
and enforce the returned authorization deadline immediately before transaction commit or result release.

No method accepts identity, tenant, operation or body claims from an executor as authority. A consumed or ambiguous
write is reconciled, never automatically retried under a new permit.

New broker permits also bind the prepared HTTP request. For those permits pass
`ServicePermitVerifier.Transport` containing trusted profile/route configuration
and actual accepted URI, method and repeated headers to the five-argument
`Request` constructor. Missing transport facts fail closed. Upgrade target
integrations before deploying the updated broker; see the installation guide.

The SQL target-claim foundation is available as JdbcEffectClaims. Every replica
must share the store and use caller-owned transactions. The actual OLO adapters now integrate it
with verified writes and claim-token online consumption. See the [transaction and recovery guide](../../docs/operations/target-effect-claims.md).


For writes using shared fencing, call ControlServiceAuthorization.verifyWrite
with accepted TLS/HTTP facts. Its VerifiedWrite exposes immutable claim binding
but cannot authorize a commit. Commit the unique local claim, then call
consumeClaim with that owner and win beginApply before acting. The original
deadline includes claim/Control round trips and is never renewed by replay.

JdbcEffectClaims.finish atomically records terminal state and a pending outcome
in the caller's business transaction. Deliver pending outcomes through
reportOutcome and acknowledge only accepted evidence. Use keyset pagination so
deferred records do not starve later delivery. Expired CLAIMED work may be
abandoned by CAS; APPLYING is never automatically declared not-started.
Fresh initialization installs the current claim schema and its identity marker.
Older stores are rejected without conversion. Every claim requires a positive
authorization expiry. Direct authorization handles reads; writes require the
verified-write and durable claim workflow. Owner and outcome string representations redact tokens. Credential suppliers used by
the new concurrent reporting APIs must support concurrent reads.
