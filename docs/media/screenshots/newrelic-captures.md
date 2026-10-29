# New Relic ToolGate captures

Captured 29 October 2026 from the standalone New Relic stack in account 7418784,
service `olo-toolgate-newrelic`. The successful Hello World use case produced real
traces containing gateway request, authorization, dispatch and Tool Host execution.
These are instrumentation-boundary spans, not method-by-method source-code profiling.
The greeting makes no database or external API calls, so no such spans are expected.

| Screen | Full-page snapshot |
| --- | --- |
| Trace overview | [Overview](newrelic-traces-overview-full.jpg) |
| Calls within the trace group | [Call list](newrelic-trace-call-list-full.jpg) |
| Expanded, maximized call waterfall | [Waterfall](newrelic-call-waterfall-full.jpg) |
| Tool execution correlation attributes | [Attributes](newrelic-execution-attributes-full.jpg) |
| Tool execution details | [Details](newrelic-execution-details-full.jpg) |

Earlier `newrelic-traces-full.jpg` documents the empty view before telemetry was
connected; use the overview above for the working integration.
