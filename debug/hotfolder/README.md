# HotFolder on a Docker bind mount

Run from a Linux filesystem in Linux or WSL. The selected Docker engine must be
able to bind-mount that filesystem with its Linux UID and mode bits intact. On
Docker Desktop, enable the distribution's WSL integration before selecting its
engine. The independent WSL Docker engine also works. Windows file sharing that
does not preserve owner-only permissions is unsuitable for this example.

This is the existing signed `examples.inbox-watch` trigger and LangGraph example,
run under its own `toolgate-hotfolder` Compose project. It uses the source-built
endpoint and the existing `olo-toolgate-quickstart:debug` image. The console is
at [port 18092](http://127.0.0.1:18092/console/), TLS at 18452, and the local
package registry at 18553. The standard debug stack on 18090 is separate.

On Windows, run `start.bat` and `stop.bat` in this directory. They use the
default WSL distribution, which needs access to this checkout and the Linux
Docker engine. `start.bat --prepared` reuses the lab's prepared packages.
The Windows launcher creates the Python environment if needed and uses the
lab's local Docker CLI configuration when present.

From a Linux or WSL shell at the repository root:

```sh
python3 -m venv .dev/debug/hotfolder/venv
. .dev/debug/hotfolder/venv/bin/activate
pip install -r examples/langchain/requirements.txt
python debug/hotfolder/bringup.py start
python debug/hotfolder/bringup.py verify
python debug/hotfolder/bringup.py listen
```

If the selected engine does not have the current debug Quickstart image, build
it with `python tools/quickstart/check.py --build --build-only --image
olo-toolgate-quickstart:debug` in an environment with `tools/requirements.txt`.
`start` prepares the signed packages and registry, creates the protected files
area, starts the stack, and provisions the isolated local fixture. It approves
only its fingerprint-checked Compose device for 24 hours and uses the example's
independent reviewer identities. Private console and agent credentials stay in
`.dev/debug/hotfolder/state/`; the console login is the `admin` entry in that
directory's `console-passwords.json`. The device uses the existing local-only
evaluation Docker executor; only this trusted debug device gets the engine
socket. Tool runtimes have no host-folder or Docker socket mount.

The [local LangChain example](langchain/README.md) provides a runner for the
mounted inbox and the live trigger smoke test.

The host directory **`.dev/debug/hotfolder/files`** is bound directly at
`/var/lib/olo-toolgate/hotfolder` in the device container. The trigger watches
its `inbox/` for new `.txt` files. Direct host writers must create root-owned
mode 0600 files; the directories must remain root-owned mode 0700. For a
synthetic input without `sudo`, run from a second terminal:

```sh
python3 debug/hotfolder/bringup.py drop order-1042.txt '2 x widget'
```

`drop` writes through the host bind mount in a short-lived, network-isolated
helper, separate from the device. It refuses an existing filename. `verify`
uses that same producer and checks actual trigger delivery over WebSocket.
Run it before `listen`, since both use the example's subscription. Trigger
polling can take about a minute. If the runtime returns `OUTCOME_UNKNOWN`,
the example reports an unconfirmed operation and does not retry it. A delivered
event is not proof of a confirmed file read or move.

Use `python3 debug/hotfolder/bringup.py status` to inspect the stack. Ctrl+C
stops the foreground listener. `python3 debug/hotfolder/bringup.py stop` stops
the example containers and preserves the mount and volumes.
