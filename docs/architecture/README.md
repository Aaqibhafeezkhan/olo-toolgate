# Architecture

- [Overview](overview.md): current authorization and execution model.
- [Component map](component-map.md): ownership and boundaries.
- [Trust boundaries](trust-boundaries.md) and [data flows](data-flows.md).
- [Package model](package-model.md) and [desired/reported state](desired-vs-reported-state.md).
- [Shared contracts](shared-contracts.md), [compatibility](compatibility.md) and [scalability](scalability.md).
- [Repository layout](monorepo-layout.md) and [decision records](../adr/README.md).

Component guides describe current support and limitations.

## Workflow creation and execution

- [LangGraph runtime plan](langgraph-runtime-plan.md): proposed configuration-driven
  workflow/group creation, complete use-case profiles, ToolGate-driven prompts, context and models, and generic message/event execution (v0.8 section 19), tracked in
  [enhancement #295](https://github.com/olo-labs/olo-toolgate/issues/295).
- [Triggers design](device-triggers-plan.md) and
  [implementation status](triggers-implementation-status.md): related trigger work
  tracked in [issue #274](https://github.com/olo-labs/olo-toolgate/issues/274).
- [Trigger architecture decision](../adr/058-triggers.md): trigger boundaries and contracts.

The runtime plan is a proposal, not a statement of supported functionality.
Repository instructions remain authoritative where its migration or compatibility
proposals conflict with the fresh breaking-build constraints for #230 and #274.
