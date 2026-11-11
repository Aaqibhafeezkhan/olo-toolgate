# LangChain HotFolder example

From Windows, open `start.bat` in this directory. Choose **1** to register for
HotFolder events and listen, or **2** to select an installed Ollama model and
chat. Type `/back` in chat to return to the menu. Chat sends typed prompts to
Ollama and keeps conversation history for that chat session.

Ollama must be reachable from WSL at `http://127.0.0.1:11434`, or at the address
in `OLLAMA_HOST` in the WSL environment. The console lists models from that
server. The mounted HotFolder lab uses port 18092 and the standard Docker
Desktop ToolGate console uses port 18090.

Start the [mounted HotFolder lab](../README.md), then run the example from a
WSL shell at the repository root. Use the Python environment created by the lab.

```sh
. .dev/debug/hotfolder/venv/bin/activate
python debug/hotfolder/langchain/run.py listen
```

In another WSL terminal, drop a new sample into the mounted inbox.

```sh
python3 debug/hotfolder/bringup.py drop order-1044.txt '2 x widget'
```

The listener prints the path and outcome after receiving the trigger over
WebSocket. On a confirmed read it displays the contents and attempts the move
through the signed HotFolder tool. With the current unqualified native runtime,
`read-unconfirmed` leaves the sample in `inbox/` without retrying.

If the listener is already running with the lab's detached log, watch it with
`tail -f .dev/debug/hotfolder/listener.log` instead of starting another listener.

Use `python debug/hotfolder/langchain/run.py verify` for a one-shot smoke test.
Stop the foreground listener first because both commands share the example's
subscription. The [shared example](../../../examples/langchain/README.md#hot-folder-trigger-in-progress-issue-274)
contains the graph and signed-tool client used by both commands.


Edit [profile/deployment-profile.json](profile/deployment-profile.json) before starting
HotFolder. The launcher stages a root-owned snapshot and mounts it read-only in
Control. Restart with the launcher to apply changes. Start with `start.bat
--no-profile` from the parent HotFolder directory to skip injection. Existing
persisted configuration is retained.

In the console, create a working profile and use **Agents > Models & prompts**
to select a model group and set the agent prompt. Configure model deployments
and groups on the same page or under **Workflows > Models**. Use **Tools > Triggers** to configure
a package's trigger assignments against its signed configuration schema.
Under **Workflows > Trigger mappings**, select the workflow and enter the
subscription UID, assignment UID, service agent and event message pointer.
Stage, review, provision and publish the revision as appropriate. **Workflows >
Profiles** imports and downloads the same version-2 deployment profile format.
A mounted file provisions packages at startup; workflow definitions in that file
still need an explicit import, review and publication.

The signed trigger event reports file data. The reviewed mapping pins the
workflow ID and version separately. Control does not yet implement workflow
admission, so the mapping does not start a workflow when an event arrives.
