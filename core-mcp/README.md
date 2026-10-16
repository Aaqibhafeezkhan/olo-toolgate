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
