# Core MCP packages

`core-packages.build.json` is the build inventory. Initially it selects one
Java SDK package, `hello-world`, with `client_greet` and `server_greet`.
Both accept `text` with default `World` and return `{"message":"Hello World"}`.
They share their greeting implementation and emit actual SDK log/progress
messages. The package defines no alternative executor or installation branch.

To build and verify every configured package:

```text
python core-mcp/build.py --cli /absolute/path/to/toolgate
```

The orchestrator publishes SDK dependencies to `build/sdk-repo`, builds and
checks independent projects, invokes the SDK's package command, verifies the
result with the canonical CLI, then writes packages and
`core-packages.lock.json` under `build/core-mcp`. Lock entries include exact
package/artifact digests. Build outputs never upload a package, create users,
modify grants, install tools or activate a deployment profile.

Add a project directory and an inventory entry to include another package.
The supported adapters are `java-gradle` and `json`. Additional SDK languages
require their shared adapter/runtime implementation before an inventory may
select them. A package does not require an entry in root Gradle settings.
For Java projects, use the versioned SDK plugin and the explicit
`toolgateSdkRepository` Gradle property, as the example does. JSON projects use
the installed distribution built by `:sdk-json:installDist`.

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
