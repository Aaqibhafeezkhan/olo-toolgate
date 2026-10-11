-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- SPECIFICATION (gate D2). Ships unchanged as db/migration/V19__kill_switch.sql with the kill switch;
-- only the version number may change if another migration lands first.
-- Grow-only signed kill and recovery events (plan.md §15). Normative description:
-- docs/control-plane/kill-switch.md.

CREATE TABLE control_kill_events (
    tenant_id varchar(128) NOT NULL,
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
    CHECK (event_id = issuer || ':' || issuer_counter::text),
    CHECK (event_kind = 'KILL' OR issuer = 'control'),
    CHECK ((event_kind = 'KILL') = (scope_kind IS NOT NULL)),
    CHECK ((event_kind = 'KILL') = (mode IS NOT NULL)),
    CHECK (scope_kind IS NULL OR (scope_kind = 'TENANT') = (scope_value IS NULL))
);
CREATE INDEX control_kill_event_epoch ON control_kill_events (tenant_id, authorization_epoch);

-- One row per (recovery, kill) it supersedes; a kill is effective while it has no row here.
CREATE TABLE control_kill_supersessions (
    tenant_id varchar(128) NOT NULL,
    kill_event_id varchar(40) NOT NULL,
    recovery_event_id varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, kill_event_id, recovery_event_id),
    FOREIGN KEY (tenant_id, kill_event_id) REFERENCES control_kill_events (tenant_id, event_id),
    FOREIGN KEY (tenant_id, recovery_event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Superseded events move out of the push set into the audited archive; the event rows stay.
CREATE TABLE control_kill_archive (
    tenant_id varchar(128) NOT NULL,
    event_id varchar(40) NOT NULL,
    archived_at bigint NOT NULL CHECK (archived_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Highest authorization epoch each component has acknowledged, and the path that delivered it.
CREATE TABLE control_kill_acks (
    tenant_id varchar(128) NOT NULL,
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
    IF NOT EXISTS (SELECT 1 FROM control_kill_events WHERE tenant_id = NEW.tenant_id AND event_id = NEW.kill_event_id AND event_kind = 'KILL')
       OR NOT EXISTS (SELECT 1 FROM control_kill_events WHERE tenant_id = NEW.tenant_id AND event_id = NEW.recovery_event_id AND event_kind = 'RECOVERY') THEN
        RAISE EXCEPTION 'a recovery event supersedes kill events only';
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
    IF TG_OP = 'DELETE' OR NEW.acked_epoch < OLD.acked_epoch THEN RAISE EXCEPTION 'kill acknowledgements are monotonic'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_ack_guard BEFORE UPDATE OR DELETE ON control_kill_acks
    FOR EACH ROW EXECUTE FUNCTION control_kill_ack_guard();

GRANT SELECT, INSERT ON control_kill_events, control_kill_supersessions, control_kill_archive TO toolgate_control_runtime;
GRANT SELECT, INSERT, UPDATE ON control_kill_acks TO toolgate_control_runtime;
