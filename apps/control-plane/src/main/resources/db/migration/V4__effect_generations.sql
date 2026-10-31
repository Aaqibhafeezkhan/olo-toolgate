-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effects(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id varchar(128) NOT NULL, operation_id varchar(128) NOT NULL, route_id TEXT NOT NULL,
 request_digest TEXT NOT NULL, current_generation BIGINT NOT NULL CHECK(current_generation>=1),
 PRIMARY KEY(tenant_id,effect_id), UNIQUE(tenant_id,operation_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
CREATE TABLE control_effect_generations(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id varchar(128) NOT NULL, generation BIGINT NOT NULL CHECK(generation>=1),
 operation_id varchar(128) NOT NULL, attempt_id varchar(128) NOT NULL,
 auth_nonce varchar(128) NOT NULL, device_id varchar(128) NOT NULL, broker_id varchar(128) NOT NULL, handler_id varchar(128) NOT NULL,
 state TEXT NOT NULL CHECK(state IN('AUTHORIZED','RELEASED','SENDING','CONSUMED','COMPLETED','FAILED','AMBIGUOUS','CONFLICT')),
 issued_at BIGINT NOT NULL CHECK(issued_at>=0), expires_at BIGINT NOT NULL CHECK(expires_at>issued_at),
 outcome_deadline BIGINT NOT NULL CHECK(outcome_deadline>=expires_at), revision BIGINT NOT NULL CHECK(revision>=1),
 consumer TEXT, claim_token TEXT,
 PRIMARY KEY(tenant_id,effect_id,generation), UNIQUE(tenant_id,auth_nonce),
 FOREIGN KEY(tenant_id,operation_id,effect_id) REFERENCES control_effects(tenant_id,operation_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK((consumer IS NULL)=(claim_token IS NULL)),
 CHECK(state<>'CONSUMED' OR consumer IS NOT NULL)
);
CREATE UNIQUE INDEX control_one_live_effect_generation ON control_effect_generations(tenant_id,effect_id) WHERE state IN('AUTHORIZED','SENDING','CONSUMED');
CREATE INDEX control_effect_expiry ON control_effect_generations(tenant_id,state,expires_at,outcome_deadline);
CREATE TABLE control_effect_evidence(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id), effect_id varchar(128) NOT NULL,
 generation BIGINT NOT NULL, evidence_digest TEXT NOT NULL, source TEXT NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN('COMPLETED','FAILED')), result_digest TEXT NOT NULL,
 recorded_at BIGINT NOT NULL CHECK(recorded_at>=0),
 PRIMARY KEY(tenant_id,effect_id,generation,evidence_digest),
 FOREIGN KEY(tenant_id,effect_id,generation) REFERENCES control_effect_generations(tenant_id,effect_id,generation)
);
ALTER TABLE control_effects ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effects AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effects AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT,UPDATE ON control_effects TO toolgate_control_runtime,toolgate_control_platform;
ALTER TABLE control_effect_generations ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_generations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_generations AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_generations AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT,UPDATE ON control_effect_generations TO toolgate_control_runtime,toolgate_control_platform;
ALTER TABLE control_effect_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_evidence AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_evidence AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_evidence TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_effect_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'effect history is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.operation_id<>OLD.operation_id OR NEW.route_id<>OLD.route_id OR NEW.request_digest<>OLD.request_digest OR NEW.current_generation<>OLD.current_generation+1 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=OLD.tenant_id AND g.effect_id=OLD.effect_id AND g.generation=OLD.current_generation AND g.state='RELEASED') THEN RAISE EXCEPTION 'invalid effect identity or transition'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER effect_identity_guard BEFORE UPDATE OR DELETE ON control_effects FOR EACH ROW EXECUTE FUNCTION control_effect_identity_guard();
CREATE FUNCTION control_effect_generation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'effect history is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.generation<>OLD.generation OR NEW.operation_id<>OLD.operation_id OR NEW.attempt_id<>OLD.attempt_id OR NEW.auth_nonce<>OLD.auth_nonce OR NEW.device_id<>OLD.device_id OR NEW.broker_id<>OLD.broker_id OR NEW.handler_id<>OLD.handler_id OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.outcome_deadline<>OLD.outcome_deadline OR NEW.revision<>OLD.revision+1 OR (OLD.consumer IS NOT NULL AND (NEW.consumer IS DISTINCT FROM OLD.consumer OR NEW.claim_token IS DISTINCT FROM OLD.claim_token)) OR (OLD.consumer IS NULL AND NEW.consumer IS NOT NULL AND NOT(OLD.state IN('AUTHORIZED','SENDING') AND NEW.state='CONSUMED')) OR NOT((NEW.state=OLD.state) OR
 (OLD.state='AUTHORIZED' AND NEW.state IN('RELEASED','SENDING','CONSUMED','AMBIGUOUS')) OR
 (OLD.state='SENDING' AND NEW.state IN('CONSUMED','AMBIGUOUS')) OR
 (OLD.state='CONSUMED' AND NEW.state IN('COMPLETED','FAILED','AMBIGUOUS')) OR
 (OLD.state='AMBIGUOUS' AND NEW.state IN('COMPLETED','FAILED')) OR
 (OLD.state IN('COMPLETED','FAILED') AND NEW.state='CONFLICT')) THEN RAISE EXCEPTION 'invalid effect identity or transition'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER effect_generation_guard BEFORE UPDATE OR DELETE ON control_effect_generations FOR EACH ROW EXECUTE FUNCTION control_effect_generation_guard();
CREATE FUNCTION control_effect_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.state<>'AUTHORIZED' OR NEW.revision<>1 OR NEW.consumer IS NOT NULL OR NOT EXISTS(SELECT 1 FROM control_effects e WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id AND e.current_generation=NEW.generation) THEN RAISE EXCEPTION 'generation must be current and new'; END IF; RETURN NEW; END $$;
CREATE TRIGGER effect_insert_guard BEFORE INSERT ON control_effect_generations FOR EACH ROW EXECUTE FUNCTION control_effect_insert_guard();
CREATE TRIGGER effect_evidence_immutable BEFORE UPDATE OR DELETE ON control_effect_evidence FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_effect_family_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.current_generation<>1 THEN RAISE EXCEPTION 'effect starts at generation one'; END IF; RETURN NEW; END $$;
CREATE TRIGGER effect_family_insert_guard BEFORE INSERT ON control_effects FOR EACH ROW EXECUTE FUNCTION control_effect_family_insert_guard();
