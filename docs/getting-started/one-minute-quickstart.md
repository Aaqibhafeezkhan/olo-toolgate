# Start OLO locally

Use the [published-image Quickstart](../../deploy/docker/quickstart/dev/README.md).
It runs Quickstart, the LangGraph runtime and a persistent Device; local models
and monitoring are optional companions.

From the cloned repository on Windows:

```powershell
cd deploy\docker\quickstart\dev
.\start.bat
```

On Linux/macOS with Python 3.11+:

```sh
cd deploy/docker/quickstart/dev
sh start.sh
```

Docker must use its Linux engine with current Compose support. Select matching
application images. Missing capabilities fail startup rather than using stale
images or building applications implicitly.

Open http://127.0.0.1:18090/console/ and follow [first login](first-login.md).
Import a `.olo` bot, configure its model/device dependencies, prepare and
publish it, then [run it](../help/run-bots.md). Device approval and administrator
login never supply runtime grants.

The deployment guide covers credentials, ports, model/collector options,
stop/restart and data ownership. Its stop command preserves state.
The [source-built installation](../../debug/profiled/README.md) is for application
development. The separate [ToolGate-only evaluation](../../deploy/compose/QuickStart/README.md)
is available when orchestration is not needed.
