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
