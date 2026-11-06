<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 056: Per-effect keys and qualified resend

**Status:** Accepted and delivered as bounded S20; full phase 3 remains partial.

## Decision

A logical effect is identified by a domain-separated hash of tenant, logical
operation, approved route and a bounded stable effect key. Attempts and owners
are excluded from identity; complete immutable request facts remain bound
separately. A changed request for an existing identity is a conflict. Every
logical write requires an explicit key; missing write keys are refused.
Literal default/service-write names are ordinary distinct keys. No historical
identity aliases, conversion or backward acceptance paths are supported.

Automatic resend is a narrow exception requiring a current independently
reviewed target guarantee. Its reference, key-retention period, deduplication
scope, different-body behavior and review expiry are explicit; no default
retention is supplied. The effective window subtracts the greater of ten percent
and one hour, measured from the first authorization across all generations.
Missing, expired, conflicting, incomplete or out-of-window facts cannot authorize
resend. A new generation/attempt must recheck current policy, exact consent,
epoch, profile, original deadline and complete effect history.

The broker owns downstream key injection. Tools cannot substitute a target key
or obtain authority by selecting a different effect identity. Consent counts
distinct identities, preserving the S19 cap across generations. Reconciliation
is read-only and contributes witnessed outcome evidence; a lookup failure,
unknown response or late executor observation cannot grant new write authority.
Terminal/conflicting target evidence prevents resend and retains reconciliation.

## Delivery and current formats

Implement small focused changes and tests, then the full stage gates before one
code/tests/docs/progress commit and push. Canonical contracts precede generated
bindings. Both SQL dialects, concurrent owners, history windows, lost responses,
downstream keys, consent exhaustion, current-authority changes, adapters and
fresh initialization/preflight/operator presentation require executed evidence. Amend ADR
016 and invocation-state documentation when the behavior is wired. Existing
packages without an approved guarantee remain status-lookup/manual; no implicit
deduplication promise restores retry. This decision supplies no current runtime
authority and does not complete phase 3.

OLO services use the same explicit current effect-key domain as generic routes.
Distinct keys get distinct identities/nonces. The supervisor retains the issued
key with the handler/digest and refuses substitution before transport. Generic lost-issue
reply replay retains the original nonce/generation/expiry; positively RELEASED
replacement retains the immutable first intent. Neither supplies authority to
resend an ambiguous effect.

## Reviewed target contract and stable broker key

The immutable route profile carries the target contract. A matching tool
configuration must pass the existing maker/checker workflow, with at least one
independent review even when ordinary configuration reviews are disabled. The
Super Admin direct/self-review exception does not qualify this guarantee.
Authority requires the exact currently applied tool record, independent from
both requester and route-profile reviewer, and the reviewer's current scoped
configuration/tool permissions. Direct edits, imports, record changes, profile
replacement, review expiry or loss of reviewer authority cannot reuse approval.

Account/API-key scopes additionally pin the credential version. Global scope
does not assume a credential-scoped window. Keys are broker-generated with a
domain-separated HMAC from the dedicated effect-key boundary and persisted with
the first effect binding, so signing-key rotation cannot change an existing
target key. Raw key material remains outside workers. The fixed profile binds
the target header name; the derived value is excluded from request fingerprints
and is never accepted from the tool. A declaration alone may prepare a stable
key for the first send; only independently reviewed live guarantees can
authorize any ambiguous resend.

The tool-directory mutation boundary gains one narrow operator metadata exception:
targetDedupeGuarantees may change through configuration review while every other
packaged definition, action, extractor, artifact and package identity remains
unchanged. This does not allow installing/editing executable tools through the
directory API. Runtime approval additionally requires the exact applied record;
direct/imported metadata cannot manufacture review authority. New target-contract
profile stamps retain the verified maker user ID, rather than a credential
fingerprint, so changing credentials cannot bypass reviewer independence.

## Current-only fresh breaking build

The user's fresh-build constraint applies across S01–S20 and future feature
implementation. Ship one fresh initial schema per dialect, current contracts
and explicit current effect identities. There are no legacy aliases,
compatibility constructors, upgrade chains, backfills, conversion paths or
acceptance fallbacks. Old databases/formats are rejected without modification.
All affected first-party callers use current contracts. Runtime crash recovery
within the current schema remains essential. The authoritative progress document
records the unchanged denominator and executed S20 qualification.

## Read-only target lookup boundary

A reviewed target guarantee may name a statusRouteId in the same immutable
service profile. The route must be a bounded non-streaming GET on the same
approved HTTPS destination and credential declaration as the write, with one
constrained {effectKey:tg-[a-f0-9]+} path slot and no query or tool-selected headers. A target
implements the closed current TargetEffectStatusReply contract; terminal
responses must match the stored key and exact body digest and carry a stable
business receipt and result digest. Unknown/pending responses grant no authority.

Control snapshots current scoped operator authority, profile, independent
review, credential version and ambiguous effect generations before network I/O,
then rechecks them transactionally before recording immutable witnessed evidence.
No database writer is held while contacting a target. The HTTPS adapter pins
checked DNS addresses, verifies hostname/CA and declared verified-leaf pins,
disables proxy/redirect/retry behavior and bounds concurrency, DNS, total time,
headers and body. Unsupported auth/route/status shapes remain manual. Target
lookups never mint a generation, renew a deadline or dispatch a business write.

Lookup receipts use the existing append-only tenant effect-evidence journal under
a dedicated Control-only TARGET_LOOKUP source. Its receipt hash binds the pinned
profile, key, body digest, documented scope and provider business receipt. Raw
provider receipts and credentials are not retained. Lookup does not fabricate a
target claim token or rewrite an old AMBIGUOUS generation. The complete bounded
finalization projection consumes positive lookup witnesses, detects conflicting
receipts and fences further resend. Fresh initial-schema settlement guards must
recognize that witnessed closure before automatic attempt settlement.

Broker-only API-key references are explicitly reachable HTTP auth metadata, not
worker variable declarations. Java/Python authoring no longer requires creating
a worker Secret just to name an auth vault reference. Control includes only
reachable auth references in admitted resources; private authority keeps them
scoped to the broker, and workers receive no handle. Admission qualification now
covers reviewed generic non-streaming HTTP and auth-only API-key routes while
continuing to refuse raw worker secret declarations, other auth modes and TCP.
