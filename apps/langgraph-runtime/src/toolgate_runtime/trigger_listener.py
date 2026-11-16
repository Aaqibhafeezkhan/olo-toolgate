# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Subscribe to ToolGate trigger events and obtain durable Control admission receipts."""
import argparse
import json
import os
from pathlib import Path

from .trigger_bridge import ListenerFailure, Subscription
from .trigger_transport import ControlTriggers, TriggerListener


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-origin", required=True)
    parser.add_argument("--subscription", required=True)
    parser.add_argument("--subscription-uid", required=True)
    credential = parser.add_mutually_exclusive_group()
    credential.add_argument("--token-env", default="TOOLGATE_LISTENER_TOKEN")
    credential.add_argument("--token-file", type=Path)
    parser.add_argument("--ca-file")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--max-events", type=int)
    args = parser.parse_args(argv)
    try:
        subscription = Subscription(args.subscription, args.subscription_uid)
        def token():
            if args.token_file is not None:
                with args.token_file.open(encoding="ascii") as stream:
                    return stream.read(8193).strip()
            return os.environ.get(args.token_env, "")
        control = ControlTriggers(args.control_origin, token,
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
