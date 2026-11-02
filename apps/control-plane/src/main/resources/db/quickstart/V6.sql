-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_attempt_recovery_scopes (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 attempt_id TEXT NOT NULL,
 device_id TEXT NOT NULL,
 pool_id TEXT NOT NULL,
 image_digest TEXT NOT NULL CHECK(length(image_digest)=71),
 runtime_digest TEXT NOT NULL CHECK(length(runtime_digest)=71),
 descriptor_digest TEXT NOT NULL CHECK(length(descriptor_digest)=71),
 profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71),
 profile_revision BIGINT NOT NULL CHECK(profile_revision BETWEEN 1 AND 9007199254740991),
 PRIMARY KEY(tenant_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
-- statement
CREATE TRIGGER recovery_scope_insert BEFORE INSERT ON control_attempt_recovery_scopes
 WHEN NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n JOIN control_enterprise_invocations i
 ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
 WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.operation_id AND n.nonce=NEW.attempt_id
 AND n.purpose='EXECUTION' AND n.consumed_at IS NULL AND i.state='RESERVED'
 AND json_extract(i.document,'$.reservedNonce')=NEW.attempt_id
 AND json_extract(i.document,'$.evaluation.context.deviceId')=NEW.device_id)
 BEGIN SELECT RAISE(ABORT,'recovery scope must bind fresh Control admission'); END;
-- statement
CREATE TRIGGER recovery_scope_update BEFORE UPDATE ON control_attempt_recovery_scopes
 BEGIN SELECT RAISE(ABORT,'recovery scope is immutable'); END;
-- statement
CREATE TRIGGER recovery_scope_delete BEFORE DELETE ON control_attempt_recovery_scopes
 BEGIN SELECT RAISE(ABORT,'recovery scope is retained'); END;
-- statement
CREATE TABLE control_operation_recoveries (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 from_revision BIGINT NOT NULL CHECK(from_revision>=1),
 to_revision BIGINT NOT NULL CHECK(to_revision=from_revision+1),
 operation_revision BIGINT NOT NULL CHECK(operation_revision>=1),
 previous_nonce TEXT,
 original_deadline BIGINT NOT NULL,
 proof TEXT NOT NULL CHECK(proof IN('NEVER_CONSUMED','QUALIFIED_BROKER_NO_WRITE')),
 attempt_ids TEXT NOT NULL CHECK(length(CAST(attempt_ids AS BLOB))<=32768 AND json_valid(attempt_ids) AND json_type(attempt_ids)='array'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=33554432),
 PRIMARY KEY(tenant_id,operation_id,from_revision),
 UNIQUE(tenant_id,operation_id,to_revision),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id,previous_nonce) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(original_deadline>created_at)
);
-- statement
CREATE TRIGGER operation_recovery_insert BEFORE INSERT ON control_operation_recoveries
 WHEN NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o
 ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id
 AND i.revision=NEW.from_revision AND o.revision=NEW.operation_revision AND o.legacy=0
 AND i.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN') AND o.state IN('FAILED','OUTCOME_UNKNOWN')
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id)
 AND i.expires_at=NEW.original_deadline AND i.expires_at>NEW.created_at
 AND json_extract(i.document,'$.reservedNonce') IS NEW.previous_nonce
 AND json_extract(NEW.document,'$.invocation')=i.document
 AND json_extract(NEW.document,'$.attemptIds')=json(NEW.attempt_ids)
 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.attempt_ids) j WHERE j.type<>'text')
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_approvals a WHERE a.tenant_id=i.tenant_id AND a.invocation_id=i.invocation_id AND a.approval_type='OPERATION' AND json_extract(a.document,'$.state') IN('DENIED','REVOKED'))
 AND NOT EXISTS(SELECT 1 FROM control_business_keys k WHERE k.tenant_id=i.tenant_id AND k.invocation_id=i.invocation_id
 AND (k.tombstoned_at IS NOT NULL OR k.retain_until<=NEW.created_at))
 AND (json_extract(i.document,'$.reservedNonce') IS NULL OR EXISTS(SELECT 1 FROM control_operation_attempts a
 WHERE a.tenant_id=i.tenant_id AND a.operation_id=i.invocation_id AND a.attempt_id=json_extract(i.document,'$.reservedNonce')))
 AND (SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')<64
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a
 ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id
 AND n.purpose<>'SERVICE' AND (n.purpose<>'EXECUTION' OR NOT(a.attempt_id IS NOT NULL AND a.operation_id=n.invocation_id
 AND a.evaluation_digest=n.evaluation_digest AND a.authorization_epoch=n.authorization_epoch
 AND a.issued_at=n.issued_at AND a.expires_at=n.expires_at)
 OR (n.consumed_at IS NULL AND n.expires_at>NEW.created_at)
 OR (n.consumed_at IS NOT NULL AND n.consumed_at>NEW.created_at)))
 AND (SELECT count(*) FROM json_each(NEW.attempt_ids) AS j)=(SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND NOT EXISTS(SELECT 1 FROM json_each(NEW.attempt_ids) AS j WHERE j.value=n.nonce))
 AND (
 (NEW.proof='NEVER_CONSUMED' AND o.state='FAILED'
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id))
 OR (NEW.proof='QUALIFIED_BROKER_NO_WRITE'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=json_extract(i.document,'$.evaluation.context.deviceId')))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state<>'RELEASED' OR n.consumed_at IS NULL))))
)
 BEGIN SELECT RAISE(ABORT,'positive complete Control recovery proof required'); END;
-- statement
CREATE TRIGGER operation_recovery_update BEFORE UPDATE ON control_operation_recoveries
 BEGIN SELECT RAISE(ABORT,'operation recovery is immutable'); END;
-- statement
CREATE TRIGGER operation_recovery_delete BEFORE DELETE ON control_operation_recoveries
 BEGIN SELECT RAISE(ABORT,'operation recovery is retained'); END;
-- statement
CREATE TRIGGER operation_recovery_capacity AFTER INSERT ON control_operation_recoveries BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM control_tenant_capacity WHERE tenant_id=NEW.tenant_id)
 THEN RAISE(ABORT,'tenant capacity unavailable') END;
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id;
END;
-- statement
DROP TRIGGER IF EXISTS invocation_recovery_guard;
-- statement
CREATE TRIGGER invocation_recovery_guard BEFORE UPDATE ON control_enterprise_invocations WHEN OLD.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN','CANCELLED','SUCCEEDED','PARTIAL') AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') AND NOT(NEW.state='QUEUED' AND NEW.revision=OLD.revision+1 AND NEW.tenant_id=OLD.tenant_id AND NEW.invocation_id=OLD.invocation_id AND NEW.request_digest=OLD.request_digest AND NEW.created_at=OLD.created_at AND NEW.expires_at=OLD.expires_at AND NEW.authorization_epoch=OLD.authorization_epoch
 AND json_extract(NEW.document,'$.state')=NEW.state AND json_extract(NEW.document,'$.revision')=NEW.revision AND json_extract(NEW.document,'$.expiresAtUnixMs')=NEW.expires_at
 AND json_extract(NEW.document,'$.evaluation') IS json_extract(OLD.document,'$.evaluation')
 AND json_extract(NEW.document,'$.reservedNonce') IS NULL AND json_extract(NEW.document,'$.resultDigest') IS NULL
 AND json_extract(NEW.document,'$.completedResources')='[]'
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id
 AND r.operation_id=OLD.invocation_id AND r.from_revision=OLD.revision AND r.to_revision=NEW.revision
 AND r.original_deadline=OLD.expires_at AND json_extract(r.document,'$.invocation')=OLD.document)) BEGIN SELECT RAISE(ABORT,'terminal invocation requires positive recovery proof'); END;
-- statement
DROP TRIGGER control_operations_guard;
-- statement
CREATE TRIGGER control_operations_guard BEFORE UPDATE ON control_operations WHEN
 NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED' AND NOT(NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.operation_id
 AND r.operation_revision=OLD.revision AND i.revision=r.to_revision AND i.state='QUEUED' AND i.expires_at=r.original_deadline)))
 BEGIN SELECT RAISE(ABORT,'operation identity or finality is immutable'); END;
-- statement
DROP TRIGGER control_attempt_reservation_guard;
-- statement
CREATE TRIGGER control_attempt_reservation_guard BEFORE INSERT ON control_enterprise_nonces WHEN NEW.purpose<>'SERVICE' AND (
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND ((n.consumed_at IS NULL AND n.expires_at>NEW.issued_at)
 OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)
 OR (n.consumed_at IS NOT NULL AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=n.tenant_id AND r.operation_id=n.invocation_id
 AND r.proof='QUALIFIED_BROKER_NO_WRITE' AND r.created_at<=NEW.issued_at AND r.to_revision<=i.revision AND EXISTS(SELECT 1 FROM json_each(r.attempt_ids) AS j WHERE j.value=n.nonce))))))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED')) BEGIN SELECT RAISE(ABORT,'predecessor requires positive recovery coverage'); END;
-- statement
DROP TRIGGER control_business_key_update;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN ((NEW.tenant_id IS NOT OLD.tenant_id OR NEW.tool_id IS NOT OLD.tool_id OR NEW.namespace IS NOT OLD.namespace OR NEW.namespace_identity IS NOT OLD.namespace_identity OR NEW.business_key_hash IS NOT OLD.business_key_hash) OR NEW.source<>OLD.source OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source<>'DEDUPE_WINDOW' AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.dedupe_expires_at IS NOT OLD.dedupe_expires_at OR NEW.retain_until IS NOT OLD.retain_until) OR
 ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at)
 AND NOT(COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL)))) OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source='DEDUPE_WINDOW' AND
 (OLD.dedupe_expires_at>NEW.created_at OR NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.outcome IS NOT NULL
 OR NEW.tombstoned_at IS NOT NULL OR NEW.retain_until<NEW.dedupe_expires_at
 OR NOT EXISTS(SELECT 1 FROM control_business_key_history h JOIN control_operations o
 ON o.tenant_id=h.tenant_id AND o.operation_id=h.invocation_id
 WHERE h.tenant_id IS OLD.tenant_id AND h.tool_id IS OLD.tool_id AND h.namespace IS OLD.namespace AND h.namespace_identity IS OLD.namespace_identity AND h.business_key_hash IS OLD.business_key_hash AND h.invocation_id IS OLD.invocation_id AND h.fingerprint IS OLD.fingerprint AND h.caller_agent_id IS OLD.caller_agent_id AND h.caller_user_id IS OLD.caller_user_id AND h.created_at IS OLD.created_at AND h.dedupe_expires_at IS OLD.dedupe_expires_at AND h.retain_until IS OLD.retain_until AND h.outcome IS OLD.outcome AND h.tombstoned_at IS OLD.tombstoned_at AND h.archived_at=NEW.created_at AND o.legacy=0
 AND o.state IN('FAILED','SUCCEEDED')))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS NOT OLD.fingerprint OR NEW.caller_agent_id IS NOT OLD.caller_agent_id OR NEW.caller_user_id IS NOT OLD.caller_user_id OR NEW.dedupe_expires_at IS NOT OLD.dedupe_expires_at OR NEW.retain_until IS NOT OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS NOT OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS NOT OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND o.legacy=0 AND o.state IN('FAILED','SUCCEEDED'))))))) AND NOT(NEW.tenant_id IS OLD.tenant_id AND NEW.tool_id IS OLD.tool_id AND NEW.namespace IS OLD.namespace AND NEW.namespace_identity IS OLD.namespace_identity AND NEW.business_key_hash IS OLD.business_key_hash AND NEW.source IS OLD.source AND NEW.fingerprint IS OLD.fingerprint AND NEW.caller_agent_id IS OLD.caller_agent_id AND NEW.caller_user_id IS OLD.caller_user_id AND NEW.invocation_id IS OLD.invocation_id AND NEW.created_at IS OLD.created_at AND NEW.dedupe_expires_at IS OLD.dedupe_expires_at AND NEW.retain_until IS OLD.retain_until AND NEW.tombstoned_at IS OLD.tombstoned_at
 AND NEW.outcome IS NULL AND OLD.outcome IN('FAILED','EXPIRED','OUTCOME_UNKNOWN')
 AND OLD.tombstoned_at IS NULL
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision=i.revision AND i.state='QUEUED' AND o.state='RUNNING'
 AND OLD.retain_until>r.created_at)) BEGIN SELECT RAISE(ABORT,'business key identity, retention or recovery proof is immutable'); END;

-- statement
DROP TRIGGER IF EXISTS control_execution_leases_identity_immutable;
-- statement
CREATE TRIGGER control_execution_leases_identity_immutable BEFORE UPDATE ON control_execution_leases WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.device_id<>OLD.device_id OR (NEW.fencing_token<>OLD.fencing_token AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.fencing_token
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision<=i.revision
 AND i.state='EXECUTING' AND json_extract(i.document,'$.reservedNonce')=NEW.fencing_token
 AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND NEW.expires_at<=i.expires_at
 AND json_extract(json_extract(r.document,'$.lease'),'$.fencingToken')=OLD.fencing_token AND json_extract(json_extract(r.document,'$.lease'),'$.deviceId')=OLD.device_id
 AND json_extract(json_extract(r.document,'$.lease'),'$.expiresAt')=OLD.expires_at AND EXISTS(SELECT 1 FROM json_each(r.attempt_ids) AS j WHERE j.value=OLD.fencing_token)))) BEGIN SELECT RAISE(ABORT,'execution identity requires archived recovery proof'); END;

-- statement
CREATE TRIGGER original_deadline_guard BEFORE UPDATE ON control_enterprise_invocations WHEN NEW.expires_at<>OLD.expires_at AND
 (EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=OLD.tenant_id AND a.operation_id=OLD.invocation_id)
 OR EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id))
 BEGIN SELECT RAISE(ABORT,'admitted operation deadline is immutable');END;
-- statement
CREATE TRIGGER recovery_scope_capacity AFTER INSERT ON control_attempt_recovery_scopes BEGIN
 UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+length(CAST(NEW.device_id||NEW.pool_id||NEW.image_digest||NEW.runtime_digest||NEW.descriptor_digest||NEW.profile_digest AS BLOB))
 WHERE tenant_id=NEW.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END;
END;
