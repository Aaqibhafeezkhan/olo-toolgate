-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- SPECIFICATION (gate D2). Ships unchanged as db/quickstart/V11.sql in M1, with the runner cap in
-- SqliteState raised from 10 to 11. Same state as Flyway V18__invocation_tasks_v2.sql; SQLite has no
-- multi-column ALTER constraints, so the cross-column checks are triggers.
ALTER TABLE control_enterprise_invocations ADD COLUMN tool_id TEXT CHECK(length(tool_id) BETWEEN 1 AND 256);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN package_id TEXT CHECK(length(package_id) BETWEEN 1 AND 128);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN package_digest TEXT CHECK(length(package_digest)=71 AND substr(package_digest,1,7)='sha256:' AND substr(package_digest,8) NOT GLOB '*[^0-9a-f]*');
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN pool_id TEXT CHECK(length(pool_id) BETWEEN 1 AND 128);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN runtime_digest TEXT CHECK(length(runtime_digest)=71 AND substr(runtime_digest,1,7)='sha256:' AND substr(runtime_digest,8) NOT GLOB '*[^0-9a-f]*');
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN effect_safety TEXT CHECK(effect_safety IN('READ_ONLY','IDEMPOTENT','NON_IDEMPOTENT','UNKNOWN'));
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN requires_execution_ledger INTEGER CHECK(requires_execution_ledger IN(0,1));
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN lease_holder TEXT CHECK(length(lease_holder) BETWEEN 1 AND 256);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN lease_expires_at INTEGER CHECK(lease_expires_at BETWEEN 0 AND 9007199254740991);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN cancel_requested_at INTEGER CHECK(cancel_requested_at BETWEEN 0 AND 9007199254740991);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN not_started INTEGER NOT NULL DEFAULT 0 CHECK(not_started IN(0,1));
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_id TEXT CHECK(length(task_id) BETWEEN 22 AND 64 AND task_id NOT GLOB '*[^A-Za-z0-9_-]*');
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_state TEXT;
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_owner_digest TEXT CHECK(length(task_owner_digest)=64 AND task_owner_digest NOT GLOB '*[^0-9a-f]*');
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_retain_until INTEGER CHECK(task_retain_until BETWEEN 0 AND 9007199254740991);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_input_deadline_at INTEGER CHECK(task_input_deadline_at BETWEEN 0 AND 9007199254740991);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_last_polled_at INTEGER CHECK(task_last_polled_at BETWEEN 0 AND 9007199254740991);
-- statement
ALTER TABLE control_enterprise_invocations ADD COLUMN task_document TEXT CHECK(length(CAST(task_document AS BLOB))<=32768);
-- statement
CREATE TRIGGER control_invocation_v2_insert BEFORE INSERT ON control_enterprise_invocations WHEN NOT ((NEW.requires_execution_ledger IS NULL OR (NEW.tool_id IS NOT NULL AND NEW.package_digest IS NOT NULL AND NEW.effect_safety IS NOT NULL AND NEW.requires_execution_ledger=(NEW.effect_safety IN('NON_IDEMPOTENT','UNKNOWN'))))
 AND (NEW.task_id IS NULL)=(NEW.task_state IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_owner_digest IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_retain_until IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_document IS NULL) AND (NEW.task_id IS NULL OR NEW.requires_execution_ledger IS NOT NULL)
 AND (NEW.task_state IS NULL
  OR (NEW.state='PENDING_APPROVAL' AND NEW.task_state='AWAITING_APPROVAL')
  OR (NEW.state='QUEUED' AND NEW.task_state='QUEUED')
  OR (NEW.state IN('RESERVED','DISPATCHED') AND NEW.task_state IN('DISPATCHED','CANCEL_REQUESTED'))
  OR (NEW.state='EXECUTING' AND NEW.task_state IN('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
  OR (NEW.state='SUCCEEDED' AND NEW.task_state='COMPLETED')
  OR (NEW.state IN('FAILED','PARTIAL') AND NEW.task_state='FAILED')
  OR (NEW.state='CANCELLED' AND NEW.task_state IN('CANCELLED','REJECTED'))
  OR (NEW.state='EXPIRED' AND NEW.task_state='EXPIRED')
  OR (NEW.state='OUTCOME_UNKNOWN' AND NEW.task_state='OUTCOME_UNKNOWN'))
 AND (NEW.lease_holder IS NULL)=(NEW.lease_expires_at IS NULL) AND (NEW.lease_holder IS NULL OR NEW.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED'))
 AND (NEW.task_state IS NOT 'CANCEL_REQUESTED' OR NEW.cancel_requested_at IS NOT NULL) AND (NEW.not_started=0 OR NEW.state='FAILED')) BEGIN SELECT RAISE(ABORT,'invalid v2 invocation state'); END;
-- statement
CREATE TRIGGER control_invocation_v2_update BEFORE UPDATE ON control_enterprise_invocations WHEN NOT ((NEW.requires_execution_ledger IS NULL OR (NEW.tool_id IS NOT NULL AND NEW.package_digest IS NOT NULL AND NEW.effect_safety IS NOT NULL AND NEW.requires_execution_ledger=(NEW.effect_safety IN('NON_IDEMPOTENT','UNKNOWN'))))
 AND (NEW.task_id IS NULL)=(NEW.task_state IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_owner_digest IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_retain_until IS NULL) AND (NEW.task_id IS NULL)=(NEW.task_document IS NULL) AND (NEW.task_id IS NULL OR NEW.requires_execution_ledger IS NOT NULL)
 AND (NEW.task_state IS NULL
  OR (NEW.state='PENDING_APPROVAL' AND NEW.task_state='AWAITING_APPROVAL')
  OR (NEW.state='QUEUED' AND NEW.task_state='QUEUED')
  OR (NEW.state IN('RESERVED','DISPATCHED') AND NEW.task_state IN('DISPATCHED','CANCEL_REQUESTED'))
  OR (NEW.state='EXECUTING' AND NEW.task_state IN('RUNNING','INPUT_REQUIRED','CANCEL_REQUESTED'))
  OR (NEW.state='SUCCEEDED' AND NEW.task_state='COMPLETED')
  OR (NEW.state IN('FAILED','PARTIAL') AND NEW.task_state='FAILED')
  OR (NEW.state='CANCELLED' AND NEW.task_state IN('CANCELLED','REJECTED'))
  OR (NEW.state='EXPIRED' AND NEW.task_state='EXPIRED')
  OR (NEW.state='OUTCOME_UNKNOWN' AND NEW.task_state='OUTCOME_UNKNOWN'))
 AND (NEW.lease_holder IS NULL)=(NEW.lease_expires_at IS NULL) AND (NEW.lease_holder IS NULL OR NEW.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED'))
 AND (NEW.task_state IS NOT 'CANCEL_REQUESTED' OR NEW.cancel_requested_at IS NOT NULL) AND (NEW.not_started=0 OR NEW.state='FAILED')) BEGIN SELECT RAISE(ABORT,'invalid v2 invocation state'); END;
-- statement
CREATE UNIQUE INDEX control_invocation_task ON control_enterprise_invocations(tenant_id,task_id) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_invocation_lease ON control_enterprise_invocations(tenant_id,lease_expires_at) WHERE state='EXECUTING';
-- statement
CREATE INDEX control_invocation_task_retention ON control_enterprise_invocations(tenant_id,task_retain_until) WHERE task_id IS NOT NULL;
-- statement
CREATE INDEX control_invocation_input_deadline ON control_enterprise_invocations(tenant_id,task_input_deadline_at) WHERE task_state IN('AWAITING_APPROVAL','INPUT_REQUIRED');
-- statement
CREATE INDEX control_invocation_active_package ON control_enterprise_invocations(tenant_id,package_digest) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE INDEX control_invocation_active_tool ON control_enterprise_invocations(tenant_id,tool_id) WHERE state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING');
-- statement
CREATE TABLE control_business_keys(tenant_id TEXT NOT NULL,tool_id TEXT NOT NULL CHECK(length(tool_id) BETWEEN 1 AND 256),namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),namespace_identity TEXT NOT NULL CHECK(length(namespace_identity)<=128),business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64 AND business_key_hash NOT GLOB '*[^0-9a-f]*'),source TEXT NOT NULL CHECK(source IN('HEADER','ARGUMENT','DEDUPE_WINDOW')),fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71 AND substr(fingerprint,1,7)='sha256:' AND substr(fingerprint,8) NOT GLOB '*[^0-9a-f]*'),caller_agent_id TEXT NOT NULL,caller_user_id TEXT,invocation_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),dedupe_expires_at INTEGER CHECK(dedupe_expires_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),outcome TEXT CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN')),tombstoned_at INTEGER CHECK(tombstoned_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash),CHECK((namespace='TENANT')=(namespace_identity='')),CHECK((source='DEDUPE_WINDOW')=(dedupe_expires_at IS NOT NULL)),CHECK(dedupe_expires_at IS NULL OR dedupe_expires_at>created_at),CHECK(retain_until>=created_at),CHECK(tombstoned_at IS NULL OR outcome IS NOT NULL));
-- statement
CREATE INDEX control_business_key_invocation ON control_business_keys(tenant_id,invocation_id);
-- statement
CREATE INDEX control_business_key_retention ON control_business_keys(tenant_id,retain_until) WHERE tombstoned_at IS NULL;
-- statement
CREATE TABLE control_invocation_results(tenant_id TEXT NOT NULL,invocation_id TEXT NOT NULL,fencing_nonce TEXT NOT NULL,result_digest TEXT NOT NULL CHECK(length(result_digest)=71 AND substr(result_digest,1,7)='sha256:' AND substr(result_digest,8) NOT GLOB '*[^0-9a-f]*'),byte_length INTEGER NOT NULL CHECK(byte_length BETWEEN 0 AND 4194304),payload BLOB,created_at INTEGER NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),retain_until INTEGER NOT NULL CHECK(retain_until BETWEEN 0 AND 9007199254740991),purged_at INTEGER CHECK(purged_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,invocation_id),FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id),CHECK((payload IS NULL)=(purged_at IS NOT NULL)),CHECK(payload IS NULL OR length(payload)=byte_length),CHECK(retain_until>=created_at));
-- statement
CREATE INDEX control_invocation_result_retention ON control_invocation_results(tenant_id,retain_until) WHERE purged_at IS NULL;
-- statement
CREATE TRIGGER control_invocation_result_delete BEFORE DELETE ON control_invocation_results BEGIN SELECT RAISE(ABORT,'invocation results are purged, never deleted'); END;
-- statement
CREATE TRIGGER control_invocation_result_update BEFORE UPDATE ON control_invocation_results WHEN OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL OR NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.fencing_nonce<>OLD.fencing_nonce OR NEW.result_digest<>OLD.result_digest OR NEW.byte_length<>OLD.byte_length OR NEW.created_at<>OLD.created_at OR NEW.retain_until<>OLD.retain_until BEGIN SELECT RAISE(ABORT,'invocation result is immutable except for its purge'); END;
-- statement
CREATE TRIGGER control_business_key_delete BEFORE DELETE ON control_business_keys BEGIN SELECT RAISE(ABORT,'business keys are tombstoned, never deleted'); END;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN OLD.tombstoned_at IS NOT NULL OR NEW.source<>OLD.source
 OR (OLD.source='DEDUPE_WINDOW' AND NEW.invocation_id<>OLD.invocation_id AND OLD.dedupe_expires_at>NEW.created_at)
 OR (OLD.source<>'DEDUPE_WINDOW' AND (NEW.fingerprint<>OLD.fingerprint OR NEW.caller_agent_id<>OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id
   OR ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at) AND NOT (COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL))
   OR (NEW.invocation_id=OLD.invocation_id AND OLD.outcome IS NOT NULL AND NEW.outcome IS NOT OLD.outcome AND NOT (OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED')))))
 BEGIN SELECT RAISE(ABORT,'business key reservation is immutable'); END;
