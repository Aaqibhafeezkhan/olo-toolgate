# Foundation compatibility and upgrades

The initial contract release is `0.1.0-dev`, wire schema major `v1`, Java 21,
Rust 1.94.1, TypeScript 5.9.3 and PHP 8.2+. No runtime component or prior stable
release is claimed compatible. The release bundle includes `compatibility.json`.

Contract versions use SemVer. Breaking accepted inputs, wire shapes or security
meaning requires a major contract version and compatibility review. Compatible
new independent models may use minor versions. Metadata/generator corrections
that preserve behavior may use patch versions. Pre-1.0 breaking changes require
an explicit minor version and upgrade notes; do not disguise them as patches.

Unknown fields are rejected by security object schemas. An optional new field
may still break an old closed reader; negotiate a new wire revision or capability
and maintain both revisions during the support window. Frozen fixtures must
never be rewritten simply to make a breaking change pass. New model fixtures
may be added separately. Signing formats, verification, policy lifecycles and
actual package reconciliation are implemented by their later owning modules.

Adding an enum value also changes the wire contract for an existing closed
reader. The compatibility gate rejects additions, removals and replacement of
an unrestricted string with an enum. Introduce such changes through an explicitly
reviewed wire revision rather than treating them as ordinary additions.

Release versions support SemVer core and prerelease identifiers without build
metadata, keeping Maven/npm/Cargo/Helm release tags aligned. Purely numeric
prerelease identifiers cannot have leading zeroes (`rc.1` is valid; `rc.01` is
not). Invalid `--set` input is rejected before any version source is written.
This strengthens release metadata validation; the frozen v1 wire schemas retain
their existing acceptance rules.

There is no database migration, running workload, HA behavior or cluster upgrade
to test in this foundation. The Helm chart has no rendered resources and its
default and upgrade renders must both remain empty. Chart `version` tracks the
product version and `appVersion` names that product version; contract versions
are independently recorded in the compatibility matrix. No fake deployment is
created to make chart installation appear functional.

The owner-controlled device power switch adds optional `ClientReport.power` with
the closed values `ON` and `OFF`. This is an explicit reader incompatibility with
the archived foundation report, whose schema rejects extra properties. Upgrade
Control to the matching power-report implementation before upgrading clients;
current Control accepts archived reports without `power` and treats them as on.
Rollback requires older clients before older Control. The frozen schema and
corpus remain unchanged. The compatibility tests cover this exact extension
separately and retain strict comparison for every other original report field.

Release artifacts are immutable. Fix a published defect with a new version.
Rollback of a foundation library means selecting the previous compatible package
version; there is no runtime rollout procedure yet. Stable N/N-1 runtime support
will be defined and tested before a stable product release.
