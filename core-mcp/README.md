# Core MCP packages

`core-packages.build.json` is the build inventory. It selects three packages, one
per SDK adapter:

- `hello-world` (Java SDK), `ololabs.hello-world`: `client_greet` and
  `server_greet` accept `text` with default `World` and return
  `{"message":"Hello World"}`. They share their greeting implementation and emit
  actual SDK log/progress messages.
- `hello-world-python` (Python SDK), `ololabs.hello-world-python`: the same
  greeting contract, defaults, logs and progress as Java. Gateway callers change
  only the tool name; the Quickstart and managed-device gates exercise both.
- `echo` (JSON SDK), `ololabs.echo`: `echo` returns its `text` input unchanged
  (default `ping`), a connectivity check for agents and Tool Hosts.

Neither defines an alternative executor or installation branch.

To build and verify every configured package:

```text
python core-mcp/build.py --cli /absolute/path/to/toolgate
```

Manual development packaging may add `--without-tests` to omit Java `check`;
this retains CLI/package integrity validation but is not test qualification.
Development and production releases publish the exact configured archives as
standalone `.olotool` assets with the [tool release inventory](../tools/release/README.md#standalone-tool-and-trigger-release-assets).

The orchestrator publishes SDK dependencies to `build/sdk-repo`, builds and
checks independent projects, invokes the SDK's package command, verifies the
result with the canonical CLI, then writes packages and
`core-packages.lock.json` under `build/core-mcp`. Lock entries include exact
package/artifact digests. Build outputs never upload a package, create users,
modify grants, install tools or activate a deployment profile.

Java SDK preparation and Java package builds use the shared bounded
[Maven Central resolution recovery](../docs/development/gradle.md#maven-central-resolution-recovery).
Attempt logs live beside the output directory in `<output-name>-gradle-logs/`,
separate from published archives and runtime-image package content.

CI restores downloaded Gradle dependency files and resolution metadata into
`.dev/core-mcp-gradle` before building. GitHub Actions retains this cache between
otherwise fresh runners. Keys include OS, architecture, Java major, build scripts,
dependency locks, version catalog and Gradle wrapper pins, rather than the release
build number. A changed key can reuse existing downloads while Gradle resolves
the current pinned dependencies. The cache is saved only after the package,
SDK-worker and profile checks and artifact uploads succeed.

The cache excludes build outputs, the locally published SDK, Gradle init scripts
and credential configuration files such as `gradle.properties`. SDK publication
and every build/test gate still execute. Cache
misses, eviction and new dependencies still need network access; a warm cache
reduces repository requests but does not guarantee an entirely offline build.

Add a project directory and an inventory entry to include another package.
The supported adapters are `java-gradle`, `json` and `python`. Additional SDK languages
require their shared adapter/runtime implementation before an inventory may
select them. A package does not require an entry in root Gradle settings.
For Java projects, use the versioned SDK plugin and the explicit
`toolgateSdkRepository` Gradle property, as the example does. JSON projects use
the installed distribution built by `:sdk-json:installDist` and build from a
copy under `build/core-mcp/projects` whose `sdkVersion` is the pinned SDK
version of the build, as Java projects receive `-PtoolgateSdkVersion`.
Python projects use the shared CLI with the SDK from this checkout and run their
examples and tests before packing. The build requires Python 3.10+ (and `tomli`
when building with Python 3.10).

Deployment provisioning and Marketplace publication consume these sealed
artifacts separately. The inventory is not a deployment profile.

The Control image runs this same build and copies only its sealed output into
`/opt/toolgate/core-mcp`. At deployment,
`TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH` selects that immutable lock. Quickstart
selects it for its configured tenant; Helm selects it when enrollment is enabled.
Other Control deployments explicitly select the lock and configure the tenant
with `TOOLGATE_CONTROL_ENDPOINT_TENANT_ID`. Control
verifies every artifact and identity before uploading through the ordinary
package service using the current installation Super Admin. Repeating startup
reuses the same digest; invalid or missing artifacts fail startup. The upload
does not enable tools, assign targets or create grants. Those operations belong
to the deployment profile, not to the build inventory or SDK compiler.
