# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Durable ACK ordering against a simulated Control ledger, not actual database qualification."""
from dataclasses import replace
import json
import threading
import unittest

from toolgate_runtime.trigger_bridge import (DispatchReceipt, ListenerFailure, Reason, Subscription, TriggerBridge)

SUB = Subscription("monitor", "ts-immutable-1")
C1, C2, C3 = (f"{x:016d}" for x in (1, 2, 3))


def frame(cursor=C1, **event):
    return json.dumps({"type": "event", "subscription": SUB.name, "cursor": cursor, "event": event})


class Ledger:
    def __init__(self):
        self.rows, self.calls, self.runs = {}, [], []
        self.disposition = "ADMITTED"
        self.unavailable = False

    def admit(self, uid, cursor, *, timeout_seconds):
        self.calls.append((uid, cursor))
        if self.unavailable:
            raise TimeoutError("secret-bootstrap-token and private event body")
        key = uid, cursor
        if key in self.rows:
            return replace(self.rows[key], disposition="DUPLICATE")
        run = "run-" + str(len(self.runs) + 1) if self.disposition == "ADMITTED" else None
        receipt = DispatchReceipt(uid, cursor, "admission-" + str(len(self.rows) + 1), self.disposition, run)
        self.rows[key] = receipt
        if run:
            self.runs.append(run)
        return receipt


class BridgeTests(unittest.TestCase):
    def bridge(self, ledger=None):
        ledger = ledger or Ledger()
        bridge = TriggerBridge(SUB, ledger)
        bridge.handle(json.dumps({"type": "subscribed", "subscription": SUB.name}), lambda _: self.fail("No ACK for subscribe"))
        return bridge, ledger

    def test_server_admission_precedes_ack_and_credit_and_payload_cannot_select_identity(self):
        bridge, ledger = self.bridge(); sent = []
        def send(raw):
            self.assertIn((SUB.uid, C1), ledger.rows)
            sent.append(json.loads(raw))
        receipt = bridge.handle(frame(workflowId="attacker", runAs="administrator", model="provider", prompt="override"), send)
        self.assertEqual(ledger.calls, [(SUB.uid, C1)])
        self.assertEqual(receipt.run_id, "run-1")
        self.assertEqual(sent, [{"type": "ack", "subscription": "monitor", "cursor": C1}, {"type": "credit", "n": 1}])

    def test_crash_after_commit_before_ack_replays_one_admission_and_run(self):
        bridge, ledger = self.bridge()
        def lost(_): raise ConnectionError("secret-ticket")
        with self.assertRaises(ListenerFailure) as error:
            bridge.handle(frame(), lost)
        self.assertEqual(error.exception.code, Reason.CONNECTION_LOST)
        self.assertEqual(ledger.runs, ["run-1"])
        bridge, _ = self.bridge(ledger); sent = []
        receipt = bridge.handle(frame(), sent.append)
        self.assertEqual(receipt.disposition, "DUPLICATE")
        self.assertEqual(receipt.run_id, "run-1")
        self.assertEqual(ledger.runs, ["run-1"])
        self.assertEqual(json.loads(sent[0])["cursor"], C1)

    def test_duplicate_terminal_rejection_never_creates_a_run(self):
        for disposition in ("REJECTED", "FILTERED"):
            ledger = Ledger(); ledger.disposition = disposition
            bridge, _ = self.bridge(ledger)
            first = bridge.handle(frame(), lambda _: None)
            replay = bridge.handle(frame(), lambda _: None)
            self.assertEqual(first.disposition, disposition)
            self.assertEqual(replay.disposition, "DUPLICATE")
            self.assertIsNone(replay.run_id)
            self.assertFalse(ledger.runs)

    def test_failure_permanently_prevents_acknowledging_later_cursors(self):
        bridge, ledger = self.bridge(); ledger.unavailable = True; sent = []
        with self.assertRaisesRegex(ListenerFailure, "^ADMISSION_UNAVAILABLE$"):
            bridge.handle(frame(), sent.append)
        ledger.unavailable = False
        with self.assertRaisesRegex(ListenerFailure, "^STOPPED$"):
            bridge.handle(frame(C2), sent.append)
        self.assertEqual(ledger.calls, [(SUB.uid, C1)])
        self.assertFalse(sent)
        self.assertFalse(ledger.rows)

    def test_missing_foreign_non_durable_or_malformed_receipt_is_not_acknowledged(self):
        valid = DispatchReceipt(SUB.uid, C1, "admission-1", "ADMITTED", "run-1")
        cases = (None, False, replace(valid, subscription_uid="foreign"), replace(valid, cursor=C2),
                 replace(valid, disposition="PENDING"), replace(valid, run_id=None),
                 replace(valid, disposition="REJECTED"), replace(valid, admission_id=""))
        for receipt in cases:
            with self.subTest(receipt=receipt):
                ledger = Ledger(); ledger.admit = lambda *a, **kw: receipt
                bridge, _ = self.bridge(ledger); sent = []
                with self.assertRaisesRegex(ListenerFailure, "^INVALID_RECEIPT$"):
                    bridge.handle(frame(), sent.append)
                self.assertFalse(sent)

    def test_protocol_failures_and_loss_notices_stop_without_admission_or_ack(self):
        cases = ('{"type":"event","type":"subscribed"}', 'null', '{"type":NaN}', "x" * 65537,
                 b'{}', json.dumps({"type": "gap", "subscription": "monitor", "toCursor": C3}),
                 json.dumps({"type": "revoked", "subscription": "monitor", "reason": "secret"}),
                 json.dumps({"type": "error", "error": {"detail": "secret"}}),
                 frame("bad"), json.dumps({"type": "event", "subscription": "foreign", "cursor": C1, "event": {}}),
                 json.dumps({"type": "event", "cursor": C1, "event": {}}))
        for raw in cases:
            bridge, ledger = self.bridge(); sent = []
            with self.assertRaises(ListenerFailure) as failure:
                bridge.handle(raw, sent.append)
            self.assertNotIn("secret", str(failure.exception))
            self.assertFalse(ledger.calls)
            self.assertFalse(sent)

    def test_events_require_subscribed_and_cursors_are_ordered_not_contiguous(self):
        ledger = Ledger(); bridge = TriggerBridge(SUB, ledger)
        with self.assertRaisesRegex(ListenerFailure, "^NOT_SUBSCRIBED$"):
            bridge.handle(frame(), lambda _: None)
        bridge, ledger = self.bridge()
        bridge.handle(frame(C1), lambda _: None)
        bridge.handle(frame(C3), lambda _: None)  # A global cursor not selected by this subscription may be absent.
        with self.assertRaisesRegex(ListenerFailure, "^OUT_OF_ORDER$"):
            bridge.handle(frame(C2), lambda _: None)
        self.assertEqual(ledger.calls, [(SUB.uid, C1), (SUB.uid, C3)])

    def test_cancellation_leaves_session_stopped_and_cursor_unacknowledged(self):
        ledger = Ledger()
        def cancel(*a, **kw): raise KeyboardInterrupt()
        ledger.admit = cancel
        bridge, _ = self.bridge(ledger); sent = []
        with self.assertRaises(KeyboardInterrupt): bridge.handle(frame(), sent.append)
        with self.assertRaisesRegex(ListenerFailure, "^STOPPED$"): bridge.handle(frame(C2), sent.append)
        self.assertFalse(sent)

    def test_concurrent_delivery_cannot_skip_an_unresolved_earlier_cursor(self):
        entered, release = threading.Event(), threading.Event()
        ledger = Ledger()
        def blocked(uid, cursor, **kwargs):
            ledger.calls.append((uid, cursor)); entered.set()
            if not release.wait(3): raise AssertionError("Test coordination failed")
            raise TimeoutError()
        ledger.admit = blocked
        bridge, _ = self.bridge(ledger); sent, errors = [], []
        def run(cursor):
            try: bridge.handle(frame(cursor), sent.append)
            except ListenerFailure as error: errors.append(error.code)
        first = threading.Thread(target=run, args=(C1,)); second = threading.Thread(target=run, args=(C2,))
        first.start(); self.assertTrue(entered.wait(3)); second.start(); release.set()
        first.join(3); second.join(3)
        self.assertFalse(first.is_alive()); self.assertFalse(second.is_alive())
        self.assertEqual(ledger.calls, [(SUB.uid, C1)])
        self.assertCountEqual(errors, [Reason.ADMISSION_UNAVAILABLE, Reason.STOPPED])
        self.assertFalse(sent)


if __name__ == "__main__":
    unittest.main()
