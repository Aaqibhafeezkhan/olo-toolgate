# Bots, models and execution

OLO separates a bot's portable configuration from installation credentials,
devices and model hosting. This walkthrough shows the console screens for that
workflow. These are saved captures of a configured debug installation, not
fresh-install defaults. See [capture scope](README.md#capture-sources-and-rebuild).

## Author and transfer a bot

The bot catalog exposes published bots and their Run actions. Import a `.olo`
bundle, inspect its dependencies and prepare/publish the intended revision.

![Bot catalog and Run actions](olo-2026-11/console/bots.jpg)

The bundle/profile view manages portable definitions. Human accounts, runtime
tokens, model weights and host permissions remain installation-owned.

![Bot bundles and profile configuration](olo-2026-11/console/workflow-profiles.jpg)

For the source and build path, use [bot authoring](../help/author-bots.md) and
[example bot sources](../../bot/README.md).

## Follow a run from input to result

Supply the input expected by the published bot. This example sends an incident
to a triage agent and then a handover agent.

![Incident input for the two-agent bot](olo-2026-11/console/multiagent-input.jpg)

Admission creates a run identity and a queued state. A queued request is not a
completed model call or tool effect.

![Accepted and queued bot run](olo-2026-11/console/multiagent-queued.jpg)

The progress view follows execution without changing the run's admitted revision.

![Running multi-agent execution](olo-2026-11/console/multiagent-running.jpg)

Inspect the terminal status and recorded result rather than inferring success
from a green container or an emitted span.

![Terminal run status](olo-2026-11/console/multiagent-result.jpg)

The recorded output combines triage with the second agent's handover. This text
example performs no remediation or external tool effects.

![Triage and handover output](olo-2026-11/console/multiagent-output.jpg)

![Animated input-to-result walkthrough](orchestration-flow.gif)

See [run and switch bots](../help/run-bots.md) for instructions and
[workflow architecture](../architecture/workflows.md) for execution boundaries.

## Configure models and agents

Model configuration selects deployment/group references. Provider credentials
and endpoint trust must be supplied by the destination installation.

![Configured workflow models](olo-2026-11/console/workflow-models.jpg)

Agent definitions hold prompts, model selection and output requirements.
Configuring an agent does not supply runtime tool grants.

![Agent models and prompts](olo-2026-11/console/agent-configuration.jpg)

Mappings connect the intended agent and bot configuration. Existing runs keep
their pinned revision when a different revision is published.

![Agent-to-bot mappings](olo-2026-11/console/agent-workflow-mappings.jpg)

Use [model setup](../help/models.md) for Ollama, routing and provider custody;
[monitoring](../help/observability.md#screenshots) shows this example's trace views.

[All illustrated capabilities](README.md) · [Back to OLO](../../README.md#what-olo-supports)
