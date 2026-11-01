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
ALTER TABLE control_business_key_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_business_key_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_business_key_history AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_business_key_history AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_business_key_history TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_business_key_history_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM control_business_keys k JOIN control_operations o
 ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id
 WHERE k.tenant_id IS NOT DISTINCT FROM NEW.tenant_id AND k.tool_id IS NOT DISTINCT FROM NEW.tool_id AND k.namespace IS NOT DISTINCT FROM NEW.namespace AND k.namespace_identity IS NOT DISTINCT FROM NEW.namespace_identity AND k.business_key_hash IS NOT DISTINCT FROM NEW.business_key_hash AND k.invocation_id IS NOT DISTINCT FROM NEW.invocation_id AND k.fingerprint IS NOT DISTINCT FROM NEW.fingerprint AND k.caller_agent_id IS NOT DISTINCT FROM NEW.caller_agent_id AND k.caller_user_id IS NOT DISTINCT FROM NEW.caller_user_id AND k.created_at IS NOT DISTINCT FROM NEW.created_at AND k.dedupe_expires_at IS NOT DISTINCT FROM NEW.dedupe_expires_at AND k.retain_until IS NOT DISTINCT FROM NEW.retain_until AND k.outcome IS NOT DISTINCT FROM NEW.outcome AND k.tombstoned_at IS NOT DISTINCT FROM NEW.tombstoned_at AND k.source='DEDUPE_WINDOW' AND k.dedupe_expires_at<=NEW.archived_at
 AND NOT o.legacy AND o.state IN('FAILED','SUCCEEDED')) THEN RAISE EXCEPTION 'window archive requires an exact resolved reservation'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_business_key_history_insert BEFORE INSERT ON control_business_key_history FOR EACH ROW EXECUTE FUNCTION control_business_key_history_insert_guard();
CREATE TRIGGER control_business_key_history_immutable BEFORE UPDATE OR DELETE ON control_business_key_history FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE OR REPLACE FUNCTION control_business_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'business keys are retained'; END IF;
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
