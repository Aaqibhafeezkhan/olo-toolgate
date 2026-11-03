-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effect_consents(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),consent_id varchar(128) NOT NULL,operation_id varchar(128) NOT NULL,
 preview_digest TEXT NOT NULL CHECK(preview_digest ~ '^sha256:[a-f0-9]{64}$'),document TEXT NOT NULL CHECK(octet_length(document)<=2097152 AND jsonb_typeof(document::jsonb)='object'),
 not_before BIGINT NOT NULL CHECK(not_before BETWEEN 0 AND 9007199254740991),
 expires_at BIGINT NOT NULL CHECK(expires_at>not_before AND expires_at<=9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
ALTER TABLE control_effect_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_consents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_consents AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_consents AS RESTRICTIVE FOR ALL TO PUBLIC USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_consents TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER consents_immutable BEFORE UPDATE OR DELETE ON control_effect_consents FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION consents_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION consents_capacity() FROM PUBLIC;
CREATE TRIGGER consents_capacity AFTER INSERT ON control_effect_consents FOR EACH ROW EXECUTE FUNCTION consents_capacity();
CREATE TABLE control_effect_consent_slots(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),consent_id varchar(128) NOT NULL,slot_index BIGINT NOT NULL CHECK(slot_index BETWEEN 0 AND 63),
 route_id varchar(128) NOT NULL,mapped_digest TEXT NOT NULL CHECK(mapped_digest ~ '^sha256:[a-f0-9]{64}$'),permitted_count BIGINT NOT NULL CHECK(permitted_count BETWEEN 1 AND 64),
 PRIMARY KEY(tenant_id,consent_id,slot_index),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
ALTER TABLE control_effect_consent_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_consent_slots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_consent_slots AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_consent_slots AS RESTRICTIVE FOR ALL TO PUBLIC USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_consent_slots TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER consent_slots_immutable BEFORE UPDATE OR DELETE ON control_effect_consent_slots FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION consent_slots_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION consent_slots_capacity() FROM PUBLIC;
CREATE TRIGGER consent_slots_capacity AFTER INSERT ON control_effect_consent_slots FOR EACH ROW EXECUTE FUNCTION consent_slots_capacity();
CREATE TABLE control_effect_consent_approvals(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),consent_id varchar(128) NOT NULL,reviewer varchar(128) NOT NULL,
 authorization_epoch BIGINT NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 approved_at BIGINT NOT NULL CHECK(approved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
ALTER TABLE control_effect_consent_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_consent_approvals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_consent_approvals AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_consent_approvals AS RESTRICTIVE FOR ALL TO PUBLIC USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_consent_approvals TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER consent_approvals_immutable BEFORE UPDATE OR DELETE ON control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION consent_approvals_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION consent_approvals_capacity() FROM PUBLIC;
CREATE TRIGGER consent_approvals_capacity AFTER INSERT ON control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION consent_approvals_capacity();
CREATE TABLE control_effect_consent_revocations(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),consent_id varchar(128) NOT NULL,reviewer varchar(128) NOT NULL,
 revoked_at BIGINT NOT NULL CHECK(revoked_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
ALTER TABLE control_effect_consent_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_consent_revocations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_consent_revocations AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_consent_revocations AS RESTRICTIVE FOR ALL TO PUBLIC USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_consent_revocations TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER consent_revocations_immutable BEFORE UPDATE OR DELETE ON control_effect_consent_revocations FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION consent_revocations_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION consent_revocations_capacity() FROM PUBLIC;
CREATE TRIGGER consent_revocations_capacity AFTER INSERT ON control_effect_consent_revocations FOR EACH ROW EXECUTE FUNCTION consent_revocations_capacity();
CREATE TABLE control_effect_consent_uses(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),consent_id varchar(128) NOT NULL,slot_index BIGINT NOT NULL,effect_id varchar(128) NOT NULL,
 request_digest TEXT NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),reserved_at BIGINT NOT NULL CHECK(reserved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),FOREIGN KEY(tenant_id,consent_id,slot_index) REFERENCES control_effect_consent_slots(tenant_id,consent_id,slot_index),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
ALTER TABLE control_effect_consent_uses ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_effect_consent_uses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_effect_consent_uses AS PERMISSIVE FOR ALL TO PUBLIC USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_effect_consent_uses AS RESTRICTIVE FOR ALL TO PUBLIC USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
GRANT SELECT,INSERT ON control_effect_consent_uses TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER consent_uses_immutable BEFORE UPDATE OR DELETE ON control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION control_attempt_immutable();
CREATE FUNCTION consent_uses_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;
REVOKE ALL ON FUNCTION consent_uses_capacity() FROM PUBLIC;
CREATE TRIGGER consent_uses_capacity AFTER INSERT ON control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION consent_uses_capacity();
CREATE FUNCTION consent_use_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id||'/consent/'||NEW.consent_id||'/'||NEW.slot_index,0));
 IF NOT EXISTS(SELECT 1 FROM control_effect_consent_slots s
 JOIN control_effect_consents c ON c.tenant_id=s.tenant_id AND c.consent_id=s.consent_id
 JOIN control_effect_consent_approvals a ON a.tenant_id=c.tenant_id AND a.consent_id=c.consent_id
 JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id AND t.authorization_epoch=a.authorization_epoch
 JOIN control_effects e ON e.tenant_id=c.tenant_id AND e.operation_id=c.operation_id AND e.route_id=s.route_id
 JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE s.tenant_id=NEW.tenant_id AND s.consent_id=NEW.consent_id AND s.slot_index=NEW.slot_index
 AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND c.not_before<=NEW.reserved_at AND c.expires_at>NEW.reserved_at AND a.approved_at<=NEW.reserved_at
 AND g.state='AUTHORIZED' AND g.expires_at>NEW.reserved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)
 AND (SELECT count(*) FROM control_effect_consent_uses u WHERE u.tenant_id=s.tenant_id AND u.consent_id=s.consent_id AND u.slot_index=s.slot_index)<s.permitted_count) THEN RAISE EXCEPTION 'effect consent scope or allowance refused'; END IF; RETURN NEW; END $$;
CREATE TRIGGER consent_use_guard BEFORE INSERT ON control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION consent_use_guard();
CREATE FUNCTION consent_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_effect_consents c JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id
 WHERE c.tenant_id=NEW.tenant_id AND c.consent_id=NEW.consent_id AND t.authorization_epoch=NEW.authorization_epoch
 AND c.not_before<=NEW.approved_at AND c.expires_at>NEW.approved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)) THEN RAISE EXCEPTION 'effect consent scope or allowance refused'; END IF; RETURN NEW; END $$;
CREATE TRIGGER consent_approval_guard BEFORE INSERT ON control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION consent_approval_guard();
