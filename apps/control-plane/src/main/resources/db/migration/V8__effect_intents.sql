-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effect_intents(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),effect_id varchar(128) NOT NULL,
 request_digest TEXT NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
 arguments_digest TEXT NOT NULL CHECK(arguments_digest ~ '^sha256:[a-f0-9]{64}$'),
 profile_digest TEXT NOT NULL CHECK(profile_digest ~ '^sha256:[a-f0-9]{64}$'),
 classification TEXT NOT NULL CHECK(classification IN('READ','WRITE','DESTRUCTIVE','FINANCIAL')),
 document TEXT NOT NULL CHECK(octet_length(document)<=1048576 AND jsonb_typeof(document::jsonb)='object'),
 recorded_at BIGINT NOT NULL CHECK(recorded_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
ALTER TABLE control_effect_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_intents AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_intents AS RESTRICTIVE FOR ALL TO PUBLIC
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false))
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_intents TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER effect_intent_immutable BEFORE UPDATE OR DELETE ON control_effect_intents FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION control_effect_intent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND g.state='AUTHORIZED' AND g.issued_at=NEW.recorded_at)
 THEN RAISE EXCEPTION 'intent requires matching fresh effect authority'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER effect_intent_guard BEFORE INSERT ON control_effect_intents FOR EACH ROW EXECUTE FUNCTION control_effect_intent_guard();
CREATE FUNCTION control_effect_intent_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION control_effect_intent_capacity() FROM PUBLIC;
CREATE TRIGGER effect_intent_capacity AFTER INSERT ON control_effect_intents FOR EACH ROW EXECUTE FUNCTION control_effect_intent_capacity();
