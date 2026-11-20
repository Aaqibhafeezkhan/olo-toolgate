# Shared standalone monitoring runtime

`compose.yaml` supplies QuickStart, the public-CA publisher, and the native device
to the New Relic, Datadog and Prometheus Compose projects. Each project declares
its own private network, ports, volumes and collector. This directory is source
configuration, not another running stack dependency.

Each vendor folder has `start.bat`, `stop.bat`, and `generate-data.bat`. Start
performs evaluation bootstrap and three rounds of real Hello World success plus
unauthenticated denial. Generate-data repeats those cases without reconfiguring
access. `--bootstrap` reapplies the scoped fixture through the normal review flow
and rotates its credential via QuickStart's reauthorization endpoint.

The native device checks in separately. Hello World executes on QuickStart's
bundled Tool Host; this fixture does not claim native-device package execution.
The root request, authorization, dispatch, and execution spans constitute the
instrumented call path. Method-level, database, or external-call spans require
corresponding instrumentation and a use case that makes those calls.

Validate the resolved vendor stack, enroll its device and run its allowed and
denied Hello World cases. Verify delivery at the actual monitoring destination.
A shared Compose definition or previous smoke test is not qualification of the
images and credentials selected for a new deployment.
