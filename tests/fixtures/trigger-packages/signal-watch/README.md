<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Generic trigger-package acceptance fixture

This independent Java SDK package proves that WorkflowStack can adopt a package
outside its image catalog. It exports `acceptance.signal-watch/signal-ready`,
accepts `{ "file": "signals/alert.txt" }` and emits
`acceptance.signal.ready.v1` with `{ "body": "the signal text" }`.
It uses the already supported read-only managed device-files capability and POLL
runtime. It is not a production health, log, webhook or queue connector.

`trigger-tests.json` registers its deterministic source example in the
[parallel package test stage](../../../../tools/triggers/README.md). Tests need
no real provider account or credentials. Its CI result is independent of the
other registered trigger packages.

Build with a current published SDK repository/version and `toolgate` CLI:

```sh
gradle -PtoolgateSdkRepo=<sdk-repository> -PtoolgateSdkVersion=<sdk-version> toolgatePackage
```

Copy the resulting `.olo` to the chosen configuration's `packages/` folder.
The profile must pin its package digest, declare this trigger/config/event filter,
and map `/body` to its own workflow message. It needs no folder-delivery adapter.
Once the stack is ready, a test writes the signal file into the enrolled device's
private managed files area. Observe the authoritative workflow result and worker
execution metadata; no platform image or Compose service is added for this fixture.
