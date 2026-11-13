# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Host-run trigger subscriber. Durable workflow admission requires the pending Control endpoint."""
import argparse
import json
import os

from .trigger_bridge import ListenerFailure, Subscription
from .trigger_transport import ControlTriggers, TriggerListener


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-origin", required=True)
    parser.add_argument("--subscription", required=True)
    parser.add_argument("--subscription-uid", required=True)
    parser.add_argument("--token-env", default="TOOLGATE_LISTENER_TOKEN")
    parser.add_argument("--ca-file")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--max-events", type=int)
    args = parser.parse_args(argv)
    try:
        subscription = Subscription(args.subscription, args.subscription_uid)
        control = ControlTriggers(args.control_origin, lambda: os.environ.get(args.token_env, ""),
                                  ca_file=args.ca_file, timeout_seconds=args.timeout)
        count, last = TriggerListener(control, subscription).listen_once(max_events=args.max_events)
        result = {"processed": count}
        if last is not None:
            result.update(admissionId=last.admission_id, disposition=last.disposition, runId=last.run_id)
        print(json.dumps(result))
        return 0
    except KeyboardInterrupt:
        return 130
    except ListenerFailure as failure:
        print(json.dumps({"error": failure.code.value}))
        return 2
    except Exception:
        print(json.dumps({"error": "BOOTSTRAP_FAILED"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
