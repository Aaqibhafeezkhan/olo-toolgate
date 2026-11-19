<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO videos, tours and capture provenance

[Watch the 36-second OLO overview](toolgate-overview.mp4) ·
[GIF preview](toolgate-overview.gif) · [Poster](toolgate-overview.png) ·
[WebVTT captions](toolgate-overview.vtt). This silent captioned explainer is
rendered from diagrams; it is not a live installation recording.

The six scenes explain OLO's governed tool boundary, ToolGate/Orchestration,
group access, ALLOW/ASK/BLOCK, the two-agent trace and community contribution.
Filenames retain existing video links; the rendered branding/content is current.

## Current live UI slideshows

The four original slideshow filenames remain in use with newly captured OLO
console frames. Two additional sets show a successful two-agent incident bot and
its same-run traces in Langfuse, LangSmith and Phoenix.

- [Audit and approvals](audit-flow.gif)
- [Devices and package delivery](device-flow.gif)
- [Tools and access](console-tools-tour.gif)
- [Administration](console-administration.gif)
- [Multi-agent input, execution and result](orchestration-flow.gif)
- [Three monitoring consoles](monitoring-flow.gif)

[Step-by-step originals](TOURS.md) · [Capture inventory](screenshots.json) ·
[Current scope and limitations](capture-status.json) ·
[Run/cloud evidence](olo-2026-11/evidence.json).

These are browser captures from the November 19 debug stack, not unpublished
Docker images. Loaded empty queues remain empty. Monitoring exports structural
metadata only; absent content/cost fields do not indicate missing execution.
Each tour contains 2–15 distinct frames, with no filler minimum. Scaling and
captions appear only in generated GIFs; original screenshots remain unaltered.

## Rebuild

With Python 3, Pillow, imageio-ffmpeg and Windows Segoe UI or Linux DejaVu fonts:

```sh
python -m pip install Pillow imageio-ffmpeg
python tools/docs/render_overview.py
python tools/docs/build_console_tours.py
```

The tour generator validates source checksums, nonblank frames, frame counts and
inventory coverage. `screenshots.json` owns capture provenance; `tours.json`
owns slide order. External API credentials and local state are never media inputs.

## Historical monitoring and desktop captures

[Historical provenance](HISTORICAL.md) separates earlier Windows enrollment,
independent approvals, Operator Insights, Grafana/Prometheus and New Relic
captures from this demo. Existing monitoring guides remain at
[Prometheus/Grafana](../../deploy/prometheus/README.md),
[New Relic](../../deploy/newrelic/README.md) and
[workflow observability](../help/observability.md).
