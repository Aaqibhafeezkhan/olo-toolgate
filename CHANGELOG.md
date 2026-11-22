# Changelog

## 1.0.0-dev.1

- Add five standard bot use cases (six configurations, including publication A/B)
  with active QLoRA/Ollama, lightweight Ollama and OpenRouter premium routing,
  plus a shared-Control/PostgreSQL
  Docker development wrapper with separate tenant ports, execution pools and
  destination-local custody. Startup can import an exported bot independently
  into either tenant. Graph preparation binds each tenant reader to its own
  HTTPS Control origin; obsolete single-origin reader configuration is refused.

- Add optional multi-bot Quickstart setup with a blank default, private credential
  reuse, per-bot configurators and readiness-gated activation of standard demos.
- Give current tenant Super Admins tenant-wide human runtime authority and run
  observation while retaining tenant isolation, policy and independent execution checks.

- Require complete bot release inventories and publish standalone core, Java
  example and bot-embedded tool/trigger archives in development and production
  releases. Add release-download walkthroughs and a shared guide for adapting
  bots with monitoring while keeping execution grants and secrets installation-owned.

- Add tenant-bound password login for external workflow callers, explicit human
  execution/resource grants, resumable structural events and verified signed HTTPS
  callbacks. Include Postman/receiver examples and an all-bot permission matrix.
- Provide the published-image community Quickstart with separate Quickstart,
  LangGraph runtime and Device services; keep local models and monitoring optional.
- Author, import, review and publish portable bots with referenced agents, model
  routing and exact tool/trigger packages. Preserve installation credentials and
  pinned existing runs when changing a bot.
- Govern MCP tools with current Control authorization, group grants, reviewed
  configuration, execution permits and tenant-scoped evidence.
- Support enrolled devices, managed-file trigger examples and signed package
  deployment within their declared execution boundaries.
- Expose workflow progress, audit/outcome views and optional collector integrations;
  distinguish observations from target confirmation and fencing.
- Preserve WorkflowStack storage on stop and use the explicit destroy command
  for removal. Consolidate password-free evaluation into QuickStart's login option.
- Organize current user, developer, operator and architecture guides around the
  fresh release. Keep only unfinished work in docs/plans, with two root README
  slideshows and separate media, performance and monitoring references.

See [current security scope](https://github.com/olo-labs/olo-toolgate/blob/main/docs/security/zero-trust-boundaries.md) and
[release preparation](https://github.com/olo-labs/olo-toolgate/blob/main/docs/development/how-to-release.md) before deployment.
These notes do not claim complete production or roadmap qualification.
