# ADR 011: SDK package authoring and isolated builds

Status: accepted, updated 2026-10-16 for the first supported package contract.

Builder and the standalone JSON SDK share one compiler/stager/packager. Their
authoring input is a canonical JSON SDK project. The SDK lowers a closed typed
operation set to Java SDK source and uses the existing annotation processor,
example checks, runtime staging and canonical Rust package CLI. Executable
behavior is installed only as a verified package.

Builder exports exactly two artifacts: the standard `.tgpkg` and one canonical
source-project ZIP. Identical source and pinned SDK/toolchain produce identical
package bytes. Generated Java, descriptors and manifests are build artifacts,
not a second authoring/export format.

Compilation jobs pin a saved source revision, digest, creator and Tool Group.
Claims and completion are transactional and fenced; compilation runs outside
the database transaction and web process. Current management authority is
checked at submission, claim, completion and download. Worker errors expose safe
codes rather than raw source or diagnostics. Package versions are immutable.

The initial process adapter accepts only the closed JSON SDK language. It has a
fixed deployment-supplied JDK/SDK/CLI, a clean environment, a private per-job
workspace and bounded resources/time. Future arbitrary-source SDKs require a
reviewed build isolation adapter. Requests cannot inject executable paths or
toolchain options.

Installation and testing reuse normal package governance and invocation permits.
Builder build authority never grants runtime access. Runtime response streams
are distinct from compilation state. See the
[Builder guide](../client/tool-builder.md) for settings, artifacts and checks.
