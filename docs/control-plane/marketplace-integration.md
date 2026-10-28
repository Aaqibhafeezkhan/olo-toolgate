# Marketplace integration

Control can read one OLO Orgs Marketplace and import its packages as uploaded server packages. The Marketplace
validates and signs releases; Control checks them again before it stores anything, and an imported package then
follows the same review, deploy, enable and grant steps as an upload. The Marketplace side is described in
[olo-marketplace docs/design.md](https://github.com/olo-labs/olo-marketplace/blob/main/docs/design.md) and its API in
[the ToolGate-owned Marketplace API contract](../api/marketplace-api.md).

## Settings

All are environment-owned, so a configuration import cannot change them. The Marketplace is off unless enabled.

| Environment variable | Meaning |
|---|---|
| `TOOLGATE_CONTROL_MARKETPLACE_ENABLED` | `true` turns the Marketplace view and import on; default `false` |
| `TOOLGATE_CONTROL_MARKETPLACE_ID` | The `marketplace` a release record must name, for example `olo-labs-marketplace` |
| `TOOLGATE_CONTROL_MARKETPLACE_BASE_URL` | The only origin Control fetches from; `https://` with no path, query or credentials |
| `TOOLGATE_CONTROL_MARKETPLACE_KEYS` | Comma-separated `keyId:base64PublicKey` pairs of raw 32-byte Ed25519 keys, from the Marketplace's key file |
| `TOOLGATE_CONTROL_MARKETPLACE_CA_PATH` | Optional PEM bundle of 1 to 4 CA certificates trusted for the origin instead of the JVM trust store |

At startup Control logs `marketplace_key marketplace=… key=… fingerprint=sha256:…` for each pinned key; compare the fingerprints
with the Marketplace's About page before relying on them. A malformed setting stops Control from starting. The Helm
chart sets these from `control.marketplace` (`caSecret` names a Secret with `ca.pem`, and
`control.networkPolicy.marketplaceTo` opens egress on TCP 443); Quickstart passes them through from its compose file.

## Import checks

`POST /api/control/v1/marketplace/imports` with `{"packageId", "version"}` is Super Admin only, checked before any
fetch. Control builds every path itself from the validated ids, never follows a redirect, uses no proxy, and caps each
response (2 MiB for JSON, 64 KiB for the release record, the upload limit for the package) with a 5 second connect
and 60 second request timeout.
It then refuses the import, with 422 and the step in its log, unless:

1. the version is not delisted;
2. the release record has the schema `toolgate.marketplace.release/1`;
3. it is signed by a pinned key (Ed25519 over the RFC 8785 form without `signature`);
4. it names this Marketplace id;
5. its package id and version are the ones asked for;
6. its publisher is the detail record's publisher, and the package id starts with the publisher's slug;
7. the archive's SHA-256 is the signed `archiveSha256`;
8. the archive passes the package rules (the shared `backend-libs/package-archive`) and its package and descriptor set
   digests are the signed ones.

The package is then stored through the existing upload, which also refuses a package this ToolGate can't run (below).
The audit log records `PACKAGE_MARKETPLACE_IMPORT` with the Marketplace, package, version, publisher and key id next
to the usual `PACKAGE_UPLOAD`. Importing a stored version again answers it unchanged. Nothing is deployed, enabled or
granted.

## Compatibility

Upload and import refuse, with 422, a package whose manifest declares a contracts major other than this Control's, a
descriptor generation Control does not accept, a tool protocol range that does not overlap the Tool Host's, or a
runtime no Tool Host provides. Deploy also refuses a named Tool Host whose registered version is older than the
package's `minimumToolHostVersion` or that speaks no common protocol. The Marketplace view applies the same rules to
mark each version compatible or not, and installs the newest compatible one.

## Console

**Server packages** shows a Marketplace section when it is enabled: catalog cards, a version's description, tools,
required settings, links and digests, and its publisher. Images load through
`GET /api/control/v1/marketplace/media/{mediaId}`, which passes through only PNG or JPEG up to 1 MiB from the configured
origin, so the console keeps `img-src 'self'`. Install asks for confirmation with the text of design §8.1.
