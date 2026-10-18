# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Standalone SQLite tasks and business-key reservations, matching Java's stores."""
from __future__ import annotations

import datetime as dt
import json
import secrets
import sqlite3
import threading
import time
from pathlib import Path

from .._json import instant_text, parse_instant
from ..protocol.tasks import MemoryTaskStore, TaskRecord, TaskStatus


def database(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(path), check_same_thread=False, timeout=5)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    return db


class SqliteTaskStore(MemoryTaskStore):
    def __init__(self, path):
        super().__init__()
        self.db = database(path)
        self.db.execute("CREATE TABLE IF NOT EXISTS python_tasks (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        for ident, body in self.db.execute("SELECT id, body FROM python_tasks"):
            values = json.loads(body)
            t = TaskRecord()
            for k, v in values.items():
                if k == "status":
                    v = TaskStatus(v)
                elif k.endswith("_at"):
                    v = parse_instant(v)
                setattr(t, k, v)
            if not t.status.terminal:
                t.status = TaskStatus.FAILED
                t.input_requests.clear()
                t.input_responses.clear()
                t.error = {"code": -32603, "message": "The previous execution outcome is unknown",
                           "data": {"code": "OUTCOME_UNKNOWN", "retryable": False}}
            self._tasks[ident] = t
        self._save()

    def _save(self):
        with self.db:
            self.db.execute("DELETE FROM python_tasks")
            for t in self._tasks.values():
                body = {k: v.value if isinstance(v, TaskStatus) else instant_text(v) if isinstance(v, dt.datetime)
                        else v for k, v in vars(t).items()}
                self.db.execute("INSERT INTO python_tasks VALUES (?, ?)", (t.task_id, json.dumps(body)))

    def _mutate(self, operation, *args):
        with self._lock:
            before = {k: v.copy() for k, v in self._tasks.items()}
            try:
                result = operation(*args)
                self._save()
                return result
            except Exception:
                self._tasks = before
                raise

    def create(self, task):
        return self._mutate(super().create, task)

    def transition(self, ident, change):
        return self._mutate(super().transition, ident, change)

    def submit_input(self, ident, owner, responses):
        return self._mutate(super().submit_input, ident, owner, responses)

    def request_cancel(self, ident, owner):
        return self._mutate(super().request_cancel, ident, owner)

    def purge_expired(self, at):
        return self._mutate(super().purge_expired, at)

    def close(self):
        with self._lock:
            self.db.close()


class Reservations:
    """Atomic reserve-before-execution; completed results and expired tombstones survive restart."""
    def __init__(self, path, retention=30 * 86400):
        self.db = database(path)
        self.lock = threading.RLock()
        self.retention = retention
        with self.db:
            self.db.execute("CREATE TABLE IF NOT EXISTS python_reservations "
                            "(scope TEXT, key TEXT, owner TEXT, fingerprint TEXT, expires REAL, result TEXT, "
                            "PRIMARY KEY(scope,key))")
            self.db.execute("CREATE TABLE IF NOT EXISTS python_state_meta (id TEXT PRIMARY KEY, value TEXT)")
            self.db.execute("INSERT OR IGNORE INTO python_state_meta VALUES ('salt', ?)", (secrets.token_hex(32),))
            # A process owns this state file, exactly as the Java runtime does.
            unknown = json.dumps({"content": [{"type": "text", "text": "The previous execution outcome is unknown"}],
                                  "isError": True, "structuredContent": {"error": {
                                      "code": "OUTCOME_UNKNOWN", "message": "The previous execution outcome is unknown",
                                      "retryable": False, "data": {}}}})
            self.db.execute("UPDATE python_reservations SET result=? WHERE result IS NULL", (unknown,))
        self.salt = bytes.fromhex(self.db.execute("SELECT value FROM python_state_meta WHERE id='salt'").fetchone()[0])

    def reserve(self, scope, key, owner, fingerprint):
        with self.lock, self.db:
            self.db.execute("BEGIN IMMEDIATE")
            row = self.db.execute("SELECT owner,fingerprint,expires,result FROM python_reservations "
                                  "WHERE scope=? AND key=?", (scope, key)).fetchone()
            if row:
                if row[0] != owner:
                    return "IDEMPOTENCY_KEY_IN_USE", None
                if row[2] <= time.time():
                    return "EXPIRED_KEY", None
                if row[1] != fingerprint:
                    return "IDEMPOTENCY_CONFLICT", None
                return ("REPLAY", json.loads(row[3])) if row[3] else ("IN_PROGRESS", None)
            self.db.execute("INSERT INTO python_reservations VALUES (?,?,?,?,?,NULL)",
                            (scope, key, owner, fingerprint, time.time() + self.retention))
            return "NEW", None

    def finish(self, scope, key, result):
        with self.lock, self.db:
            self.db.execute("UPDATE python_reservations SET result=? WHERE scope=? AND key=?",
                            (json.dumps(result), scope, key))

    def release(self, scope, key):
        with self.lock, self.db:
            self.db.execute("DELETE FROM python_reservations WHERE scope=? AND key=?", (scope, key))

    def close(self):
        self.db.close()
