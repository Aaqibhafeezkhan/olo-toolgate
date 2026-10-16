-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Durable per-invocation run-response events (plan.md §12): redacted executor frames and authoritative platform state
-- transitions, ordered by a Control-assigned cursor. Executor frames are unique per invocation, fencing token and source
-- sequence, so redelivery after a reconnect or retry never duplicates a frame. The stream row allocates cursors under a
-- row lock and records the bounds that refused or dropped frames, so a reader is told explicitly what is missing.
CREATE TABLE control_invocation_event_streams(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    last_cursor bigint NOT NULL CHECK(last_cursor BETWEEN 0 AND 9007199254740991),
    event_count integer NOT NULL CHECK(event_count>=0),byte_count bigint NOT NULL CHECK(byte_count>=0),
    dropped_frames bigint NOT NULL CHECK(dropped_frames>=0),refused_frames bigint NOT NULL CHECK(refused_frames>=0),
    retain_until bigint NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at bigint CHECK(purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(tenant_id,invocation_id),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id));
CREATE INDEX control_invocation_event_stream_retention ON control_invocation_event_streams(tenant_id,retain_until) WHERE purged_at IS NULL;
CREATE TABLE control_invocation_events(tenant_id varchar(128) NOT NULL,invocation_id varchar(128) NOT NULL,
    event_cursor bigint NOT NULL CHECK(event_cursor BETWEEN 1 AND 9007199254740991),
    source varchar(16) NOT NULL CHECK(source IN('EXECUTOR','PLATFORM')),kind varchar(16) NOT NULL CHECK(kind IN('LOG','PROGRESS','INPUT_REQUEST','STATE','RESULT')),
    attempt integer CHECK(attempt BETWEEN 1 AND 16),fencing_token varchar(128),sequence integer CHECK(sequence BETWEEN 1 AND 2147483647),
    recorded_at bigint NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),document text NOT NULL CHECK(octet_length(document)<=131072),
    PRIMARY KEY(tenant_id,invocation_id,event_cursor),
    FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_invocation_event_streams(tenant_id,invocation_id),
    CHECK((source='EXECUTOR')=(fencing_token IS NOT NULL AND attempt IS NOT NULL)),
    CHECK(sequence IS NULL OR source='EXECUTOR'));
CREATE UNIQUE INDEX control_invocation_event_identity ON control_invocation_events(tenant_id,invocation_id,fencing_token,sequence) WHERE sequence IS NOT NULL;

GRANT SELECT,INSERT,UPDATE,DELETE ON control_invocation_event_streams,control_invocation_events TO toolgate_control_runtime;
