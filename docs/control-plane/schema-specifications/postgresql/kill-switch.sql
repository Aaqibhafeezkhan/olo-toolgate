-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Current fresh-schema kill storage, mirrored in initial V1.
-- Grow-only signed kill and recovery events (plan.md §15). Normative description:
-- docs/control-plane/kill-switch.md.

CREATE TABLE control_kill_events (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    event_id varchar(40) NOT NULL,
    issuer varchar(24) NOT NULL CHECK (issuer IN ('control','control-breakglass')),
    issuer_counter bigint NOT NULL CHECK (issuer_counter BETWEEN 1 AND 9999999999999999),
    event_kind varchar(8) NOT NULL CHECK (event_kind IN ('KILL','RECOVERY')),
    scope_kind varchar(16) CHECK (scope_kind IN ('PACKAGE','PACKAGE_DIGEST','PUBLISHER','TOOL','POOL','RUNTIME_IMAGE','TENANT')),
    scope_value varchar(256),
    mode varchar(16) CHECK (mode IN ('STOP_NEW','TERMINATE')),
    authorization_epoch bigint NOT NULL CHECK (authorization_epoch BETWEEN 1 AND 9007199254740991),
    issued_at bigint NOT NULL CHECK (issued_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK (octet_length(document) <= 65536),
    PRIMARY KEY (tenant_id, event_id),
    UNIQUE (tenant_id, issuer, issuer_counter),
    UNIQUE (tenant_id, authorization_epoch),
    CHECK (event_id = issuer || ':' || issuer_counter::text),
    CHECK (event_kind = 'KILL' OR issuer = 'control'),
    CHECK ((event_kind = 'KILL') = (scope_kind IS NOT NULL)),
    CHECK ((event_kind = 'KILL') = (mode IS NOT NULL)),
    CHECK (scope_kind IS NULL OR (scope_kind = 'TENANT') = (scope_value IS NULL))
);
CREATE INDEX control_kill_event_epoch ON control_kill_events (tenant_id, authorization_epoch);

-- One row per (recovery, kill) it supersedes; a kill is effective while it has no row here.
CREATE TABLE control_kill_supersessions (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    kill_event_id varchar(40) NOT NULL,
    recovery_event_id varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, kill_event_id, recovery_event_id),
    FOREIGN KEY (tenant_id, kill_event_id) REFERENCES control_kill_events (tenant_id, event_id),
    FOREIGN KEY (tenant_id, recovery_event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Superseded events move out of the push set into the audited archive; the event rows stay.
CREATE TABLE control_kill_archive (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    event_id varchar(40) NOT NULL,
    archived_at bigint NOT NULL CHECK (archived_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Highest authorization epoch each component has acknowledged, and the path that delivered it.
CREATE TABLE control_kill_acks (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    component_kind varchar(16) NOT NULL CHECK (component_kind IN ('TOOL_HOST','ENDPOINT','GATEWAY')),
    component_id varchar(256) NOT NULL,
    acked_epoch bigint NOT NULL CHECK (acked_epoch BETWEEN 0 AND 9007199254740991),
    acked_at bigint NOT NULL CHECK (acked_at BETWEEN 0 AND 9007199254740991),
    delivery_path varchar(16) NOT NULL CHECK (delivery_path IN ('PUSH','CHECK_IN','POLL')),
    PRIMARY KEY (tenant_id, component_kind, component_id)
);

CREATE FUNCTION control_kill_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'kill events are grow-only'; END $$;
CREATE TRIGGER control_kill_events_immutable BEFORE UPDATE OR DELETE ON control_kill_events
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
CREATE TRIGGER control_kill_supersessions_immutable BEFORE UPDATE OR DELETE ON control_kill_supersessions
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
CREATE TRIGGER control_kill_archive_immutable BEFORE UPDATE OR DELETE ON control_kill_archive
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();

-- Only a recovery event may supersede, only a kill may be superseded, and only an
-- already superseded event may be archived.
CREATE FUNCTION control_kill_supersession_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM control_kill_events k JOIN control_kill_events r ON r.tenant_id=k.tenant_id
        WHERE k.tenant_id=NEW.tenant_id AND k.event_id=NEW.kill_event_id AND k.event_kind='KILL'
        AND r.event_id=NEW.recovery_event_id AND r.event_kind='RECOVERY' AND r.authorization_epoch>k.authorization_epoch
        AND (r.document::jsonb->'supersedes') @> jsonb_build_array(k.event_id)) THEN
        RAISE EXCEPTION 'a signed later recovery must name its superseded kill';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_supersession_guard BEFORE INSERT ON control_kill_supersessions
    FOR EACH ROW EXECUTE FUNCTION control_kill_supersession_guard();

CREATE FUNCTION control_kill_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM control_kill_supersessions WHERE tenant_id = NEW.tenant_id
                   AND (kill_event_id = NEW.event_id OR recovery_event_id = NEW.event_id)) THEN
        RAISE EXCEPTION 'only superseded kills and applied recoveries are archived';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_archive_guard BEFORE INSERT ON control_kill_archive
    FOR EACH ROW EXECUTE FUNCTION control_kill_archive_guard();

-- Acknowledgements only move forward.
CREATE FUNCTION control_kill_ack_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.tenant_id <> OLD.tenant_id OR NEW.component_kind <> OLD.component_kind OR NEW.component_id <> OLD.component_id OR NEW.acked_epoch < OLD.acked_epoch OR NEW.acked_at < OLD.acked_at THEN RAISE EXCEPTION 'kill acknowledgements are monotonic'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_ack_guard BEFORE UPDATE OR DELETE ON control_kill_acks
    FOR EACH ROW EXECUTE FUNCTION control_kill_ack_guard();

GRANT SELECT, INSERT ON control_kill_events, control_kill_supersessions, control_kill_archive TO toolgate_control_runtime;
GRANT SELECT, INSERT, UPDATE ON control_kill_acks TO toolgate_control_runtime;

ALTER TABLE control_kill_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_events USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_events AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_events TO toolgate_control_platform;
ALTER TABLE control_kill_supersessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_supersessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_supersessions USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_supersessions AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_supersessions TO toolgate_control_platform;
ALTER TABLE control_kill_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_archive USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_archive AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_archive TO toolgate_control_platform;
ALTER TABLE control_kill_acks ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_acks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_acks USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_acks AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT,UPDATE ON control_kill_acks TO toolgate_control_platform;

CREATE TABLE control_invocation_kill_scopes(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,attempt_id TEXT NOT NULL,device_id TEXT NOT NULL,
 package_id TEXT,package_digest TEXT NOT NULL,tool_id TEXT NOT NULL,
 publisher_id TEXT,pool_id TEXT,image_digest TEXT,
 PRIMARY KEY(tenant_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(length(operation_id) BETWEEN 1 AND 128),CHECK(length(attempt_id) BETWEEN 1 AND 128),
 CHECK(length(device_id) BETWEEN 1 AND 128),CHECK(length(tool_id) BETWEEN 1 AND 128),
 CHECK(length(package_digest)=71),CHECK(package_id IS NULL OR length(package_id) BETWEEN 1 AND 128),
 CHECK(publisher_id IS NULL OR length(publisher_id) BETWEEN 1 AND 128),
 CHECK(pool_id IS NULL OR length(pool_id)<=128),CHECK(image_digest IS NULL OR length(image_digest) IN(0,71))
);
CREATE TRIGGER control_invocation_kill_scopes_immutable BEFORE UPDATE OR DELETE ON control_invocation_kill_scopes FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_invocation_kill_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_invocation_kill_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_invocation_kill_scopes USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_invocation_kill_scopes AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_invocation_kill_scopes TO toolgate_control_runtime,toolgate_control_platform;

CREATE TABLE control_kill_proposals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,requester_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('KILL','RECOVERY')),authorization_epoch BIGINT NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),document TEXT NOT NULL CHECK(octet_length(document)<=65536 AND jsonb_typeof(document::jsonb)='object'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 expires_at BIGINT NOT NULL CHECK(expires_at>created_at AND expires_at<=created_at+600000),
 PRIMARY KEY(tenant_id,proposal_id),CHECK(length(proposal_id) BETWEEN 1 AND 128),CHECK(length(requester_id) BETWEEN 1 AND 128)
);
CREATE TABLE control_kill_reviews(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,reviewer_id TEXT NOT NULL,
 approved BOOLEAN NOT NULL,reviewed_at BIGINT NOT NULL CHECK(reviewed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id,reviewer_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id)
);
CREATE TABLE control_kill_closures(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('APPLIED','REJECTED')),
 event_id TEXT,closed_at BIGINT NOT NULL CHECK(closed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK((state='APPLIED')=(event_id IS NOT NULL))
);
CREATE TRIGGER control_kill_proposals_immutable BEFORE UPDATE OR DELETE ON control_kill_proposals FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_proposals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_proposals USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_proposals AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_proposals TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER control_kill_reviews_immutable BEFORE UPDATE OR DELETE ON control_kill_reviews FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_reviews USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_reviews AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_reviews TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER control_kill_closures_immutable BEFORE UPDATE OR DELETE ON control_kill_closures FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_closures ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_closures FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_closures USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_closures AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_closures TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_kill_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.requester_id<>NEW.reviewer_id AND p.created_at<=NEW.reviewed_at AND p.expires_at>NEW.reviewed_at)
 OR EXISTS(SELECT 1 FROM control_kill_closures c WHERE c.tenant_id=NEW.tenant_id AND c.proposal_id=NEW.proposal_id)
 OR (SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id)>=2 THEN RAISE EXCEPTION 'independent current kill review required'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_review_guard BEFORE INSERT ON control_kill_reviews FOR EACH ROW EXECUTE FUNCTION control_kill_review_guard();
CREATE FUNCTION control_kill_closure_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.created_at<=NEW.closed_at AND p.expires_at>NEW.closed_at)
 OR (NEW.state='APPLIED' AND ((SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=true)<>2
 OR NOT EXISTS(SELECT 1 FROM control_kill_events e JOIN control_kill_proposals p ON p.tenant_id=e.tenant_id AND p.kind=e.event_kind
 WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND p.proposal_id=NEW.proposal_id AND e.issuer='control')))
 OR (NEW.state='REJECTED' AND NOT EXISTS(SELECT 1 FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=false)) THEN RAISE EXCEPTION 'independent current kill review required'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_closure_guard BEFORE INSERT ON control_kill_closures FOR EACH ROW EXECUTE FUNCTION control_kill_closure_guard();

CREATE TABLE control_kill_breakglass_requests(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),request_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),event_id TEXT NOT NULL,
 certificate_sha256 TEXT NOT NULL CHECK(length(certificate_sha256)=64),
 not_after BIGINT NOT NULL CHECK(not_after BETWEEN 1 AND 9007199254740991),
 applied_at BIGINT NOT NULL CHECK(applied_at>=0 AND applied_at<not_after),
 PRIMARY KEY(tenant_id,request_id),UNIQUE(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK(length(request_id) BETWEEN 1 AND 128)
);
CREATE TRIGGER control_kill_breakglass_requests_immutable BEFORE UPDATE OR DELETE ON control_kill_breakglass_requests FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_breakglass_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_breakglass_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_breakglass_requests USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_breakglass_requests AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_breakglass_requests TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_kill_breakglass_request_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(
 SELECT 1 FROM control_kill_events e WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND e.event_kind='KILL'
 AND e.issuer='control-breakglass' AND e.issued_at=NEW.applied_at)
 THEN RAISE EXCEPTION 'operator request requires its committed emergency kill'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_breakglass_request_guard BEFORE INSERT ON control_kill_breakglass_requests FOR EACH ROW EXECUTE FUNCTION control_kill_breakglass_request_guard();

CREATE TABLE control_kill_gateways(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),gateway_id TEXT NOT NULL,
 registered_at BIGINT NOT NULL CHECK(registered_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,gateway_id),CHECK(length(gateway_id)=64)
);
CREATE TRIGGER control_kill_gateways_immutable BEFORE UPDATE OR DELETE ON control_kill_gateways FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_gateways ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_gateways FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_gateways USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_gateways AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_gateways TO toolgate_control_runtime,toolgate_control_platform;
