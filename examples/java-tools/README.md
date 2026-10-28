<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Example tools for the Java Tool SDK

Twelve small, working tool packages for everyday operations and enterprise work, built with the ToolGate Java Tool SDK. Read them to see how a real tool is put together, run them as they are, or copy one as the start of your own.

Every example is a complete package: one `@ToolPackage`, its tools, unit tests with `ToolHarness`, `@Example` self-tests, and a `.tgpkg` whose `artifacts/tool.jar` is one self-contained fat jar. Each runs as a plain MCP server (standalone) or under ToolGate (governed) without changes. New to the SDK? Start with the [Tool SDK guide](../../TOOL-SDK-GUIDE.md).

| Example | Tools (MCP name) | What it does | Shows |
|---|---|---|---|
| [script-runner](script-runner/README.md) | `scripts.run`, `scripts.list` | Runs an allowlisted `.bat`, `.cmd`, `.ps1` or `.sh` script and returns its exit code and last N lines of console output | Running local programs safely: allowlisted directory, argv without a shell, deadline, process-tree kill |
| [file-download](file-download/README.md) | `downloads.fetch` | Downloads a file from the approved file server and returns its local path, `file:` URL and shared URL | HTTP routes with path patterns, binary responses, atomic writes |
| [system-health](system-health/README.md) | `health.system` | CPU, memory, swap and disk health rated OK, WARN or CRITICAL | Thresholds as globals an administrator sets |
| [cpu-processes](cpu-processes/README.md) | `processes.cpu`, `processes.top`, `processes.find` | CPU usage, busiest processes, and "is X running?" | Read-only tools; never exposing command-line arguments |
| [log-insights](log-insights/README.md) | `logs.search`, `logs.levels`, `logs.errors`, `logs.tail` | Logs by time window, counts by level (and over time), errors grouped by cause, tail of a file | Bounded scans of large files, multi-line entries, time zones |
| [disk-usage](disk-usage/README.md) | `disk.space`, `disk.largest` | Free space per volume; largest files and folders in an allowed directory | Allowlisted roots, visit caps, no link following |
| [endpoint-check](endpoint-check/README.md) | `endpoints.check` | Is a service up? HTTP status, latency, self-reported status | Treating upstream failures as a result, recorded exchanges in tests |
| [csv-query](csv-query/README.md) | `csv.query`, `csv.files` | Filter, sort, group and total CSV exports | Structured inputs (lists) and tabular output |
| [webhook-notify](webhook-notify/README.md) | `notify.send` | Sends an alert to a JSON webhook, at most once per reference | Route auth with a secret, business keys, `Idempotency-Key` |
| [file-cleanup](file-cleanup/README.md) | `cleanup.old-files` | Lists, and when asked deletes, files older than N days | A destructive tool done carefully: off until configured, dry run by default, caps |
| [db-query](db-query/README.md) | `db.tables`, `db.row`, `db.query` | Lists tables and runs read-only, parameterised queries on a database (an embedded H2 file) | Read-only enforced by connection, account and statement checks; a third-party driver in the fat jar |
| [hotfolder](hotfolder/README.md) | `hotfolder.list`, `hotfolder.read_text`, `hotfolder.write_text`, `hotfolder.move`, `hotfolder.copy` and five more | Reads and writes text files in a protected folder on the selected device | A `@ClientTool` package using `@DeviceFileAccess` and the device's confined files area; no device-side code for the package |

For fixed, fictional data in business scenarios (loan approval, a clinic, a server crash, support, HR, fraud, insurance claims), see the [mocked tool examples](../mocked-tools/README.md).

## Safe by default

Tools are called by models, so every example treats its arguments as untrusted.

- **Files** are read or written only inside directories the administrator configures (`ScriptsDir`, `LogDir`, `DataDir`, `DownloadDir`, `AllowedRoots`, `CleanupRoots`). Names are validated, `..` is refused, and links are resolved so nothing escapes the directory.
- **Programs** run only if they are scripts in `ScriptsDir` (and in `AllowedScripts` when set). Arguments are separate argv entries limited to characters no shell treats as syntax, the environment is minimal, and a run past its deadline has its whole process tree killed.
- **Network** access is only through declared routes to a host the administrator sets (`DownloadHost`, `ServiceHost`, `WebhookHost`). No tool accepts a URL. Under ToolGate the broker makes these calls and adds credentials; the code never sees the API key.
- **Destructive** work is declared (`destructive = true`), off until configured, a dry run by default, and capped per call.
- **Output** is bounded: line counts, entry counts, bytes scanned and files visited all have limits, and results say when they were cut short.

## Build and test

You need Java 17 or later. Until the SDK is on Maven Central, publish it from this repository once, and build the `toolgate` CLI:

```sh
./gradlew publishJavaSdkToMavenLocal          # at the repository root
cargo install --path tool-sdk/cli --locked
```

Then, here:

```sh
./gradlew check                     # every example: compile, unit tests and @Example self-tests
./gradlew toolgatePackage           # every example: build/toolgate/<example>-0.1.0.tgpkg
```

Or work in one example directory with the CLI, exactly as in a project made by `toolgate init`:

```sh
cd log-insights
cp toolgate.dev.example.json toolgate.dev.json    # local settings; never commit toolgate.dev.json
toolgate dev        # serve it over stdio to an MCP client
toolgate check
toolgate pack
```

Each example's `gradlew` runs the shared build in this directory, so the CLI works there without a Gradle install. To build against another SDK repository, such as `build/sdk-repo` from `./gradlew publishJavaSdkLocally`, pass `-PtoolgateSdkRepo=<path or URL>`. The SDK plugin version comes from the repository's generated `VERSION` file; outside this repository, set `-PtoolgateSdkVersion=<installed SDK version>` explicitly.

## Run one

**From an MCP client** (Claude Desktop, an IDE), standalone. Give each package's fat jar its settings as environment variables, upper-cased:

```json
{
  "mcpServers": {
    "logs": {
      "command": "java",
      "args": ["-jar", "C:\\tools\\log-insights\\tool.jar"],
      "env": { "TOOLGATE_GLOBAL_LOGDIR": "C:\\app\\logs", "TOOLGATE_GLOBAL_LOGTIMEZONE": "Europe/Berlin" }
    }
  }
}
```

The jar is `artifacts/tool.jar` inside the `.tgpkg` (a zip). Destinations are `TOOLGATE_DESTINATION_<HOSTSETTING>` and secrets `TOOLGATE_SECRET_<NAME>`.

**Under ToolGate**, upload the `.tgpkg` in *Server packages*, deploy it with the settings filled in, then enable, allow and grant the tools ([guide, section 13](../../TOOL-SDK-GUIDE.md#13-run-it-under-toolgate)). Paths in settings must be absolute: a Tool Host starts each call in its own working directory with an empty environment. Use *Tool Hosts* on the deployment to run, say, `system-health` on every server, and `script-runner` only on the machine that holds the scripts.

## Copying an example

Each directory stands alone apart from the plugin version, group and version in [build.gradle.kts](build.gradle.kts) and the repositories in [settings.gradle.kts](settings.gradle.kts). To start your own project from one, copy the directory, add a `settings.gradle.kts` with those repositories, put `version "<sdk version>"` on the plugin and set `group` and `version` in its `build.gradle.kts`, replace its short `gradlew` and `gradlew.bat` with the ones here (and the `gradle/` directory), and change the `@ToolPackage` id. Keep `descriptorGeneration` pinned.

The few helpers that several examples need (checking a path is inside an allowed directory, for instance) are repeated in each on purpose, so every example reads top to bottom on its own.
