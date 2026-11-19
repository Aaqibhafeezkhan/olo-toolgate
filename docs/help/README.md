<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO community help

OLO has two sections: **OLO Orchestration** for bots, agents and model routing,
and **OLO ToolGate** for governed tools, triggers, devices and access. Technical
repository, image and API names still use `olo-toolgate`.

| Task | Guide |
|---|---|
| Start from published images, once available | [Docker Quickstart dev](../../deploy/docker/quickstart/dev/README.md) |
| Start the local installation | [Profiled Quickstart](../../debug/profiled/README.md) |
| Import, publish, run or switch | [Run bots](run-bots.md) |
| Approve a device, change folders or install packages | [Device setup and updates](device.md) |
| Set QLoRA, Ollama, fallback order or OpenRouter | [Model setup and updates](models.md) |
| Write JSON graphs and build bundles | [Author bots](author-bots.md) |
| Configure Langfuse, LangSmith and Phoenix | [Observability](observability.md) |
| Resolve readiness errors or failed runs | [Troubleshooting](troubleshooting.md) |
| Pick an example and its exact input/output | [Bot sources](../../bot/README.md) |

Start blank, prepare the installation's model/device, then import one `.olo`.
Credentials, real device membership, model weights and filesystem permissions
belong to that installation. Importing a bot never creates, removes or moves
input files. See [implementation evidence and limits](../architecture/workflow-v1-status.md);
a successful bundle build alone does not demonstrate an executable deployment.
