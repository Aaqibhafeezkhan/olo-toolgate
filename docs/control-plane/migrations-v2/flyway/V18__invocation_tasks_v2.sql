-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- SPECIFICATION (gate D2). Ships unchanged as db/migration/V18__invocation_tasks_v2.sql in M1;
-- only the version number may change if another migration lands first.
-- Task, execution-ledger, lease and business-key state for v2 invocations.
-- Normative description: docs/control-plane/invocation-state-v2.md.

-- 1. v2 columns on the existing invocation row. All are NULL for v1 rows.
ALTER TABLE control_enterprise_invocations
    ADD COLUMN tool_id varchar(256),
    ADD COLUMN package_id varchar(128),
    ADD COLUMN package_digest char(71) CHECK (package_digest ~ '^sha256:[a-f0-9]{64}$'),
    ADD COLUMN pool_id varchar(128),
    ADD COLUMN runtime_digest char(71) CHECK (runtime_digest ~ '^sha256:[a-f0-9]{64}$'),
    ADD COLUMN effect_safety varchar(16) CHECK (effect_safety IN ('READ_ONLY','IDEMPOTENT','NON_IDEMPOTENT','UNKNOWN')),
    ADD COLUMN requires_execution_ledger boolean,
    ADD COLUMN lease_holder varchar(256),
    ADD COLUMN lease_expires_at bigint CHECK (lease_expires_at BETWEEN 0 AND 9007199254740991),
    ADD COLUMN cancel_requested_at bigint CHECK (cancel_requested_at BETWEEN 0 AND 9007199254740991),
    ADD COLUMN not_started boolean NOT NULL DEFAULT false,
    ADD COLUMN task_id varchar(64) CHECK (task_id ~ '^[A-Za-z0-9_-]{22,64}$'),
    ADD COLUMN task_state varchar(32),
    ADD COLUMN task_owner_digest char(64) CHECK (task_owner_digest ~ '^[a-f0-9]{64}$'),
    ADD COLUMN task_retain_until bigint CHECK (task_retain_until BETWEEN 0 AND 9007199254740991),
    ADD COLUMN task_input_deadline_at bigint CHECK (task_input_deadline_at BETWEEN 0 AND 9007199254740991),
    ADD COLUMN task_last_polled_at bigint CHECK (task_last_polled_at BETWEEN 0 AND 9007199254740991),
    ADD COLUMN task_document text CHECK (octet_length(task_document) <= 32768);

-- A v2 row carries its resolved execution class; the ledger flag is derived, never chosen.
ALTER TABLE control_enterprise_invocations ADD CONSTRAINT control_invocation_v2_class CHECK (
    requires_execution_ledger IS NULL
    OR (tool_id IS NOT NULL AND package_digest IS NOT NULL AND effect_safety IS NOT NULL
        AND requires_execution_ledger = (effect_safety IN ('NON_IDEMPOTENT','UNKNOWN'))));

-- Task columns exist together or not at all.
ALTER TABLE control_enterprise_invocations ADD CONSTRAINT control_invocation_task_shape CHECK (
    (task_id IS NULL) = (task_state IS NULL)
    AND (task_id IS NULL) = (task_owner_digest IS NULL)
    AND (task_id IS NULL) = (task_retain_until IS NULL)
    AND (task_id IS NULL) = (task_document IS NULL)
    AND (task_id IS NULL OR requires_execution_ledger IS NOT NULL));

-- The task state is a projection of the authoritative invocation state (§2 of the spec).
ALTER TABLE control_enterprise_invocations ADD CONSTRAINT control_invocation_task_projection CHECK (
    task_state IS NULL
    OR (state = 'PENDING_APPROVAL' AND task_state = 'AWAITING_APPROVAL')
    OR (state = 'QUEUED' AND task_state = 'QUEUED')
    OR (state IN ('RESERVED','DISPATCHED') AND task_state IN ('DISPATCHED','CANCEL_REQUESTED'))
    OR (state = 'EXECUTING' AND task_state IN ('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
    OR (state = 'SUCCEEDED' AND task_state = 'COMPLETED')
    OR (state IN ('FAILED','PARTIAL') AND task_state = 'FAILED')
    OR (state = 'CANCELLED' AND task_state IN ('CANCELLED','REJECTED'))
    OR (state = 'EXPIRED' AND task_state = 'EXPIRED')
    OR (state = 'OUTCOME_UNKNOWN' AND task_state = 'OUTCOME_UNKNOWN'));

ALTER TABLE control_enterprise_invocations ADD CONSTRAINT control_invocation_lease_shape CHECK (
    (lease_holder IS NULL) = (lease_expires_at IS NULL)
    AND (lease_holder IS NULL OR state NOT IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED')));

ALTER TABLE control_enterprise_invocations ADD CONSTRAINT control_invocation_cancel_shape CHECK (
    (task_state IS DISTINCT FROM 'CANCEL_REQUESTED' OR cancel_requested_at IS NOT NULL)
    AND (NOT not_started OR state = 'FAILED'));

CREATE UNIQUE INDEX control_invocation_task ON control_enterprise_invocations (tenant_id, task_id)
    WHERE task_id IS NOT NULL;
CREATE INDEX control_invocation_lease ON control_enterprise_invocations (tenant_id, lease_expires_at)
    WHERE state = 'EXECUTING';
CREATE INDEX control_invocation_task_retention ON control_enterprise_invocations (tenant_id, task_retain_until)
    WHERE task_id IS NOT NULL;
CREATE INDEX control_invocation_input_deadline ON control_enterprise_invocations (tenant_id, task_input_deadline_at)
    WHERE task_state IN ('AWAITING_APPROVAL','INPUT_REQUIRED');
CREATE INDEX control_invocation_active_package ON control_enterprise_invocations (tenant_id, package_digest)
    WHERE state IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
CREATE INDEX control_invocation_active_tool ON control_enterprise_invocations (tenant_id, tool_id)
    WHERE state IN ('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');

-- 2. Atomic business-operation reservations and their tombstones (plan.md §11.7).
-- Separate from the invocation row because a tombstone outlives the result it replaced.
CREATE TABLE control_business_keys (
    tenant_id varchar(128) NOT NULL,
    tool_id varchar(256) NOT NULL,
    namespace varchar(16) NOT NULL CHECK (namespace IN ('AGENT','DELEGATED_USER','TENANT')),
    namespace_identity varchar(128) NOT NULL,
    business_key_hash char(64) NOT NULL CHECK (business_key_hash ~ '^[a-f0-9]{64}$'),
    source varchar(16) NOT NULL CHECK (source IN ('HEADER','ARGUMENT','DEDUPE_WINDOW')),
    fingerprint char(71) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    caller_agent_id varchar(128) NOT NULL,
    caller_user_id varchar(128),
    invocation_id varchar(128) NOT NULL,
    created_at bigint NOT NULL CHECK (created_at BETWEEN 0 AND 9007199254740991),
    dedupe_expires_at bigint CHECK (dedupe_expires_at BETWEEN 0 AND 9007199254740991),
    retain_until bigint NOT NULL CHECK (retain_until BETWEEN 0 AND 9007199254740991),
    outcome varchar(32) CHECK (outcome IN ('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN')),
    tombstoned_at bigint CHECK (tombstoned_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, tool_id, namespace, namespace_identity, business_key_hash),
    CHECK ((namespace = 'TENANT') = (namespace_identity = '')),
    CHECK ((source = 'DEDUPE_WINDOW') = (dedupe_expires_at IS NOT NULL)),
    CHECK (dedupe_expires_at IS NULL OR dedupe_expires_at > created_at),
    CHECK (retain_until >= created_at),
    CHECK (tombstoned_at IS NULL OR outcome IS NOT NULL)
);
CREATE INDEX control_business_key_invocation ON control_business_keys (tenant_id, invocation_id);
CREATE INDEX control_business_key_retention ON control_business_keys (tenant_id, retain_until)
    WHERE tombstoned_at IS NULL;

-- 3. Task result payloads (plan.md §12.8): above the relay queue's 64 KiB cap, up to the 4 MiB ceiling.
CREATE TABLE control_invocation_results (
    tenant_id varchar(128) NOT NULL,
    invocation_id varchar(128) NOT NULL,
    fencing_nonce varchar(128) NOT NULL,
    result_digest char(71) NOT NULL CHECK (result_digest ~ '^sha256:[a-f0-9]{64}$'),
    byte_length integer NOT NULL CHECK (byte_length BETWEEN 0 AND 4194304),
    payload bytea,
    created_at bigint NOT NULL CHECK (created_at BETWEEN 0 AND 9007199254740991),
    retain_until bigint NOT NULL CHECK (retain_until BETWEEN 0 AND 9007199254740991),
    purged_at bigint CHECK (purged_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, invocation_id),
    FOREIGN KEY (tenant_id, invocation_id) REFERENCES control_enterprise_invocations (tenant_id, invocation_id),
    CHECK ((payload IS NULL) = (purged_at IS NOT NULL)),
    CHECK (payload IS NULL OR octet_length(payload) = byte_length),
    CHECK (retain_until >= created_at)
);
CREATE INDEX control_invocation_result_retention ON control_invocation_results (tenant_id, retain_until)
    WHERE purged_at IS NULL;

-- A result is written once; the only later change is the purge that replaces it with a tombstone.
CREATE FUNCTION control_invocation_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'invocation results are purged, never deleted'; END IF;
    IF OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL
       OR NEW.tenant_id <> OLD.tenant_id OR NEW.invocation_id <> OLD.invocation_id
       OR NEW.fencing_nonce <> OLD.fencing_nonce OR NEW.result_digest <> OLD.result_digest
       OR NEW.byte_length <> OLD.byte_length OR NEW.created_at <> OLD.created_at
       OR NEW.retain_until <> OLD.retain_until THEN
        RAISE EXCEPTION 'invocation result is immutable except for its purge';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_invocation_result_guard BEFORE UPDATE OR DELETE ON control_invocation_results
    FOR EACH ROW EXECUTE FUNCTION control_invocation_result_guard();

-- A reservation's owner and fingerprint never change. Its invocation changes only when the previous
-- invocation provably never executed (spec §4.4), and an expired dedupe row is replaced whole.
CREATE FUNCTION control_business_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'business keys are tombstoned, never deleted'; END IF;
    IF OLD.tombstoned_at IS NOT NULL OR NEW.source <> OLD.source THEN
        RAISE EXCEPTION 'business key reservation is immutable';
    END IF;
    IF OLD.source = 'DEDUPE_WINDOW' THEN
        IF NEW.invocation_id <> OLD.invocation_id AND OLD.dedupe_expires_at > NEW.created_at THEN
            RAISE EXCEPTION 'dedupe window still open';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.fingerprint <> OLD.fingerprint OR NEW.caller_agent_id <> OLD.caller_agent_id
       OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id THEN
        RAISE EXCEPTION 'business key reservation is immutable';
    END IF;
    IF (NEW.invocation_id <> OLD.invocation_id OR NEW.created_at <> OLD.created_at)
       AND NOT (COALESCE(OLD.outcome,'') IN ('EXPIRED','FAILED') AND NEW.outcome IS NULL) THEN
        RAISE EXCEPTION 'business key already bound to an invocation';
    END IF;
    IF NEW.invocation_id = OLD.invocation_id AND OLD.outcome IS NOT NULL
       AND NEW.outcome IS DISTINCT FROM OLD.outcome
       AND NOT (OLD.outcome = 'OUTCOME_UNKNOWN' AND NEW.outcome IN ('COMPLETED','FAILED')) THEN
        RAISE EXCEPTION 'business key outcome is final';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_business_key_guard BEFORE UPDATE OR DELETE ON control_business_keys
    FOR EACH ROW EXECUTE FUNCTION control_business_key_guard();

GRANT SELECT, INSERT, UPDATE ON control_business_keys, control_invocation_results TO toolgate_control_runtime;
