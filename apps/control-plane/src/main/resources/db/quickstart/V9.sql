-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_effect_consents(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,operation_id TEXT NOT NULL,
 preview_digest TEXT NOT NULL CHECK(length(preview_digest)=71 AND substr(preview_digest,1,7)='sha256:' AND substr(preview_digest,8) NOT GLOB '*[^a-f0-9]*'),document TEXT NOT NULL CHECK(length(CAST(document AS BLOB))<=2097152 AND json_valid(document) AND json_type(document)='object'),
 not_before INTEGER NOT NULL CHECK(not_before BETWEEN 0 AND 9007199254740991),
 expires_at INTEGER NOT NULL CHECK(expires_at>not_before AND expires_at<=9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES control_enterprise_invocations(tenant_id,invocation_id)
);
-- statement
CREATE TRIGGER consents_immutable_update BEFORE UPDATE ON control_effect_consents BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consents_immutable_delete BEFORE DELETE ON control_effect_consents BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consents_capacity AFTER INSERT ON control_effect_consents BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(CAST(NEW.document AS BLOB)) WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TABLE control_effect_consent_slots(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,slot_index INTEGER NOT NULL CHECK(slot_index BETWEEN 0 AND 63),
 route_id TEXT NOT NULL,mapped_digest TEXT NOT NULL CHECK(length(mapped_digest)=71 AND substr(mapped_digest,1,7)='sha256:' AND substr(mapped_digest,8) NOT GLOB '*[^a-f0-9]*'),permitted_count INTEGER NOT NULL CHECK(permitted_count BETWEEN 1 AND 64),
 PRIMARY KEY(tenant_id,consent_id,slot_index),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TRIGGER consent_slots_immutable_update BEFORE UPDATE ON control_effect_consent_slots BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_slots_immutable_delete BEFORE DELETE ON control_effect_consent_slots BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_slots_capacity AFTER INSERT ON control_effect_consent_slots BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TABLE control_effect_consent_approvals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,reviewer TEXT NOT NULL,
 authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 approved_at INTEGER NOT NULL CHECK(approved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TRIGGER consent_approvals_immutable_update BEFORE UPDATE ON control_effect_consent_approvals BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_approvals_immutable_delete BEFORE DELETE ON control_effect_consent_approvals BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_approvals_capacity AFTER INSERT ON control_effect_consent_approvals BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TABLE control_effect_consent_revocations(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,reviewer TEXT NOT NULL,
 revoked_at INTEGER NOT NULL CHECK(revoked_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,consent_id),FOREIGN KEY(tenant_id,consent_id) REFERENCES control_effect_consents(tenant_id,consent_id)
);
-- statement
CREATE TRIGGER consent_revocations_immutable_update BEFORE UPDATE ON control_effect_consent_revocations BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_revocations_immutable_delete BEFORE DELETE ON control_effect_consent_revocations BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_revocations_capacity AFTER INSERT ON control_effect_consent_revocations BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TABLE control_effect_consent_uses(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),consent_id TEXT NOT NULL,slot_index INTEGER NOT NULL,effect_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=71 AND substr(request_digest,1,7)='sha256:' AND substr(request_digest,8) NOT GLOB '*[^a-f0-9]*'),reserved_at INTEGER NOT NULL CHECK(reserved_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,effect_id),FOREIGN KEY(tenant_id,consent_id,slot_index) REFERENCES control_effect_consent_slots(tenant_id,consent_id,slot_index),
 FOREIGN KEY(tenant_id,effect_id) REFERENCES control_effects(tenant_id,effect_id)
);
-- statement
CREATE TRIGGER consent_uses_immutable_update BEFORE UPDATE ON control_effect_consent_uses BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_uses_immutable_delete BEFORE DELETE ON control_effect_consent_uses BEGIN SELECT RAISE(ABORT,'effect consent is immutable'); END;
-- statement
CREATE TRIGGER consent_uses_capacity AFTER INSERT ON control_effect_consent_uses BEGIN UPDATE control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id; SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity unavailable') END; END;
-- statement
CREATE TRIGGER consent_use_guard BEFORE INSERT ON control_effect_consent_uses WHEN NOT EXISTS(SELECT 1 FROM control_effect_consent_slots s
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
 AND (SELECT count(*) FROM control_effect_consent_uses u WHERE u.tenant_id=s.tenant_id AND u.consent_id=s.consent_id AND u.slot_index=s.slot_index)<s.permitted_count) BEGIN SELECT RAISE(ABORT,'effect consent scope or allowance refused'); END;
-- statement
CREATE TRIGGER consent_approval_guard BEFORE INSERT ON control_effect_consent_approvals WHEN NOT EXISTS(SELECT 1 FROM control_effect_consents c JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id
 WHERE c.tenant_id=NEW.tenant_id AND c.consent_id=NEW.consent_id AND t.authorization_epoch=NEW.authorization_epoch
 AND c.not_before<=NEW.approved_at AND c.expires_at>NEW.approved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)) BEGIN SELECT RAISE(ABORT,'effect consent scope or allowance refused'); END;
