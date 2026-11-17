<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 071: Buildable profiles and embedded tool packages

Status: accepted. Extends ADR 070 with a fresh bundle format; no compatibility reader.

The repository-level `profiles/` directory owns editable configuration folders,
portable build entry points and ignored `bin/` outputs. A profile build produces
one versioned `.olo` per workflow, a version/source/package inventory and checksums.
Each archive carries the exact required `.olo` bytes, including trigger packages.
Release jobs retain tested profile outputs in foundation evidence and publish
those same bytes alongside other versioned release assets.

The canonical bundle manifest records package identity/version, package digest,
archive SHA-256 and byte size. The bundle's checksums establish integrity, not
publisher trust or execution permission. Source configuration declares package
build inputs; local scripts execute only repository-controlled build recipes.
No credentials, database, container images or model weights enter a bundle.

Console export resolves referenced tool descriptor digests, trigger exports and
profile pins against the tenant's verified package inventory. Missing/ambiguous
artifacts fail export. A management download operation checks current package.read
authority inside the tenant transaction; device download authorization is unchanged.
Import verifies the archive inventory and hashes before staging. An explicit
package-import action sends embedded archives through the existing bounded package
upload/verification API. Import is not deployment: it grants no tool execution,
creates no device, changes no human account and restarts no container. Existing
review, activation, device provisioning and effect authorization still apply.

Native Python graph code remains operator-installed; embedding tool packages does
not introduce a graph-code upload path or a tenant-code sandbox.
