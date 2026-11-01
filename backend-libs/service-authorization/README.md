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
