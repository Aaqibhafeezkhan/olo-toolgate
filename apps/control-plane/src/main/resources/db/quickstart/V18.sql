-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Durable per-invocation run-response events; see db/migration/V25__invocation_run_events.sql.
CREATE TABLE control_invocation_event_streams(tenant_id TEXT NOT NULL,invocation_id TEXT NOT NULL,
    last_cursor INTEGER NOT NULL CHECK(last_cursor BETWEEN 0 AND 9007199254740991),
    event_count INTEGER NOT NULL CHECK(event_count>=0),byte_count INTEGER NOT NULL CHECK(byte_count>=0),
    dropped_frames INTEGER NOT NULL CHECK(dropped_frames>=0),refused_frames INTEGER NOT NULL CHECK(refused_frames>=0),
    retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at INTEGER CHECK(purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(tenant_id,invocation_id),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id));
-- statement
CREATE INDEX control_invocation_event_stream_retention ON control_invocation_event_streams(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE TABLE control_invocation_events(tenant_id TEXT NOT NULL,invocation_id TEXT NOT NULL,
    event_cursor INTEGER NOT NULL CHECK(event_cursor BETWEEN 1 AND 9007199254740991),
    source TEXT NOT NULL CHECK(source IN('EXECUTOR','PLATFORM')),kind TEXT NOT NULL CHECK(kind IN('LOG','PROGRESS','INPUT_REQUEST','STATE','RESULT')),
    attempt INTEGER CHECK(attempt BETWEEN 1 AND 16),fencing_token TEXT,sequence INTEGER CHECK(sequence BETWEEN 1 AND 2147483647),
    recorded_at INTEGER NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=131072),
    PRIMARY KEY(tenant_id,invocation_id,event_cursor),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_invocation_event_streams(tenant_id,invocation_id),
    CHECK((source='EXECUTOR')=(fencing_token IS NOT NULL AND attempt IS NOT NULL)),
    CHECK(sequence IS NULL OR source='EXECUTOR'));
-- statement
CREATE UNIQUE INDEX control_invocation_event_identity ON control_invocation_events(tenant_id,invocation_id,fencing_token,sequence) WHERE sequence IS NOT NULL;
