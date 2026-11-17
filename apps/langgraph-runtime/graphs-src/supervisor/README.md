<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Governed supervisor graph

This operator-installed graph runs the existing ToolGate supervisor and optional
ordered agent stages using the generic native graph loader. It preserves current
candidate checks, per-step model/tool authorization, PREPARE and unknown-outcome
behavior. Configuration optionally sets `resultKey` to wrap the completed result.
It has no checkpoint/resume or automatic effect-retry capability. The factory
returns an unbound StateGraph; each invocation receives fresh governed adapters.

Rebuild the immutable directory with `tools/workflows/graph_package.py`; pin the
printed selection in workflow JSON. File hashes use UTF-8 text with LF newlines.
