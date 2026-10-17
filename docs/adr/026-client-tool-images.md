# ADR-026: Client-tool OCI images built and released by Control

**Status:** Accepted

## Context

ADR 014 decided that devices never receive a `.tgpkg`: on enable, Control converts client-eligible exports into a
digest-pinned OCI image built from an approved base and issues the RS256 release JWS over a `FleetPackageDocument`.
Nothing implemented that conversion, so SDK packages could not run on client devices. The client runs managed tools
only from images already present or pulled by digest, in a sandbox with a read-only root, no network, uid 65532, all
capabilities dropped, its seccomp profile, 64 pids and `noexec` tmpfs `/work` and `/tmp`.

## Decision

1. **Approved base.** Client Java SDK packages run on one approved JRE image, configured by digest:
   `eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c` (Temurin 21.0.12.1, Ubuntu
   Noble; runs the SDK's Java 17 bytecode). Operators mirror that exact image into their registry
   (`TOOLGATE_CONTROL_CLIENT_IMAGES_BASE`, `<repository>@sha256:<digest>`); Control never resolves a tag. The managed
   runtime version is the base's `JAVA_VERSION` (`jdk-21.0.12.1+1` → `21.0.12+1`), which the client checks against
   `java -version` before first use.
2. **Where Control builds.** Control assembles the image itself, as data, on an authorized *client release* request
   (`POST /api/control/v1/packages/client-releases`, permission `package.publish`). It reads the verified upload, copies
   the package's single Java artifact into one layer at `/opt/tool/<packageId>/tool.jar` (root-owned, mode 0444,
   zero timestamps, fixed gzip header), appends that layer's diff ID and a fixed history entry to the base config, clears
   the base entrypoint and command, and writes a manifest per base platform plus an image index. No Dockerfile, build
   daemon, shell or author code runs; identical inputs give identical digests.
3. **Which registry.** One operator-configured OCI Distribution registry and repository
   (`TOOLGATE_CONTROL_CLIENT_IMAGES_REPOSITORY`, e.g. `registry.example.com/toolgate/client-tools`), with optional
   static Basic credentials from a protected file and an optional private CA. Base layers are cross-repository mounted
   from the mirrored base repository on the same registry, or skipped when present; only the new layer, configs,
   manifests and index are uploaded. Control pushes by digest and verifies every write by reading the digest back.
   Devices pull `<repository>@sha256:<index>` through their own engine configuration.
4. **How the release pins it.** Control converts each export a device may run — every ClientTool, and a ServerTool
   only when it needs no routes, destinations, secrets or claims — into a `LocalToolRegistration` with runtime kind
   `JAVA_SDK`, entry point `/opt/tool/<packageId>/tool.jar`, the descriptor's closed schemas and limits, and an
   `InstalledAuthorizationProfile` whose package digest is the client's managed digest of that registration and
   runtime. The `ManagedRuntime` image is the index reference by digest. Each tool's SDK `@Example` becomes its
   self-test. Exports a device cannot run are listed as unsupported, never weakened. Control signs the
   `FleetPackageDocument` with the dedicated package-release key (`TOOLGATE_CONTROL_PACKAGE_RELEASE_PRIVATE_KEY_PATH`,
   separate from the organization fleet key) and records it as a fleet release; without that key it returns the exact
   document bytes for the independent release authority (`tools/builder/sign.py`) and the existing publish endpoint.
   A release grants nothing: a rollout signed by the organization key, enrollment, grants and permits remain required.
5. **Launch.** `JAVA_SDK` tools run as `java -cp <entry> io.ololabs.toolgate.runtime.Main` with
   `TOOLGATE_PROTOCOL=2` and `TOOLGATE_EXECUTION_TARGET=CLIENT` inside the unchanged sandbox. The client sends one v2
   `ToolInput` bound to the invocation and its deadline, reads response frames ending in one `ResultFrame`, and returns
   its structured content after output-schema validation. SDK diagnostics on stderr are bounded and discarded, never
   reflected. Online authorization and permits are unchanged.

## Alternatives Considered

- Ship `.tgpkg` to devices or bind-mount jars: rejected by ADR 014 (second trust format, extraction on devices).
- Build with a container daemon or BuildKit inside Control: requires a privileged builder next to tenant state.
- A registry embedded in Control: devices would need per-engine trust for Control's CA; operators already run registries.

## Security Impact

The image contains only the verified artifact on an approved base pinned by digest. Building parses no author code.
Registry credentials are read from a protected file and never logged. Release signing uses a key separate from the
organization key; a compromised registry cannot change pinned digests. The device sandbox is unchanged.

## Operational Impact

Operators mirror the approved base, configure the registry and, for Control-signed releases, the package-release key.
Client releases fail explicitly when the registry, base or key is unavailable.

## Compatibility Impact

Fresh installations only. `LocalRuntimeKind` gains `JAVA_SDK`; there is no conversion of earlier releases.

## Consequences

Positive: SDK packages, including ClientTool Hello World, run on devices through the existing signed fleet path.
Negative: one more operational dependency (a registry) and one approved base to patch by publishing a new digest.

## Validation

- Deterministic builder tests: identical inputs give identical layer, config, manifest and index digests.
- Release tests against an in-memory OCI registry: blobs, mounts and manifests by digest; the document passes
  `FleetService.validatePackage` and `InstalledProfiles.validate`; the signature verifies with the release key.
- Client tests: `JAVA_SDK` command construction and v2 frame parsing; a real run of the Hello World jar on the approved
  base under the client's sandbox flags and seccomp profile returns `Hello World`.
