-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- SPECIFICATION (gate D2). Ships unchanged as db/quickstart/V12.sql with the kill switch, with the
-- runner cap in SqliteState raised to 12. Same state as Flyway V19__kill_switch.sql.
CREATE TABLE control_kill_events(tenant_id TEXT NOT NULL,event_id TEXT NOT NULL,issuer TEXT NOT NULL CHECK(issuer IN('control','control-breakglass')),issuer_counter INTEGER NOT NULL CHECK(issuer_counter BETWEEN 1 AND 9999999999999999),event_kind TEXT NOT NULL CHECK(event_kind IN('KILL','RECOVERY')),scope_kind TEXT CHECK(scope_kind IN('PACKAGE','PACKAGE_DIGEST','PUBLISHER','TOOL','POOL','RUNTIME_IMAGE','TENANT')),scope_value TEXT CHECK(length(scope_value) BETWEEN 1 AND 256),mode TEXT CHECK(mode IN('STOP_NEW','TERMINATE')),authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch BETWEEN 1 AND 9007199254740991),issued_at INTEGER NOT NULL CHECK(issued_at BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536),PRIMARY KEY(tenant_id,event_id),UNIQUE(tenant_id,issuer,issuer_counter),CHECK(event_id=issuer||':'||issuer_counter),CHECK(event_kind='KILL' OR issuer='control'),CHECK((event_kind='KILL')=(scope_kind IS NOT NULL)),CHECK((event_kind='KILL')=(mode IS NOT NULL)),CHECK(scope_kind IS NULL OR (scope_kind='TENANT')=(scope_value IS NULL)));
-- statement
CREATE INDEX control_kill_event_epoch ON control_kill_events(tenant_id,authorization_epoch);
-- statement
CREATE TABLE control_kill_supersessions(tenant_id TEXT NOT NULL,kill_event_id TEXT NOT NULL,recovery_event_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kill_event_id,recovery_event_id),FOREIGN KEY(tenant_id,kill_event_id) REFERENCES control_kill_events(tenant_id,event_id),FOREIGN KEY(tenant_id,recovery_event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_archive(tenant_id TEXT NOT NULL,event_id TEXT NOT NULL,archived_at INTEGER NOT NULL CHECK(archived_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,event_id),FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_acks(tenant_id TEXT NOT NULL,component_kind TEXT NOT NULL CHECK(component_kind IN('TOOL_HOST','ENDPOINT','GATEWAY')),component_id TEXT NOT NULL CHECK(length(component_id) BETWEEN 1 AND 256),acked_epoch INTEGER NOT NULL CHECK(acked_epoch BETWEEN 0 AND 9007199254740991),acked_at INTEGER NOT NULL CHECK(acked_at BETWEEN 0 AND 9007199254740991),delivery_path TEXT NOT NULL CHECK(delivery_path IN('PUSH','CHECK_IN','POLL')),PRIMARY KEY(tenant_id,component_kind,component_id));
-- statement
CREATE TRIGGER control_kill_events_update BEFORE UPDATE ON control_kill_events BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_events_delete BEFORE DELETE ON control_kill_events BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersessions_update BEFORE UPDATE ON control_kill_supersessions BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersessions_delete BEFORE DELETE ON control_kill_supersessions BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_archive_update BEFORE UPDATE ON control_kill_archive BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_archive_delete BEFORE DELETE ON control_kill_archive BEGIN SELECT RAISE(ABORT,'kill events are grow-only'); END;
-- statement
CREATE TRIGGER control_kill_supersession_guard BEFORE INSERT ON control_kill_supersessions WHEN NOT EXISTS(SELECT 1 FROM control_kill_events WHERE tenant_id=NEW.tenant_id AND event_id=NEW.kill_event_id AND event_kind='KILL') OR NOT EXISTS(SELECT 1 FROM control_kill_events WHERE tenant_id=NEW.tenant_id AND event_id=NEW.recovery_event_id AND event_kind='RECOVERY') BEGIN SELECT RAISE(ABORT,'a recovery event supersedes kill events only'); END;
-- statement
CREATE TRIGGER control_kill_archive_guard BEFORE INSERT ON control_kill_archive WHEN NOT EXISTS(SELECT 1 FROM control_kill_supersessions WHERE tenant_id=NEW.tenant_id AND (kill_event_id=NEW.event_id OR recovery_event_id=NEW.event_id)) BEGIN SELECT RAISE(ABORT,'only superseded kills and applied recoveries are archived'); END;
-- statement
CREATE TRIGGER control_kill_ack_update BEFORE UPDATE ON control_kill_acks WHEN NEW.acked_epoch<OLD.acked_epoch BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;
-- statement
CREATE TRIGGER control_kill_ack_delete BEFORE DELETE ON control_kill_acks BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;
