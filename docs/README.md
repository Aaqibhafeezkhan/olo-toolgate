# OLO documentation

OLO Orchestration runs configured bots; OLO ToolGate governs their tools,
devices and runtime access. Start with the guide for the task you are doing.

| Audience | Start here |
|---|---|
| New users | [Beginner bot walkthroughs](../bot/README.md#first-time-users-choose-a-bot), [Published-image Quickstart](../deploy/docker/quickstart/dev/README.md), [first login](getting-started/first-login.md) |
| Bot authors | [Community help](help/README.md), [examples](../bot/README.md) |
| Domain users adapting a bot | [Tune prompts/configuration with monitoring](help/tune-bots.md) |
| Tool authors | [Tool SDK guide](../TOOL-SDK-GUIDE.md), [SDK specifications](../tool-sdk/README.md) |
| Contributors | [Contributing](../CONTRIBUTING.md), [development setup](development/README.md), [verification](testing/README.md) |
| Operators | [Deployment](deployment/README.md), [operations](operations/README.md), [recovery](enterprise-access-control/operations.md) |
| Security reviewers | [Security model](security/README.md), [enterprise evaluation](enterprise-access-control/README.md) |

## Current architecture and APIs

- [Component ownership and repository layout](architecture/component-map.md)
- [Architecture overview](architecture/overview.md), [data flows](architecture/data-flows.md) and [trust boundaries](architecture/trust-boundaries.md)
- [Workflow execution](architecture/workflows.md) and [triggers](architecture/triggers.md)
- [Current contracts and fresh stores](architecture/compatibility.md)
- [HTTP/MCP API](api/api-reference.md) and [canonical contracts](../packages/contracts/README.md)
- [Gateway](gateway/README.md), [Control Plane](control-plane/README.md), [devices](client/README.md) and [Tool Host](../apps/tool-host/README.md)
- [Glossary](GLOSSARY.md)

## Performance, monitoring and demonstrations

- [Performance and capacity](operations/performance.md): configuration, measurements and their limits
- [Monitoring](help/observability.md): workflow collectors and ToolGate metrics/traces
- [Images, slideshows and videos](media/README.md): demonstration media and capture scope
- [Illustrated capabilities](../README.md#what-olo-supports): direct links to the
  screenshot-rich bot, device, access, audit, administration and monitoring guides

Use [unfinished plans](plans/README.md) for remaining work. Current instructions
and supported behavior belong in component guides, not delivery logs.
See the [documentation policy](contributors/documentation-guide.md).
