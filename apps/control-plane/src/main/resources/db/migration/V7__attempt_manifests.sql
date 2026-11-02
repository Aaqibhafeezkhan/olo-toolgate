-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0

ALTER TABLE control_business_keys DROP CONSTRAINT control_business_keys_outcome_check;
ALTER TABLE control_business_keys ADD CONSTRAINT control_business_keys_outcome_check
 CHECK(outcome IN('COMPLETED','PARTIAL','FAILED','REJECTED','EXPIRED','CANCELLED','OUTCOME_UNKNOWN'));
-- Dynamic attempt manifests: membership follows authorization; closure grants no execution authority.
ALTER TABLE control_effect_generations NO FORCE ROW LEVEL SECURITY;
ALTER TABLE control_tenant_capacity NO FORCE ROW LEVEL SECURITY;
CREATE TABLE control_attempt_effects (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id varchar(128) NOT NULL, attempt_id varchar(128) NOT NULL, effect_id varchar(128) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id,attempt_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,effect_id) REFERENCES control_effects(tenant_id,operation_id,effect_id)
);
INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 SELECT DISTINCT tenant_id,operation_id,attempt_id,effect_id FROM control_effect_generations;
UPDATE control_tenant_capacity SET stored_rows=stored_rows+(SELECT count(*) FROM control_attempt_effects m WHERE m.tenant_id=control_tenant_capacity.tenant_id),
 stored_bytes=stored_bytes+COALESCE((SELECT sum(length(m.effect_id)) FROM control_attempt_effects m WHERE m.tenant_id=control_tenant_capacity.tenant_id),0);
ALTER TABLE control_effect_generations FORCE ROW LEVEL SECURITY;
ALTER TABLE control_tenant_capacity FORCE ROW LEVEL SECURITY;
CREATE TABLE control_attempt_manifest_seals (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id varchar(128) NOT NULL, attempt_id varchar(128) NOT NULL,
 invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 effect_count BIGINT NOT NULL CHECK(effect_count BETWEEN 0 AND 4096),
 manifest_digest TEXT NOT NULL CHECK(manifest_digest ~ '^[a-f0-9]{64}$'),
 document TEXT NOT NULL CHECK(octet_length(document)<=1048576 AND jsonb_typeof(document::jsonb)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
CREATE FUNCTION control_effect_manifest_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'EFFECT_MANIFEST_SEALED or inactive execution authority'; END IF; RETURN NEW; END $$;
CREATE TRIGGER effect_manifest_guard BEFORE INSERT ON control_effect_generations FOR EACH ROW EXECUTE FUNCTION control_effect_manifest_guard();
CREATE FUNCTION control_attempt_effect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND g.attempt_id=NEW.attempt_id AND g.effect_id=NEW.effect_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'manifest membership requires open admitted generation'; END IF; RETURN NEW; END $$;
CREATE TRIGGER attempt_effect_guard BEFORE INSERT ON control_attempt_effects FOR EACH ROW EXECUTE FUNCTION control_attempt_effect_guard();
CREATE FUNCTION control_attempt_manifest_seal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_operation_attempts a JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE a.tenant_id=NEW.tenant_id AND a.operation_id=NEW.operation_id AND a.attempt_id=NEW.attempt_id
 AND NEW.created_at>=a.issued_at AND i.revision=NEW.invocation_revision
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(i.document::jsonb->>'reservedNonce','')<>NEW.attempt_id))
 AND NEW.document::jsonb->>'format'='toolgate-attempt-manifest-v1'
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id
 AND jsonb_typeof(NEW.document::jsonb->'effects')='array' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.document::jsonb->'effects') j(value) WHERE jsonb_typeof(j.value)<>'string')
 AND NEW.effect_count=(SELECT count(*) FROM jsonb_array_elements_text(NEW.document::jsonb->'effects') j(value))
 AND NEW.effect_count=(SELECT count(*) FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.document::jsonb->'effects') j(value) WHERE j.value=m.effect_id)),false)) THEN RAISE EXCEPTION 'manifest sealing requires closed authority and exact membership'; END IF; RETURN NEW; END $$;
CREATE TRIGGER attempt_manifest_seal_guard BEFORE INSERT ON control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION control_attempt_manifest_seal_guard();
CREATE FUNCTION control_effect_manifest_member() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 VALUES(NEW.tenant_id,NEW.operation_id,NEW.attempt_id,NEW.effect_id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;
CREATE TRIGGER effect_manifest_member AFTER INSERT ON control_effect_generations FOR EACH ROW EXECUTE FUNCTION control_effect_manifest_member();
ALTER TABLE control_attempt_effects ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_effects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_effects AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_effects AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_effects TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER attempt_effects_immutable BEFORE UPDATE OR DELETE ON control_attempt_effects FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_attempt_effects_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(NEW.effect_id)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_attempt_effects_capacity() FROM PUBLIC;
CREATE TRIGGER attempt_effects_capacity AFTER INSERT ON control_attempt_effects FOR EACH ROW EXECUTE FUNCTION control_attempt_effects_capacity();
ALTER TABLE control_attempt_manifest_seals ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_manifest_seals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_manifest_seals AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_manifest_seals AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_manifest_seals TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER attempt_manifest_seals_immutable BEFORE UPDATE OR DELETE ON control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_attempt_manifest_seals_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_attempt_manifest_seals_capacity() FROM PUBLIC;
CREATE TRIGGER attempt_manifest_seals_capacity AFTER INSERT ON control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION control_attempt_manifest_seals_capacity();

CREATE FUNCTION control_effect_manifest_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state<>NEW.state AND OLD.state IN('AUTHORIZED','SENDING') AND NEW.state IN('SENDING','CONSUMED') THEN
  PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
   AND o.operation_id=NEW.operation_id FOR UPDATE;
  IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'EFFECT_MANIFEST_SEALED or inactive execution authority'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER effect_manifest_transition_guard BEFORE UPDATE ON control_effect_generations
 FOR EACH ROW EXECUTE FUNCTION control_effect_manifest_transition_guard();

CREATE TABLE control_attempt_settlements (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id), operation_id varchar(128) NOT NULL, attempt_id varchar(128) NOT NULL,
 proof TEXT NOT NULL CHECK(proof IN('NEVER_CONSUMED','QUALIFIED_BROKER_ONLY')),
 invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 source_digest TEXT NOT NULL CHECK(source_digest ~ '^[a-f0-9]{64}$'), document TEXT NOT NULL CHECK(octet_length(document)<=1048576 AND jsonb_typeof(document::jsonb)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_attempt_manifest_seals(tenant_id,operation_id,attempt_id)
);
CREATE FUNCTION control_attempt_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NEW.recorded_at>=m.created_at AND i.revision=NEW.invocation_revision AND n.purpose='EXECUTION'
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(i.document::jsonb->>'reservedNonce','')<>NEW.attempt_id)
 AND NEW.document::jsonb->>'format'='toolgate-attempt-settlement-v1'
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id
 AND NEW.document::jsonb->>'proof'=NEW.proof AND NEW.document::jsonb->>'manifestDigest'=m.manifest_digest
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=m.tenant_id AND l.invocation_id=m.operation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND (g.state NOT IN('RELEASED','COMPLETED','FAILED')
 OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))
 OR NOT EXISTS(SELECT 1 FROM control_attempt_effects x WHERE x.tenant_id=g.tenant_id AND x.operation_id=g.operation_id AND x.attempt_id=g.attempt_id AND x.effect_id=g.effect_id)))
 AND ((NEW.proof='NEVER_CONSUMED' AND n.consumed_at IS NULL AND n.expires_at<=NEW.recorded_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id))
 OR (NEW.proof='QUALIFIED_BROKER_ONLY' AND n.consumed_at IS NOT NULL AND EXISTS(
 SELECT 1 FROM control_attempt_recovery_scopes s JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
 JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
 WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'
 AND ep.document::jsonb->>'state'='ACTIVE' AND d.document::jsonb->>'enabled'='true' FOR SHARE OF ep,d)))),false)) THEN RAISE EXCEPTION 'attempt settlement requires closed resolved witnessed authority'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER attempt_settlement_guard BEFORE INSERT ON control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION control_attempt_settlement_guard();
ALTER TABLE control_attempt_settlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_settlements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_settlements AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_settlements AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_settlements TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER attempt_settlement_immutable BEFORE UPDATE OR DELETE ON control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_attempt_settlement_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
 BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_attempt_settlement_capacity() FROM PUBLIC;
CREATE TRIGGER attempt_settlement_capacity AFTER INSERT ON control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION control_attempt_settlement_capacity();

CREATE TABLE control_operation_outcomes (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id), operation_id varchar(128) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1), invocation_revision BIGINT NOT NULL CHECK(invocation_revision>=1),
 state TEXT NOT NULL CHECK(state IN('RUNNING','OUTCOME_UNKNOWN','RECONCILIATION_REQUIRED','SUCCEEDED','PARTIAL','FAILED')),
 source_digest TEXT NOT NULL CHECK(source_digest ~ '^[a-f0-9]{64}$'),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 document TEXT NOT NULL CHECK(octet_length(document)<=1048576 AND jsonb_typeof(document::jsonb)='object'),
 PRIMARY KEY(tenant_id,operation_id,revision),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
CREATE FUNCTION control_operation_outcome_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=NEW.invocation_revision)
 AND NEW.revision=COALESCE((SELECT max(p.revision) FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id),0)+1
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'state'=NEW.state
 AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND (NEW.document::jsonb->>'updatedAtUnixMs')::bigint=NEW.recorded_at
 AND NEW.document::jsonb->'retryable'='false'::jsonb
 AND ((NEW.state NOT IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.document::jsonb->'finalized'='false'::jsonb)
 OR (NEW.state IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.document::jsonb->'finalized'='true'::jsonb
 AND NEW.document::jsonb->'journalComplete'='true'::jsonb AND (NEW.document::jsonb->>'unresolvedEffectCount')::bigint=0 AND (NEW.document::jsonb->>'unsettledAttemptCount')::bigint=0 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND NOT o.legacy
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce
 WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND (n.purpose<>'EXECUTION' OR a.attempt_id IS NULL OR a.operation_id<>n.invocation_id OR a.evaluation_digest<>n.evaluation_digest
 OR a.authorization_epoch<>n.authorization_epoch OR a.issued_at<>n.issued_at OR a.expires_at<>n.expires_at
 OR (n.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce) AND NOT EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=n.tenant_id AND q.operation_id=n.invocation_id AND q.attempt_id=n.nonce))))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND
 (g.state NOT IN('RELEASED','COMPLETED','FAILED') OR (g.state IN('COMPLETED','FAILED') AND NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state))))
 AND (NEW.state<>'SUCCEEDED' OR EXISTS(SELECT 1 FROM control_attempt_snapshots h
 WHERE h.tenant_id=i.tenant_id AND h.operation_id=i.invocation_id
 AND (EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=h.tenant_id AND s.operation_id=h.operation_id AND s.attempt_id=h.attempt_id)
 OR EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=h.tenant_id AND q.operation_id=h.operation_id AND q.attempt_id=h.attempt_id)) AND (h.state='SUCCEEDED' OR EXISTS(SELECT 1 FROM control_execution_evidence e WHERE e.tenant_id=h.tenant_id
 AND e.invocation_id=h.operation_id AND e.fencing_token=h.attempt_id AND e.qualified_report=true
 AND e.document::jsonb->>'outcome'='COMPLETED' AND e.document::jsonb->'result'->>'kind'='RESULT' AND e.document::jsonb->'result'->>'resultType'='complete' AND e.document::jsonb->'result'->>'isError'='false'))
 AND h.revision=(SELECT max(x.revision) FROM control_attempt_snapshots x WHERE x.tenant_id=h.tenant_id AND x.attempt_id=h.attempt_id)))
)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id
 AND p.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=p.tenant_id AND x.operation_id=p.operation_id)
 AND ((p.state='RECONCILIATION_REQUIRED' AND NEW.state<>'RECONCILIATION_REQUIRED')
 OR (p.state IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.state<>p.state AND NEW.state<>'RECONCILIATION_REQUIRED'
 AND NOT(p.state='FAILED' AND NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r
 WHERE r.tenant_id=p.tenant_id AND r.operation_id=p.operation_id AND r.to_revision>p.invocation_revision AND r.to_revision<=NEW.invocation_revision))))),false)) THEN RAISE EXCEPTION 'derived outcome requires complete evidence and immutable finality'; END IF; RETURN NEW; END $$;
CREATE TRIGGER operation_outcome_guard BEFORE INSERT ON control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION control_operation_outcome_guard();
ALTER TABLE control_operation_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_operation_outcomes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_operation_outcomes AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_operation_outcomes AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_operation_outcomes TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER operation_outcome_immutable BEFORE UPDATE OR DELETE ON control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_operation_outcome_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
 BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_operation_outcome_capacity() FROM PUBLIC;
CREATE TRIGGER operation_outcome_capacity AFTER INSERT ON control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION control_operation_outcome_capacity();

CREATE FUNCTION control_execution_reconciliation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.purpose='EXECUTION' AND (TG_OP='INSERT' OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL)) THEN
  PERFORM 1 FROM control_operations WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.invocation_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT') THEN RAISE EXCEPTION 'reconciliation fences new execution authority'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_reconciliation_guard BEFORE INSERT OR UPDATE ON control_enterprise_nonces
 FOR EACH ROW EXECUTE FUNCTION control_execution_reconciliation_guard();

CREATE OR REPLACE FUNCTION control_business_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND NEW.tool_id IS NOT DISTINCT FROM OLD.tool_id AND NEW.namespace IS NOT DISTINCT FROM OLD.namespace AND NEW.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND NEW.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND NEW.source IS NOT DISTINCT FROM OLD.source AND NEW.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND NEW.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND NEW.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND NEW.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at AND NEW.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND NEW.retain_until IS NOT DISTINCT FROM OLD.retain_until AND NEW.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at AND EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=OLD.tenant_id AND v.operation_id=OLD.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND NEW.outcome IS NOT DISTINCT FROM CASE v.state WHEN 'RUNNING' THEN NULL WHEN 'SUCCEEDED' THEN 'COMPLETED'
 WHEN 'FAILED' THEN 'FAILED' WHEN 'PARTIAL' THEN 'PARTIAL' ELSE 'OUTCOME_UNKNOWN' END) THEN RETURN NEW; END IF;
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
 AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.dedupe_expires_at IS DISTINCT FROM OLD.dedupe_expires_at OR NEW.retain_until IS DISTINCT FROM OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS DISTINCT FROM OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS DISTINCT FROM OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND NOT o.legacy AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))))))) THEN RAISE EXCEPTION 'business key identity, retention or recovery proof is immutable'; END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION control_business_key_history_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM control_business_keys k JOIN control_operations o
 ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id
 WHERE k.tenant_id IS NOT DISTINCT FROM NEW.tenant_id AND k.tool_id IS NOT DISTINCT FROM NEW.tool_id AND k.namespace IS NOT DISTINCT FROM NEW.namespace AND k.namespace_identity IS NOT DISTINCT FROM NEW.namespace_identity AND k.business_key_hash IS NOT DISTINCT FROM NEW.business_key_hash AND k.invocation_id IS NOT DISTINCT FROM NEW.invocation_id AND k.fingerprint IS NOT DISTINCT FROM NEW.fingerprint AND k.caller_agent_id IS NOT DISTINCT FROM NEW.caller_agent_id AND k.caller_user_id IS NOT DISTINCT FROM NEW.caller_user_id AND k.created_at IS NOT DISTINCT FROM NEW.created_at AND k.dedupe_expires_at IS NOT DISTINCT FROM NEW.dedupe_expires_at AND k.retain_until IS NOT DISTINCT FROM NEW.retain_until AND k.outcome IS NOT DISTINCT FROM NEW.outcome AND k.tombstoned_at IS NOT DISTINCT FROM NEW.tombstoned_at AND k.source='DEDUPE_WINDOW' AND k.dedupe_expires_at<=NEW.archived_at
 AND NOT o.legacy AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))) THEN RAISE EXCEPTION 'window archive requires an exact resolved reservation'; END IF; RETURN NEW; END $$;

ALTER TABLE control_execution_evidence ADD COLUMN qualified_report boolean NOT NULL DEFAULT false;
CREATE FUNCTION control_execution_report_qualification_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.qualified_report THEN
  PERFORM 1 FROM control_enterprise_nonces n
  JOIN control_attempt_recovery_scopes s ON s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
  JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
  JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
  JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
  WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.nonce=NEW.fencing_token
  AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
  AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'
  AND ep.document::jsonb->>'state'='ACTIVE' AND d.document::jsonb->>'enabled'='true'
  FOR SHARE OF ep,d;
  IF NOT FOUND THEN RAISE EXCEPTION 'qualified report requires independent current admission trust'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_report_qualification_guard BEFORE INSERT ON control_execution_evidence
 FOR EACH ROW EXECUTE FUNCTION control_execution_report_qualification_guard();

CREATE TABLE control_operation_review_owners(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id), operation_id varchar(128) NOT NULL,
 directory_revision BIGINT NOT NULL CHECK(directory_revision>=0),
 source_digest TEXT NOT NULL CHECK(source_digest ~ '^[a-f0-9]{64}$'),
 document TEXT NOT NULL CHECK(octet_length(document)<=16384 AND jsonb_typeof(document::jsonb)='object'),
 PRIMARY KEY(tenant_id,operation_id), FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
ALTER TABLE control_operation_review_owners ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_operation_review_owners FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_operation_review_owners AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_operation_review_owners AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_operation_review_owners TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER review_owners_immutable BEFORE UPDATE OR DELETE ON control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_review_owners_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=1 AND t.revision=NEW.directory_revision AND (i.document::jsonb->'evaluation'->>'authorityRevision')::bigint=NEW.directory_revision
 AND NEW.document::jsonb->>'format'='toolgate-review-owners-v1' AND NEW.document::jsonb->>'operationId'=NEW.operation_id
 AND jsonb_typeof(NEW.document::jsonb->'owners')='array' AND jsonb_array_length(NEW.document::jsonb->'owners') BETWEEN 1 AND 66 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.document::jsonb->'owners') j WHERE jsonb_typeof(j.value)<>'string') AND NOT EXISTS(SELECT 1 FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners WHERE owners.principal IS NULL) AND jsonb_array_length(NEW.document::jsonb->'owners')=(SELECT count(*) FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners) AND NOT EXISTS(SELECT 1 FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners WHERE owners.principal NOT IN(SELECT value FROM jsonb_array_elements_text(NEW.document::jsonb->'owners'))))
 THEN RAISE EXCEPTION 'review owners require original admission'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER review_owners_guard BEFORE INSERT ON control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION control_review_owners_guard();
CREATE FUNCTION control_review_owners_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_review_owners_capacity() FROM PUBLIC;
CREATE TRIGGER review_owners_capacity AFTER INSERT ON control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION control_review_owners_capacity();

CREATE TABLE control_attempt_reviews(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),operation_id varchar(128) NOT NULL,attempt_id varchar(128) NOT NULL,
 reconciliation_id varchar(128) NOT NULL,expected_outcome_revision BIGINT NOT NULL CHECK(expected_outcome_revision>=1),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 source_digest TEXT NOT NULL CHECK(source_digest ~ '^[a-f0-9]{64}$'),document TEXT NOT NULL CHECK(octet_length(document)<=1048576 AND jsonb_typeof(document::jsonb)='object'),
 PRIMARY KEY(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_attempt_manifest_seals(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,expected_outcome_revision) REFERENCES control_operation_outcomes(tenant_id,operation_id,revision),
 FOREIGN KEY(tenant_id,reconciliation_id) REFERENCES control_outcome_reconciliations(tenant_id,reconciliation_id)
);
ALTER TABLE control_attempt_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_reviews AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_reviews AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_reviews TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER attempt_review_immutable BEFORE UPDATE OR DELETE ON control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_attempt_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM control_operations WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 JOIN control_operation_outcomes v ON v.tenant_id=i.tenant_id AND v.operation_id=i.invocation_id AND v.revision=NEW.expected_outcome_revision
 JOIN control_outcome_reconciliations c ON c.tenant_id=i.tenant_id AND c.reconciliation_id=NEW.reconciliation_id
 JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT o.legacy AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND v.state='OUTCOME_UNKNOWN' AND v.invocation_revision=i.revision
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND v.document::jsonb->>'journalComplete'='true'
 AND (v.document::jsonb->>'unresolvedEffectCount')::bigint=0
 AND c.document::jsonb->>'state'='APPLIED'
 AND c.document::jsonb->>'invocationId'=i.invocation_id
 AND (c.document::jsonb->'request'->>'expectedRevision')::bigint=i.revision
 AND (c.document::jsonb->'request'->'attemptSettlement'->>'expectedOutcomeRevision')::bigint=NEW.expected_outcome_revision
 AND c.document::jsonb->'request'->'attemptSettlement'->>'noFurtherEffects'='true'

 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=c.document::jsonb->>'requesterUserId' AND u.document::jsonb->>'enabled'='true')
 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=c.document::jsonb->>'reviewerUserId' AND u.document::jsonb->>'enabled'='true')
 AND NOT EXISTS(SELECT 1 FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h) AND d.document::jsonb->>'ownerUserId' IN(c.document::jsonb->>'requesterUserId',c.document::jsonb->>'reviewerUserId'))
 AND NEW.attempt_id IN(SELECT value FROM jsonb_array_elements_text(c.document::jsonb->'request'->'attemptSettlement'->'attemptIds'))
 AND c.document::jsonb->>'requesterUserId'<>c.document::jsonb->>'reviewerUserId'
 AND (EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id
 AND c.document::jsonb->>'requesterUserId' NOT IN(SELECT value FROM jsonb_array_elements_text(w.document::jsonb->'owners')) AND c.document::jsonb->>'reviewerUserId' NOT IN(SELECT value FROM jsonb_array_elements_text(w.document::jsonb->'owners')))
 OR (NOT EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id)
 AND i.document::jsonb->'evaluation'->'context'->>'mode'='HUMAN' AND i.document::jsonb->'evaluation'->'context'->>'agentId' IS NULL AND jsonb_array_length(i.document::jsonb->'evaluation'->'context'->'chain')=0
 AND c.document::jsonb->>'requesterUserId'<>i.document::jsonb->'evaluation'->'context'->>'userId' AND c.document::jsonb->>'reviewerUserId'<>i.document::jsonb->'evaluation'->'context'->>'userId'))
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND (g.state NOT IN('RELEASED','COMPLETED','FAILED') OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))))
 AND NEW.document::jsonb->>'format'='toolgate-attempt-review-v1' AND NEW.document::jsonb->>'operationId'=NEW.operation_id
 AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id AND NEW.document::jsonb->>'manifestDigest'=m.manifest_digest
 AND NEW.document::jsonb->>'outcomeSourceDigest'=v.source_digest
 AND NEW.document::jsonb->'review'->>'requestDigest'=c.document::jsonb->>'requestDigest'
 AND (NEW.document::jsonb->>'directoryRevision')::bigint=t.revision AND (NEW.document::jsonb->>'authorizationEpoch')::bigint=t.authorization_epoch
)) THEN RAISE EXCEPTION 'attempt review requires exact independent approved closed resolved evidence'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attempt_review_guard BEFORE INSERT ON control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION control_attempt_review_guard();
CREATE FUNCTION control_attempt_review_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_attempt_review_capacity() FROM PUBLIC;
CREATE TRIGGER attempt_review_capacity AFTER INSERT ON control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION control_attempt_review_capacity();

CREATE FUNCTION control_reconciliation_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'reconciliation history is retained'; END IF;
 IF NOT(COALESCE(NEW.document::jsonb->>'id'=NEW.reconciliation_id AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(NEW.document::jsonb->>'requesterUserId') BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.document::jsonb->>'invocationId'),false)) THEN RAISE EXCEPTION 'invalid reconciliation identity'; END IF;
 IF TG_OP='INSERT' THEN
  IF NOT(COALESCE(NEW.revision=1 AND NEW.document::jsonb->>'state'='PENDING' AND NEW.document::jsonb->>'reviewerUserId' IS NULL,false)) THEN RAISE EXCEPTION 'reconciliation must begin pending'; END IF;
 ELSE
  IF NOT(COALESCE(NEW.tenant_id=OLD.tenant_id AND NEW.reconciliation_id=OLD.reconciliation_id AND NEW.revision=OLD.revision+1 AND OLD.document::jsonb->>'state'='PENDING' AND NEW.document::jsonb->>'state' IN('APPLIED','DENIED') AND length(NEW.document::jsonb->>'reviewerUserId') BETWEEN 1 AND 128 AND NEW.document::jsonb->>'id' IS NOT DISTINCT FROM OLD.document::jsonb->>'id' AND NEW.document::jsonb->>'invocationId' IS NOT DISTINCT FROM OLD.document::jsonb->>'invocationId' AND NEW.document::jsonb->>'requesterUserId' IS NOT DISTINCT FROM OLD.document::jsonb->>'requesterUserId' AND NEW.document::jsonb->>'requestDigest' IS NOT DISTINCT FROM OLD.document::jsonb->>'requestDigest' AND NEW.document::jsonb->'request' IS NOT DISTINCT FROM OLD.document::jsonb->'request',false)) THEN RAISE EXCEPTION 'reconciliation proposal and decision are immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_history_guard BEFORE INSERT OR UPDATE OR DELETE ON control_outcome_reconciliations
 FOR EACH ROW EXECUTE FUNCTION control_reconciliation_history_guard();
