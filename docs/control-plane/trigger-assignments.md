<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Manage client trigger assignments

Open **Tools → Triggers** in the Admin UI. The page lists live assignments
and the desired state, reported phase, and configuration revision of each
device. The workflow profile editor on the same page manages profile drafts.

1. With `package.import`, upload the trigger's signed `.tgpkg` using
   **Upload trigger package**. Control verifies the package before storing it.
2. With `trigger.manage` on the target, open **Create trigger assignment**.
   Enter a JSON object with the package's exported trigger ID and version,
   the target device IDs or Device Groups, and a configuration that matches
   the trigger's schema. For example:

   ```json
   {
     "name": "watch-inbox",
     "spec": {
       "trigger": "examples.inbox-watch/new-file",
       "packageVersion": "1.0.0",
       "target": {"executor": "CLIENT", "deviceGroups": ["finance"]},
       "config": {"folder": "inbox"},
       "desiredState": "Running",
       "strategy": {"rollingUpdate": {"maxUnavailable": "1"}}
     }
   }
   ```

3. Open **View instances** to inspect each device. The target device needs
   the matching package in its signed client release. Package delivery
   uses the fleet rollout.
4. Open **Manage** to run, pause, stop, clear, deploy, or remove targets.
   Choose one scope and enter its IDs and a reason. Deploy also needs an
   initial desired state. Rollback restores the previous configuration.
   If Control reports a revision conflict, close the dialog, refresh
   assignments, and review the latest state before retrying.

For an incident that spans multiple assignments, use
`POST /api/control/v1/triggers:action` with a Device Group selector and
trigger export IDs or `package/*` patterns. The request applies Run, Pause,
Stop, or Clear to every matching group within one transaction. Control
requires `trigger.manage` on every group in the selector.

With `trigger.subscribe` on the assignment's targets, choose **Watch** to
see incoming events over HTTPS. The Live panel shows events that arrive
after the watch starts and displays the event fields your clearance
permits. The watch has no stored cursor. Choose **Stop watching** to close it.

To exercise the lifecycle phase transitions from M12 scenario 10, use a
disposable Quickstart with four approved, running devices. Each device needs
the same signed client release for `examples.inbox-watch` and an accessible
`inbox` folder. From the repository root, using the isolated LangChain
example's connection and credentials:

```sh
python examples/langchain/trigger_lifecycle_e2e.py --isolated-fixture \
  --device device-a device-b device-c device-d --package-version 1.0.0 \
  --checkpoint-service lifecycle-device-b
```

Use `examples/langchain/compose.trigger-lifecycle.yaml` with the base
Compose file for separate device state volumes. The optional checkpoint
probe writes one synthetic marker to the selected D2 container and checks
its local checkpoint before pause and after resume. The post-resume
checkpoint must retain the first marker and advance the same lease epoch
by exactly one outbox record for the new marker. The fixture remains
available for inspection until you remove the disposable stack. For the failing-package rollout scenario, provide a distinct Inbox Watch
`.tgpkg` whose poll process exits on the fixture configuration. Use three
devices already running the known-good package and pass the failing archive
to `examples/langchain/trigger_rollout_e2e.py`. The runner assigns the bad
fleet release only to the device selected by the trigger rollout and
restores the good release to that device after requesting rollback.
