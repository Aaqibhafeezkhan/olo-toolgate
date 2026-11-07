-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Current fresh-schema kill storage, mirrored in initial V1.
CREATE TABLE control_kill_events(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),event_id TEXT NOT NULL,issuer TEXT NOT NULL CHECK(issuer IN('control','control-breakglass')),issuer_counter INTEGER NOT NULL CHECK(issuer_counter BETWEEN 1 AND 9999999999999999),event_kind TEXT NOT NULL CHECK(event_kind IN('KILL','RECOVERY')),scope_kind TEXT CHECK(scope_kind IN('PACKAGE','PACKAGE_DIGEST','PUBLISHER','TOOL','POOL','RUNTIME_IMAGE','TENANT')),scope_value TEXT CHECK(length(scope_value) BETWEEN 1 AND 256),mode TEXT CHECK(mode IN('STOP_NEW','TERMINATE')),authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch BETWEEN 1 AND 9007199254740991),issued_at INTEGER NOT NULL CHECK(issued_at BETWEEN 0 AND 9007199254740991),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536),PRIMARY KEY(tenant_id,event_id),UNIQUE(tenant_id,issuer,issuer_counter),UNIQUE(tenant_id,authorization_epoch),CHECK(event_id=issuer||':'||issuer_counter),CHECK(event_kind='KILL' OR issuer='control'),CHECK((event_kind='KILL')=(scope_kind IS NOT NULL)),CHECK((event_kind='KILL')=(mode IS NOT NULL)),CHECK(scope_kind IS NULL OR (scope_kind='TENANT')=(scope_value IS NULL)));
-- statement
CREATE INDEX control_kill_event_epoch ON control_kill_events(tenant_id,authorization_epoch);
-- statement
CREATE TABLE control_kill_supersessions(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),kill_event_id TEXT NOT NULL,recovery_event_id TEXT NOT NULL,PRIMARY KEY(tenant_id,kill_event_id,recovery_event_id),FOREIGN KEY(tenant_id,kill_event_id) REFERENCES control_kill_events(tenant_id,event_id),FOREIGN KEY(tenant_id,recovery_event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_archive(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),event_id TEXT NOT NULL,archived_at INTEGER NOT NULL CHECK(archived_at BETWEEN 0 AND 9007199254740991),PRIMARY KEY(tenant_id,event_id),FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id));
-- statement
CREATE TABLE control_kill_acks(tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),component_kind TEXT NOT NULL CHECK(component_kind IN('TOOL_HOST','ENDPOINT','GATEWAY')),component_id TEXT NOT NULL CHECK(length(component_id) BETWEEN 1 AND 256),acked_epoch INTEGER NOT NULL CHECK(acked_epoch BETWEEN 0 AND 9007199254740991),acked_at INTEGER NOT NULL CHECK(acked_at BETWEEN 0 AND 9007199254740991),delivery_path TEXT NOT NULL CHECK(delivery_path IN('PUSH','CHECK_IN','POLL')),PRIMARY KEY(tenant_id,component_kind,component_id));
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
CREATE TRIGGER control_kill_supersession_guard BEFORE INSERT ON control_kill_supersessions WHEN NOT EXISTS(
 SELECT 1 FROM control_kill_events k JOIN control_kill_events r ON r.tenant_id=k.tenant_id
 WHERE k.tenant_id=NEW.tenant_id AND k.event_id=NEW.kill_event_id AND k.event_kind='KILL'
 AND r.event_id=NEW.recovery_event_id AND r.event_kind='RECOVERY' AND r.authorization_epoch>k.authorization_epoch
 AND EXISTS(SELECT 1 FROM json_each(r.document,'$.supersedes') s WHERE s.type='text' AND s.value=k.event_id))
 BEGIN SELECT RAISE(ABORT,'a signed later recovery must name its superseded kill'); END;
-- statement
CREATE TRIGGER control_kill_archive_guard BEFORE INSERT ON control_kill_archive WHEN NOT EXISTS(SELECT 1 FROM control_kill_supersessions WHERE tenant_id=NEW.tenant_id AND (kill_event_id=NEW.event_id OR recovery_event_id=NEW.event_id)) BEGIN SELECT RAISE(ABORT,'only superseded kills and applied recoveries are archived'); END;
-- statement
CREATE TRIGGER control_kill_ack_update BEFORE UPDATE ON control_kill_acks WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.component_kind<>OLD.component_kind OR NEW.component_id<>OLD.component_id OR NEW.acked_epoch<OLD.acked_epoch OR NEW.acked_at<OLD.acked_at BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;
-- statement
CREATE TRIGGER control_kill_ack_delete BEFORE DELETE ON control_kill_acks BEGIN SELECT RAISE(ABORT,'kill acknowledgements are monotonic'); END;

-- statement
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
-- statement
CREATE TRIGGER control_invocation_kill_scopes_update BEFORE UPDATE ON control_invocation_kill_scopes BEGIN SELECT RAISE(ABORT,'execution kill scope is immutable'); END;
-- statement
CREATE TRIGGER control_invocation_kill_scopes_delete BEFORE DELETE ON control_invocation_kill_scopes BEGIN SELECT RAISE(ABORT,'execution kill scope is immutable'); END;
-- statement

-- statement
CREATE TABLE control_kill_proposals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,requester_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('KILL','RECOVERY')),authorization_epoch BIGINT NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=65536 AND json_valid(document) AND json_type(document)='object'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 expires_at BIGINT NOT NULL CHECK(expires_at>created_at AND expires_at<=created_at+600000),
 PRIMARY KEY(tenant_id,proposal_id),CHECK(length(proposal_id) BETWEEN 1 AND 128),CHECK(length(requester_id) BETWEEN 1 AND 128)
);
-- statement
CREATE TABLE control_kill_reviews(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,reviewer_id TEXT NOT NULL,
 approved BOOLEAN NOT NULL CHECK(approved IN(0,1)),reviewed_at BIGINT NOT NULL CHECK(reviewed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id,reviewer_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id)
);
-- statement
CREATE TABLE control_kill_closures(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('APPLIED','REJECTED')),
 event_id TEXT,closed_at BIGINT NOT NULL CHECK(closed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK((state='APPLIED')=(event_id IS NOT NULL))
);
-- statement
CREATE TRIGGER control_kill_proposals_update BEFORE UPDATE ON control_kill_proposals BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_proposals_delete BEFORE DELETE ON control_kill_proposals BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_reviews_update BEFORE UPDATE ON control_kill_reviews BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_reviews_delete BEFORE DELETE ON control_kill_reviews BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_closures_update BEFORE UPDATE ON control_kill_closures BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_closures_delete BEFORE DELETE ON control_kill_closures BEGIN SELECT RAISE(ABORT,'kill review is immutable'); END;
-- statement
CREATE TRIGGER control_kill_review_guard BEFORE INSERT ON control_kill_reviews WHEN NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.requester_id<>NEW.reviewer_id AND p.created_at<=NEW.reviewed_at AND p.expires_at>NEW.reviewed_at)
 OR EXISTS(SELECT 1 FROM control_kill_closures c WHERE c.tenant_id=NEW.tenant_id AND c.proposal_id=NEW.proposal_id)
 OR (SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id)>=2 BEGIN SELECT RAISE(ABORT,'independent current kill review required'); END;
-- statement
CREATE TRIGGER control_kill_closure_guard BEFORE INSERT ON control_kill_closures WHEN NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.created_at<=NEW.closed_at AND p.expires_at>NEW.closed_at)
 OR (NEW.state='APPLIED' AND ((SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=true)<>2
 OR NOT EXISTS(SELECT 1 FROM control_kill_events e JOIN control_kill_proposals p ON p.tenant_id=e.tenant_id AND p.kind=e.event_kind
 WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND p.proposal_id=NEW.proposal_id AND e.issuer='control')))
 OR (NEW.state='REJECTED' AND NOT EXISTS(SELECT 1 FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=false)) BEGIN SELECT RAISE(ABORT,'independent current kill review required'); END;
-- statement

-- statement
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
-- statement
CREATE TRIGGER control_kill_breakglass_requests_update BEFORE UPDATE ON control_kill_breakglass_requests BEGIN SELECT RAISE(ABORT,'operator request is immutable'); END;
-- statement
CREATE TRIGGER control_kill_breakglass_requests_delete BEFORE DELETE ON control_kill_breakglass_requests BEGIN SELECT RAISE(ABORT,'operator request is immutable'); END;
-- statement
CREATE TRIGGER control_kill_breakglass_request_guard BEFORE INSERT ON control_kill_breakglass_requests WHEN NOT EXISTS(
 SELECT 1 FROM control_kill_events e WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND e.event_kind='KILL'
 AND e.issuer='control-breakglass' AND e.issued_at=NEW.applied_at)
 BEGIN SELECT RAISE(ABORT,'operator request requires its committed emergency kill'); END;

-- statement
CREATE TABLE control_kill_gateways(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),gateway_id TEXT NOT NULL,
 registered_at BIGINT NOT NULL CHECK(registered_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,gateway_id),CHECK(length(gateway_id)=64)
);
-- statement
CREATE TRIGGER control_kill_gateways_update BEFORE UPDATE ON control_kill_gateways BEGIN SELECT RAISE(ABORT,'gateway revocation identity is immutable'); END;
-- statement
CREATE TRIGGER control_kill_gateways_delete BEFORE DELETE ON control_kill_gateways BEGIN SELECT RAISE(ABORT,'gateway revocation identity is immutable'); END;
