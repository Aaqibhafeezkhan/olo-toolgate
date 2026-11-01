-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_business_key_history (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 tool_id TEXT NOT NULL,
 namespace TEXT NOT NULL CHECK(namespace IN('AGENT','DELEGATED_USER','TENANT')),
 namespace_identity TEXT NOT NULL,
 business_key_hash TEXT NOT NULL CHECK(length(business_key_hash)=64),
 invocation_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL CHECK(length(fingerprint)=71),
 caller_agent_id TEXT NOT NULL,
 caller_user_id TEXT,
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 dedupe_expires_at BIGINT NOT NULL CHECK(dedupe_expires_at>created_at),
 retain_until BIGINT NOT NULL CHECK(retain_until>=dedupe_expires_at),
 outcome TEXT NOT NULL CHECK(outcome IN('COMPLETED','FAILED','REJECTED','EXPIRED','CANCELLED')),
 tombstoned_at BIGINT,
 archived_at BIGINT NOT NULL CHECK(archived_at>=dedupe_expires_at AND archived_at<=9007199254740991),
 PRIMARY KEY(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES control_operations(tenant_id,operation_id),
 CHECK((namespace='TENANT')=(namespace_identity=''))
);
-- statement
CREATE TRIGGER control_business_key_history_insert BEFORE INSERT ON control_business_key_history WHEN NOT EXISTS(SELECT 1 FROM control_business_keys k JOIN control_operations o
 ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id
 WHERE k.tenant_id IS NEW.tenant_id AND k.tool_id IS NEW.tool_id AND k.namespace IS NEW.namespace AND k.namespace_identity IS NEW.namespace_identity AND k.business_key_hash IS NEW.business_key_hash AND k.invocation_id IS NEW.invocation_id AND k.fingerprint IS NEW.fingerprint AND k.caller_agent_id IS NEW.caller_agent_id AND k.caller_user_id IS NEW.caller_user_id AND k.created_at IS NEW.created_at AND k.dedupe_expires_at IS NEW.dedupe_expires_at AND k.retain_until IS NEW.retain_until AND k.outcome IS NEW.outcome AND k.tombstoned_at IS NEW.tombstoned_at AND k.source='DEDUPE_WINDOW' AND k.dedupe_expires_at<=NEW.archived_at
 AND o.legacy=0 AND o.state IN('FAILED','SUCCEEDED')) BEGIN SELECT RAISE(ABORT,'window archive requires an exact resolved reservation'); END;
-- statement
CREATE TRIGGER control_business_key_history_update BEFORE UPDATE ON control_business_key_history BEGIN SELECT RAISE(ABORT,'business key history is immutable'); END;
-- statement
CREATE TRIGGER control_business_key_history_delete BEFORE DELETE ON control_business_key_history BEGIN SELECT RAISE(ABORT,'business key history is immutable'); END;
-- statement
DROP TRIGGER control_business_key_update;
-- statement
CREATE TRIGGER control_business_key_update BEFORE UPDATE ON control_business_keys WHEN (NEW.tenant_id IS NOT OLD.tenant_id OR NEW.tool_id IS NOT OLD.tool_id OR NEW.namespace IS NOT OLD.namespace OR NEW.namespace_identity IS NOT OLD.namespace_identity OR NEW.business_key_hash IS NOT OLD.business_key_hash) OR NEW.source<>OLD.source OR
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
 AND o.operation_id=OLD.invocation_id AND o.legacy=0 AND o.state IN('FAILED','SUCCEEDED')))))) BEGIN SELECT RAISE(ABORT,'business key identity, retention or recovery proof is immutable'); END;
