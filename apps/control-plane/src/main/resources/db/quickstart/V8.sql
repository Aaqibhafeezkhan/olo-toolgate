-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effect_intents(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),effect_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=71 AND substr(request_digest,1,7)='sha256:' AND substr(request_digest,8) NOT GLOB '*[^a-f0-9]*'),
 arguments_digest TEXT NOT NULL CHECK(length(arguments_digest)=71 AND substr(arguments_digest,1,7)='sha256:' AND substr(arguments_digest,8) NOT GLOB '*[^a-f0-9]*'),
 profile_digest TEXT NOT NULL CHECK(length(profile_digest)=71 AND substr(profile_digest,1,7)='sha256:' AND substr(profile_digest,8) NOT GLOB '*[^a-f0-9]*'),
 classification TEXT NOT NULL CHECK(classification IN('READ','WRITE','DESTRUCTIVE','FINANCIAL')),
 document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=1048576 AND json_valid(document) AND json_type(document)='object'),
 recorded_at INTEGER NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
-- statement
CREATE TRIGGER effect_intent_immutable_update BEFORE UPDATE ON control_effect_intents BEGIN SELECT RAISE(ABORT,'effect intent is immutable'); END;
-- statement
CREATE TRIGGER effect_intent_immutable_delete BEFORE DELETE ON control_effect_intents BEGIN SELECT RAISE(ABORT,'effect intent is immutable'); END;
-- statement
CREATE TRIGGER effect_intent_guard BEFORE INSERT ON control_effect_intents
 WHEN NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND g.state='AUTHORIZED' AND g.issued_at=NEW.recorded_at)
 BEGIN SELECT RAISE(ABORT,'intent requires matching fresh effect authority'); END;
-- statement
CREATE TRIGGER effect_intent_capacity AFTER INSERT ON control_effect_intents
 BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB))
 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
