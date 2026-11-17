# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Subscribe to ToolGate trigger events and obtain durable Control admission receipts."""
import argparse
import json
import os
import time
from pathlib import Path

from .trigger_bridge import ListenerFailure, Subscription
from .trigger_transport import ControlTriggers, TriggerListener


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-origin", required=True)
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--subscription")
    source.add_argument("--follow-subscriptions", action='store_true',help='Reconcile all authorized active subscriptions without restarting')
    parser.add_argument("--subscription-uid")
    parser.add_argument("--maximum-subscriptions",type=int,default=64)
    credential = parser.add_mutually_exclusive_group()
    credential.add_argument("--token-env", default="TOOLGATE_LISTENER_TOKEN")
    credential.add_argument("--token-file", type=Path)
    parser.add_argument("--ca-file")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--max-events", type=int)
    args = parser.parse_args(argv)
    if bool(args.subscription)!=bool(args.subscription_uid):
        parser.error('Pinned subscription name and UID must be provided together')
    if args.follow_subscriptions and args.max_events is not None:
        parser.error('max-events applies to a single pinned subscription')
    following=None
    try:
        def token():
            if args.token_file is not None:
                with args.token_file.open(encoding="ascii") as stream:
                    return stream.read(8193).strip()
            return os.environ.get(args.token_env, "")
        control = ControlTriggers(args.control_origin, token,
                                  ca_file=args.ca_file, timeout_seconds=args.timeout)
        if args.follow_subscriptions:
            from .subscriptions import Subscriptions
            following=Subscriptions(control,maximum=args.maximum_subscriptions)
            while True:
                try:
                    print(json.dumps({'event':'SUBSCRIPTIONS_RECONCILED',**following.refresh()}),flush=True)
                except ListenerFailure:
                    following.reconcile(())
                    print(json.dumps({'event':'SUBSCRIPTIONS_UNAVAILABLE'}),flush=True)
                time.sleep(2)
        subscription = Subscription(args.subscription, args.subscription_uid)
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
    finally:
        if following is not None:following.close()


if __name__ == "__main__":
    raise SystemExit(main())
