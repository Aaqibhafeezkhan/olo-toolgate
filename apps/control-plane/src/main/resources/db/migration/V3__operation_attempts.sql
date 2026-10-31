-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
-- Transactional owner-only backfill; FORCE is restored before runtime access.
ALTER TABLE control_enterprise_invocations NO FORCE ROW LEVEL SECURITY;
ALTER TABLE control_enterprise_nonces NO FORCE ROW LEVEL SECURITY;
ALTER TABLE control_enterprise_nonces ADD COLUMN purpose text NOT NULL DEFAULT 'LEGACY' CHECK(purpose IN('LEGACY','EXECUTION','SERVICE'));
CREATE INDEX control_nonce_operation_purpose ON control_enterprise_nonces(tenant_id,invocation_id,purpose);
CREATE TABLE control_operations (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id varchar(128) NOT NULL,
 request_digest text NOT NULL,
 state text NOT NULL CHECK(state IN('RUNNING','OUTCOME_UNKNOWN','FAILED')),
 legacy boolean NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1),
 created_at BIGINT NOT NULL CHECK(created_at>=0),
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
CREATE TABLE control_operation_attempts (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 attempt_id varchar(128) NOT NULL,
 operation_id varchar(128) NOT NULL,
 evaluation_digest text NOT NULL,
 authorization_epoch BIGINT NOT NULL,
 issued_at BIGINT NOT NULL CHECK(issued_at>=0),
 expires_at BIGINT NOT NULL CHECK(expires_at>issued_at),
 PRIMARY KEY(tenant_id,attempt_id),
 UNIQUE(tenant_id,operation_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES control_enterprise_nonces(tenant_id,nonce)
);
CREATE TABLE control_attempt_snapshots (
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id varchar(128) NOT NULL,
 attempt_id varchar(128) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=1),
 state text NOT NULL CHECK(state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING','SUCCEEDED','PARTIAL','FAILED','OUTCOME_UNKNOWN','CANCELLED','EXPIRED')),
 document TEXT NOT NULL CHECK(octet_length(document)<=5242880
 AND COALESCE(document::jsonb->>'id','')=operation_id
 AND COALESCE(document::jsonb->>'reservedNonce','')=attempt_id
 AND COALESCE(document::jsonb->>'state','')=state),
 PRIMARY KEY(tenant_id,attempt_id,revision),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id)
);
INSERT INTO control_operations
 SELECT tenant_id,invocation_id,request_digest,
 CASE WHEN state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 'RUNNING' ELSE 'OUTCOME_UNKNOWN' END,
 true,revision,created_at FROM control_enterprise_invocations;
INSERT INTO control_operation_attempts
 SELECT n.tenant_id,n.nonce,n.invocation_id,n.evaluation_digest,n.authorization_epoch,n.issued_at,n.expires_at
 FROM control_enterprise_nonces n JOIN control_operations o ON o.tenant_id=n.tenant_id AND o.operation_id=n.invocation_id WHERE n.purpose<>'SERVICE';
INSERT INTO control_attempt_snapshots SELECT n.tenant_id,n.invocation_id,n.nonce,i.revision,(CASE WHEN n.nonce=i.document::jsonb->>'reservedNonce' THEN i.state WHEN n.consumed_at IS NOT NULL THEN 'OUTCOME_UNKNOWN' ELSE 'EXPIRED' END),(i.document::jsonb || jsonb_build_object('id',i.invocation_id,'reservedNonce',n.nonce,'state',(CASE WHEN n.nonce=i.document::jsonb->>'reservedNonce' THEN i.state WHEN n.consumed_at IS NOT NULL THEN 'OUTCOME_UNKNOWN' ELSE 'EXPIRED' END),'revision',i.revision))::text FROM control_enterprise_nonces n JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id;
ALTER TABLE control_enterprise_invocations FORCE ROW LEVEL SECURITY;
ALTER TABLE control_enterprise_nonces FORCE ROW LEVEL SECURITY;
ALTER TABLE control_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_operations AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_operations AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT,UPDATE ON control_operations TO toolgate_control_runtime,toolgate_control_platform;
ALTER TABLE control_operation_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_operation_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_operation_attempts AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_operation_attempts AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_operation_attempts TO toolgate_control_runtime,toolgate_control_platform;
ALTER TABLE control_attempt_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_attempt_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_attempt_snapshots AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_attempt_snapshots AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_attempt_snapshots TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_attempt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'attempt journal is immutable'; END $$;
CREATE TRIGGER attempt_immutable BEFORE UPDATE OR DELETE ON control_operation_attempts FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE TRIGGER snapshot_immutable BEFORE UPDATE OR DELETE ON control_attempt_snapshots FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'operation is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED') THEN RAISE EXCEPTION 'operation identity or finality is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_guard BEFORE UPDATE OR DELETE ON control_operations FOR EACH ROW EXECUTE FUNCTION control_operation_guard();
CREATE FUNCTION control_attempt_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.purpose<>'SERVICE' AND (NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND (n.consumed_at IS NOT NULL OR n.expires_at>NEW.issued_at OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED'))
 THEN RAISE EXCEPTION 'predecessor is not proven expired and unconsumed'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attempt_reservation_guard BEFORE INSERT ON control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION control_attempt_reservation_guard();
CREATE FUNCTION control_nonce_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.nonce<>OLD.nonce OR NEW.invocation_id<>OLD.invocation_id
 OR NEW.evaluation_digest<>OLD.evaluation_digest OR NEW.authorization_epoch<>OLD.authorization_epoch
 OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.purpose<>OLD.purpose
 OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
 OR (NEW.consumed_at IS NOT NULL AND (NEW.consumed_at<OLD.issued_at OR NEW.consumed_at>=OLD.expires_at))
 OR (NEW.purpose<>'SERVICE' AND OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=NEW.tenant_id AND a.attempt_id=NEW.nonce))
 OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED'))
 THEN RAISE EXCEPTION 'nonce identity and consumption are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER nonce_identity_guard BEFORE UPDATE ON control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION control_nonce_identity_guard();
CREATE FUNCTION control_attempt_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.nonce=NEW.attempt_id
 AND n.purpose<>'SERVICE' AND n.invocation_id=NEW.operation_id AND n.evaluation_digest=NEW.evaluation_digest AND n.authorization_epoch=NEW.authorization_epoch
 AND n.issued_at=NEW.issued_at AND n.expires_at=NEW.expires_at) THEN RAISE EXCEPTION 'attempt must bind its original permit'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attempt_binding_guard BEFORE INSERT ON control_operation_attempts FOR EACH ROW EXECUTE FUNCTION control_attempt_binding_guard();
CREATE FUNCTION control_snapshot_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM control_attempt_snapshots s WHERE s.tenant_id=NEW.tenant_id AND s.attempt_id=NEW.attempt_id
 AND (s.revision>=NEW.revision OR (s.state IN('SUCCEEDED','FAILED','PARTIAL','CANCELLED','EXPIRED') AND s.state<>NEW.state)
 OR (s.state='OUTCOME_UNKNOWN' AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING'))))
 THEN RAISE EXCEPTION 'attempt cannot reopen or rewrite history'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER snapshot_transition_guard BEFORE INSERT ON control_attempt_snapshots FOR EACH ROW EXECUTE FUNCTION control_snapshot_transition_guard();
