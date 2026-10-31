-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effects(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id TEXT NOT NULL, operation_id TEXT NOT NULL, route_id TEXT NOT NULL,
 request_digest TEXT NOT NULL, current_generation BIGINT NOT NULL CHECK(current_generation>=1),
 PRIMARY KEY(tenant_id,effect_id), UNIQUE(tenant_id,operation_id,effect_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_operations(tenant_id,operation_id)
);
-- statement
CREATE TABLE control_effect_generations(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 effect_id TEXT NOT NULL, generation BIGINT NOT NULL CHECK(generation>=1),
 operation_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
 auth_nonce TEXT NOT NULL, device_id TEXT NOT NULL, broker_id TEXT NOT NULL, handler_id TEXT NOT NULL,
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
-- statement
CREATE UNIQUE INDEX control_one_live_effect_generation ON control_effect_generations(tenant_id,effect_id) WHERE state IN('AUTHORIZED','SENDING','CONSUMED');
-- statement
CREATE INDEX control_effect_expiry ON control_effect_generations(tenant_id,state,expires_at,outcome_deadline);
-- statement
CREATE TABLE control_effect_evidence(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id), effect_id TEXT NOT NULL,
 generation BIGINT NOT NULL, evidence_digest TEXT NOT NULL, source TEXT NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN('COMPLETED','FAILED')), result_digest TEXT NOT NULL,
 recorded_at BIGINT NOT NULL CHECK(recorded_at>=0),
 PRIMARY KEY(tenant_id,effect_id,generation,evidence_digest),
 FOREIGN KEY(tenant_id,effect_id,generation) REFERENCES control_effect_generations(tenant_id,effect_id,generation)
);
-- statement
CREATE TRIGGER effect_identity_delete BEFORE DELETE ON control_effects BEGIN SELECT RAISE(ABORT,'effect history is retained'); END;
-- statement
CREATE TRIGGER effect_identity_update BEFORE UPDATE ON control_effects WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.operation_id<>OLD.operation_id OR NEW.route_id<>OLD.route_id OR NEW.request_digest<>OLD.request_digest OR NEW.current_generation<>OLD.current_generation+1 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=OLD.tenant_id AND g.effect_id=OLD.effect_id AND g.generation=OLD.current_generation AND g.state='RELEASED') BEGIN SELECT RAISE(ABORT,'invalid effect identity or transition'); END;
-- statement
CREATE TRIGGER effect_generation_delete BEFORE DELETE ON control_effect_generations BEGIN SELECT RAISE(ABORT,'effect history is retained'); END;
-- statement
CREATE TRIGGER effect_generation_update BEFORE UPDATE ON control_effect_generations WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.generation<>OLD.generation OR NEW.operation_id<>OLD.operation_id OR NEW.attempt_id<>OLD.attempt_id OR NEW.auth_nonce<>OLD.auth_nonce OR NEW.device_id<>OLD.device_id OR NEW.broker_id<>OLD.broker_id OR NEW.handler_id<>OLD.handler_id OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.outcome_deadline<>OLD.outcome_deadline OR NEW.revision<>OLD.revision+1 OR (OLD.consumer IS NOT NULL AND (NEW.consumer IS NOT OLD.consumer OR NEW.claim_token IS NOT OLD.claim_token)) OR (OLD.consumer IS NULL AND NEW.consumer IS NOT NULL AND NOT(OLD.state IN('AUTHORIZED','SENDING') AND NEW.state='CONSUMED')) OR NOT((NEW.state=OLD.state) OR
 (OLD.state='AUTHORIZED' AND NEW.state IN('RELEASED','SENDING','CONSUMED','AMBIGUOUS')) OR
 (OLD.state='SENDING' AND NEW.state IN('CONSUMED','AMBIGUOUS')) OR
 (OLD.state='CONSUMED' AND NEW.state IN('COMPLETED','FAILED','AMBIGUOUS')) OR
 (OLD.state='AMBIGUOUS' AND NEW.state IN('COMPLETED','FAILED')) OR
 (OLD.state IN('COMPLETED','FAILED') AND NEW.state='CONFLICT')) BEGIN SELECT RAISE(ABORT,'invalid effect identity or transition'); END;
-- statement
CREATE TRIGGER effect_insert_guard BEFORE INSERT ON control_effect_generations WHEN NEW.state<>'AUTHORIZED' OR NEW.revision<>1 OR NEW.consumer IS NOT NULL OR NOT EXISTS(SELECT 1 FROM control_effects e WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id AND e.current_generation=NEW.generation) BEGIN SELECT RAISE(ABORT,'generation must be current and new'); END;
-- statement
CREATE TRIGGER effect_evidence_update BEFORE UPDATE ON control_effect_evidence BEGIN SELECT RAISE(ABORT,'effect evidence is immutable'); END;
-- statement
CREATE TRIGGER effect_evidence_delete BEFORE DELETE ON control_effect_evidence BEGIN SELECT RAISE(ABORT,'effect evidence is immutable'); END;
-- statement
CREATE TRIGGER effect_family_insert_guard BEFORE INSERT ON control_effects WHEN NEW.current_generation<>1 BEGIN SELECT RAISE(ABORT,'effect starts at generation one'); END;
