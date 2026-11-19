<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 079: Profile-selected local models and ordered inference fallback

OLO bot model groups declare deployment order. Tool agents use a QLoRA deployment
first and the lightweight Ollama deployment second; other agents select the
lightweight deployment. A premium group explicitly marked `standby: true` is configuration only: no
credentials are required and no model request can reach it until an administrator
explicitly attaches it to both the workflow ceiling and an agent's model group,
provides its scoped secret, approves its endpoint and publishes the revision.

Control resolves one candidate/credential at a time from the ordered intersection
of the step's group and its pinned workflow ceiling. Each resolution rechecks the
current run/step, tenant kill state, service agent grants and selected agent's
group. The request explicitly carries its candidate index; no obsolete request
format or caller-selected provider/model is accepted. Credentials remain inside
Control/Gateway and are never returned to graph authors or workers.

Gateway attempts each configured candidate at most once, within one bounded
inference budget. Provider connection/HTTP/decision-decoding failures may move
to the next candidate. Authority/configuration failures stop immediately. A valid
model decision is returned once; policy-denied tools, approval outcomes, writes,
reads and completed effects are never retried by model fallback. This is not an
effect-resend mechanism or a quality guarantee for a small model.

The inference budget reserves 500 ms inside the configured outer Gateway request
deadline, capped at 25 seconds. Deployment generation can explicitly select
provider-supported `reasoningEffort` (`none`, `low`, `medium`, `high`); workers
cannot override it. Local structured-output examples select `none` to avoid
spending the short inference budget on an unreturned reasoning channel.

Standby groups cannot be assigned to workflows or agents until explicitly
activated; bundle scoping retains them while pruning unrelated active groups.
Unused declared deployments remain structurally validated but do not block active
workflows on absent secrets. Reachable workflow/agent model deployments still
require current scope/readiness. Premium activation is explicit rather than a
hidden availability/cost fallback. Local model weights, TLS/credential custody
and container settings remain installation data outside portable bot bundles.

Implementation, tests, limitations and actual local acceptance are recorded in
workflow-v1-status. Full roadmap and security phase acceptance remains separate.

Control also resolves the pinned agent/workflow finish-result schema. Gateway
uses it for constrained decoding and validates the finish result before returning
a decision. Invalid result bodies follow inference fallback, never tool replay.
Local schema references are rebased into the envelope; remote references and
resource IDs are refused before network IO. This does not make model proposals
authority or guarantee their factual quality.
