# ADR 005: Transactional signed policy publication and verified snapshots

> Historical decision. Runtime authority and permit-signing details are superseded
> by [Control online authorization](../architecture/overview.md) and
> [ADR 013](013-tool-host-egress-broker-secrets.md). This is not a current deployment guide.

Status: accepted for Module 04. Date: 2026-10-02.

Control owns policy compilation and immutable publication. Gateway owns runtime
verification and evaluation. Canonical bundle types live in the shared contract
package; neither component reaches into the other's implementation.

Use RFC 7515 JWS compact serialization with RS256, Java's JCA signer and ring's
RSA PKCS#1 SHA-256 verifier. The protected header contains exactly `alg`, `typ`
(`toolgate-policy-bundle+jws`) and `kid`. Only externally configured, dedicated
bundle keys are trusted. Key URLs, embedded keys and algorithm negotiation are
unsupported. RSA keys are 2048, 3072 or 4096 bits. Sign the exact UTF-8 payload bytes;
verification does not reserialize JSON. The signed payload includes a SHA-256
digest of the separately base64url encoded compiled policy bytes. Both layers
reject duplicate keys and unknown fields. All base64url encodings are unpadded.
Sources: [JWS standard](https://www.rfc-editor.org/rfc/rfc7515),
[ring signature API](https://docs.rs/ring/latest/ring/signature/index.html).

The compiler sorts enabled policies, expands enabled team memberships, combines
user/team selection by union, and intersects user, agent and device dimensions.
Empty selection in a dimension means unrestricted within the signed tenant.
Disabled policies contribute no rules. Disabled identities never expand into
an ALLOW. Exact resource/tool/action matching and BLOCK precedence are retained.
Compilation is bounded and rejects unsupported semantics. Directory changes
take effect in Gateway after an explicit publication, not immediately.

A per-tenant PostgreSQL transaction lock serializes publication against directory
mutations and concurrent publishers. Immutable history, current sequence,
administrative audit and idempotency response commit together. Publication uses
an expected directory revision and current bundle sequence. Sequence increases
even when rolling back: rollback copies a previously published policy into a new
signed envelope with fresh expiry and `rollbackOf`. Historical bytes never change.

Gateway polls one administrator-configured destination with TLS verification,
no redirect or ambient proxy, bounded body and deadlines. Authentication is an
externally refreshed short-lived Control JWT file. Claims bind issuer, audience,
tenant, format, sequence, issuance, lifetime and grace. Unknown keys and stale or
equivocating versions are rejected. The same byte-identical sequence is a no-op.
Verification and policy construction happen before a short write lock swaps one
immutable snapshot. Authorization holds one snapshot for its entire evaluation;
no database or network enters that path.

The lifecycle is EMPTY → FRESH → GRACE → EXPIRED, with successful verified updates
returning to FRESH. Rejected updates preserve last-known-good, never extend its
deadline, and cannot revive expired state. A signed grace of at most five minutes
is capped again by local configuration; zero is the safe default. Readiness is
false after that deadline and all actions BLOCK. Freshness uses a wall-clock
high-water mark and independent monotonic deadlines; clock regression cannot
extend validity or revive expired policy. Older concurrent ingress timestamps
do not cause spurious policy outages.

Grace applies only to policies explicitly identified in the publish request's
`gracePolicyIds`. This is the authenticated administrator's assertion that the
operation is a low-risk read; operation names do not establish risk. The compiler
signs `graceAllowed` per rule. Unknown, disabled or BLOCK policy selections reject
publication. Every other ALLOW rule blocks at expiry, including writes,
destructive and privileged actions. A post-audit reevaluation closes the race
between an ALLOW evaluation and expiry/replacement before its response.

Gateway containers remain stateless. Restart requires a fresh verified current
bundle from Control and cannot use expired local disk state. Administrators can
set a minimum accepted sequence during restore/incident response; anti-replay
high-water state is per process, while authoritative publication history is in
PostgreSQL. Rollback of a database backup requires explicit version-floor handling.

Rotation deploys old+new public keys first, then switches Control's signing key
and republishes, then removes the old key after every replica has adopted a new
bundle. Configuration replacement/restart re-verifies the current bundle; it
never trusts a removed key's cached state. Emergency compromise handling rotates
keys, raises the minimum sequence and publishes deny rules with zero grace.

Static administrator policy remains available as Module 01's explicit standalone
mode. Signed mode and static mode are mutually exclusive; signed mode never falls
back to static policy. Bundle signatures authorize policy configuration only;
they are neither deployment assignments nor execution permits. ASK remains
unsupported until Module 05 and cannot be compiled as an ALLOW.
