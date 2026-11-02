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
ALTER TABLE control_attempt_recovery_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_recovery_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_recovery_scopes AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_recovery_scopes AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_recovery_scopes TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_recovery_scope_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'recovery scope is immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n JOIN control_enterprise_invocations i
 ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
 WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.operation_id AND n.nonce=NEW.attempt_id
 AND n.purpose='EXECUTION' AND n.consumed_at IS NULL AND i.state='RESERVED'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id
 AND i.document::jsonb->'evaluation'->'context'->>'deviceId'=NEW.device_id)
 THEN RAISE EXCEPTION 'recovery scope must bind fresh Control admission'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER recovery_scope_guard BEFORE INSERT OR UPDATE OR DELETE ON control_attempt_recovery_scopes
 FOR EACH ROW EXECUTE FUNCTION control_recovery_scope_guard();

CREATE TABLE control_operation_recoveries (
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,
 from_revision BIGINT NOT NULL CHECK(from_revision>=1),
 to_revision BIGINT NOT NULL CHECK(to_revision=from_revision+1),
 operation_revision BIGINT NOT NULL CHECK(operation_revision>=1),
 previous_nonce TEXT,
 original_deadline BIGINT NOT NULL,
 proof TEXT NOT NULL CHECK(proof IN('NEVER_CONSUMED','QUALIFIED_BROKER_NO_WRITE')),
 attempt_ids TEXT NOT NULL CHECK(octet_length(attempt_ids)<=32768 AND jsonb_typeof(attempt_ids::jsonb)='array'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 document TEXT NOT NULL CHECK(octet_length(document)<=33554432),
 PRIMARY KEY(tenant_id,operation_id,from_revision),
 UNIQUE(tenant_id,operation_id,to_revision),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id,previous_nonce) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(original_deadline>created_at)
);
ALTER TABLE control_operation_recoveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_operation_recoveries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_operation_recoveries AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_operation_recoveries AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_operation_recoveries TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_operation_recovery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'operation recovery is immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o
 ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id
 AND i.revision=NEW.from_revision AND o.revision=NEW.operation_revision AND NOT o.legacy
 AND i.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN') AND o.state IN('FAILED','OUTCOME_UNKNOWN')
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id)
 AND i.expires_at=NEW.original_deadline AND i.expires_at>NEW.created_at
 AND i.document::jsonb->>'reservedNonce' IS NOT DISTINCT FROM NEW.previous_nonce
 AND NEW.document::jsonb->>'invocation'=i.document
 AND NEW.document::jsonb->'attemptIds'=NEW.attempt_ids::jsonb
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.attempt_ids::jsonb) j(value) WHERE jsonb_typeof(j.value)<>'string')
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_approvals a WHERE a.tenant_id=i.tenant_id AND a.invocation_id=i.invocation_id AND a.approval_type='OPERATION' AND (a.document::jsonb->>'state') IN('DENIED','REVOKED'))
 AND NOT EXISTS(SELECT 1 FROM control_business_keys k WHERE k.tenant_id=i.tenant_id AND k.invocation_id=i.invocation_id
 AND (k.tombstoned_at IS NOT NULL OR k.retain_until<=NEW.created_at))
 AND (i.document::jsonb->>'reservedNonce' IS NULL OR EXISTS(SELECT 1 FROM control_operation_attempts a
 WHERE a.tenant_id=i.tenant_id AND a.operation_id=i.invocation_id AND a.attempt_id=i.document::jsonb->>'reservedNonce'))
 AND (SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')<64
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a
 ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id
 AND n.purpose<>'SERVICE' AND (n.purpose<>'EXECUTION' OR NOT(a.attempt_id IS NOT NULL AND a.operation_id=n.invocation_id
 AND a.evaluation_digest=n.evaluation_digest AND a.authorization_epoch=n.authorization_epoch
 AND a.issued_at=n.issued_at AND a.expires_at=n.expires_at)
 OR (n.consumed_at IS NULL AND n.expires_at>NEW.created_at)
 OR (n.consumed_at IS NOT NULL AND n.consumed_at>NEW.created_at)))
 AND (SELECT count(*) FROM jsonb_array_elements_text(NEW.attempt_ids::jsonb) AS j(value))=(SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.attempt_ids::jsonb) AS j(value) WHERE j.value=n.nonce))
 AND (
 (NEW.proof='NEVER_CONSUMED' AND o.state='FAILED'
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id))
 OR (NEW.proof='QUALIFIED_BROKER_NO_WRITE'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state<>'RELEASED' OR n.consumed_at IS NULL))))
) THEN RAISE EXCEPTION 'positive complete Control recovery proof required'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_recovery_guard BEFORE INSERT OR UPDATE OR DELETE ON control_operation_recoveries
 FOR EACH ROW EXECUTE FUNCTION control_operation_recovery_guard();
CREATE FUNCTION control_operation_recovery_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION control_operation_recovery_capacity() FROM PUBLIC;
CREATE TRIGGER operation_recovery_capacity AFTER INSERT ON control_operation_recoveries
 FOR EACH ROW EXECUTE FUNCTION control_operation_recovery_capacity();

CREATE OR REPLACE FUNCTION control_invocation_recovery_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN','CANCELLED','SUCCEEDED','PARTIAL') AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') AND NOT(NEW.state='QUEUED' AND NEW.revision=OLD.revision+1 AND NEW.tenant_id=OLD.tenant_id AND NEW.invocation_id=OLD.invocation_id AND NEW.request_digest=OLD.request_digest AND NEW.created_at=OLD.created_at AND NEW.expires_at=OLD.expires_at AND NEW.authorization_epoch=OLD.authorization_epoch
 AND NEW.document::jsonb->>'state'=NEW.state AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND (NEW.document::jsonb->>'expiresAtUnixMs')::bigint=NEW.expires_at
 AND NEW.document::jsonb->'evaluation' IS NOT DISTINCT FROM OLD.document::jsonb->'evaluation'
 AND NEW.document::jsonb->>'reservedNonce' IS NULL AND NEW.document::jsonb->>'resultDigest' IS NULL
 AND NEW.document::jsonb->'completedResources'='[]'::jsonb
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id
 AND r.operation_id=OLD.invocation_id AND r.from_revision=OLD.revision AND r.to_revision=NEW.revision
 AND r.original_deadline=OLD.expires_at AND r.document::jsonb->>'invocation'=OLD.document)) THEN RAISE EXCEPTION 'terminal invocation requires positive recovery proof'; END IF; RETURN NEW; END $$;
CREATE TRIGGER invocation_recovery_guard BEFORE UPDATE ON control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION control_invocation_recovery_guard();

CREATE OR REPLACE FUNCTION control_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'operation is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED' AND NOT(NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.operation_id
 AND r.operation_revision=OLD.revision AND i.revision=r.to_revision AND i.state='QUEUED' AND i.expires_at=r.original_deadline))) THEN RAISE EXCEPTION 'operation identity or finality is immutable'; END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION control_attempt_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.purpose<>'SERVICE' AND (
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND ((n.consumed_at IS NULL AND n.expires_at>NEW.issued_at)
 OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)
 OR (n.consumed_at IS NOT NULL AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=n.tenant_id AND r.operation_id=n.invocation_id
 AND r.proof='QUALIFIED_BROKER_NO_WRITE' AND r.created_at<=NEW.issued_at AND r.to_revision<=i.revision AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(r.attempt_ids::jsonb) AS j(value) WHERE j.value=n.nonce))))))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED')) THEN RAISE EXCEPTION 'predecessor requires positive recovery coverage'; END IF; RETURN NEW; END $$;

CREATE OR REPLACE FUNCTION control_business_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'business keys are retained'; END IF;
 IF NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND NEW.tool_id IS NOT DISTINCT FROM OLD.tool_id AND NEW.namespace IS NOT DISTINCT FROM OLD.namespace AND NEW.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND NEW.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND NEW.source IS NOT DISTINCT FROM OLD.source AND NEW.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND NEW.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND NEW.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND NEW.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at AND NEW.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND NEW.retain_until IS NOT DISTINCT FROM OLD.retain_until AND NEW.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at
 AND NEW.outcome IS NULL AND OLD.outcome IN('FAILED','EXPIRED','OUTCOME_UNKNOWN')
 AND OLD.tombstoned_at IS NULL
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision=i.revision AND i.state='QUEUED' AND o.state='RUNNING'
 AND OLD.retain_until>r.created_at) THEN RETURN NEW; END IF;
 IF (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.tool_id IS DISTINCT FROM OLD.tool_id OR NEW.namespace IS DISTINCT FROM OLD.namespace OR NEW.namespace_identity IS DISTINCT FROM OLD.namespace_identity OR NEW.business_key_hash IS DISTINCT FROM OLD.business_key_hash) OR NEW.source<>OLD.source OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source<>'DEDUPE_WINDOW' AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.dedupe_expires_at IS DISTINCT FROM OLD.dedupe_expires_at OR NEW.retain_until IS DISTINCT FROM OLD.retain_until) OR
 ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at)
 AND NOT(COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL)))) OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source='DEDUPE_WINDOW' AND
 (OLD.dedupe_expires_at>NEW.created_at OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.outcome IS NOT NULL
 OR NEW.tombstoned_at IS NOT NULL OR NEW.retain_until<NEW.dedupe_expires_at
 OR NOT EXISTS(SELECT 1 FROM control_business_key_history h JOIN control_operations o
 ON o.tenant_id=h.tenant_id AND o.operation_id=h.invocation_id
 WHERE h.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND h.tool_id IS NOT DISTINCT FROM OLD.tool_id AND h.namespace IS NOT DISTINCT FROM OLD.namespace AND h.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND h.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND h.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND h.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND h.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND h.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND h.created_at IS NOT DISTINCT FROM OLD.created_at AND h.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND h.retain_until IS NOT DISTINCT FROM OLD.retain_until AND h.outcome IS NOT DISTINCT FROM OLD.outcome AND h.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at AND h.archived_at=NEW.created_at AND NOT o.legacy
 AND o.state IN('FAILED','SUCCEEDED')))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.dedupe_expires_at IS DISTINCT FROM OLD.dedupe_expires_at OR NEW.retain_until IS DISTINCT FROM OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS DISTINCT FROM OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS DISTINCT FROM OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND NOT o.legacy AND o.state IN('FAILED','SUCCEEDED')))))) THEN RAISE EXCEPTION 'business key identity, retention or recovery proof is immutable'; END IF;
 RETURN NEW;
END $$;


CREATE OR REPLACE FUNCTION control_execution_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.device_id<>OLD.device_id OR (NEW.fencing_token<>OLD.fencing_token AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.fencing_token
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision<=i.revision
 AND i.state='EXECUTING' AND i.document::jsonb->>'reservedNonce'=NEW.fencing_token
 AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND NEW.expires_at<=i.expires_at
 AND (r.document::jsonb->>'lease')::jsonb->>'fencingToken'=OLD.fencing_token AND (r.document::jsonb->>'lease')::jsonb->>'deviceId'=OLD.device_id
 AND ((r.document::jsonb->>'lease')::jsonb->>'expiresAt')::bigint=OLD.expires_at AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(r.attempt_ids::jsonb) AS j(value) WHERE j.value=OLD.fencing_token)))) THEN RAISE EXCEPTION 'execution identity requires archived recovery proof'; END IF; RETURN NEW; END $$;

CREATE FUNCTION control_original_deadline_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.expires_at<>OLD.expires_at AND
 (EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=OLD.tenant_id AND a.operation_id=OLD.invocation_id)
 OR EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id)) THEN RAISE EXCEPTION 'admitted operation deadline is immutable'; END IF;RETURN NEW;END $$;
CREATE TRIGGER original_deadline_guard BEFORE UPDATE ON control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION control_original_deadline_guard();
CREATE FUNCTION control_recovery_scope_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+octet_length(NEW.device_id||NEW.pool_id||NEW.image_digest||NEW.runtime_digest||NEW.descriptor_digest||NEW.profile_digest)
 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION control_recovery_scope_capacity() FROM PUBLIC;
CREATE TRIGGER recovery_scope_capacity AFTER INSERT ON control_attempt_recovery_scopes FOR EACH ROW EXECUTE FUNCTION control_recovery_scope_capacity();
