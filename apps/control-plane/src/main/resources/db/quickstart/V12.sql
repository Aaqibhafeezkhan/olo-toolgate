-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE control_bootstrap_admins(tenant_id TEXT PRIMARY KEY,user_id TEXT NOT NULL);
-- statement
INSERT INTO control_bootstrap_admins(tenant_id,user_id)
SELECT b.tenant_id,json_extract(b.document,'$.userId')
FROM control_records b JOIN control_records u ON u.tenant_id=b.tenant_id
    AND u.kind='USER' AND u.record_id=json_extract(b.document,'$.userId')
WHERE b.kind='IDENTITY_BINDING' AND b.record_id='installed-admin'
    AND json_extract(b.document,'$.registrationReason')='REVIEWED_INSTALLATION'
    AND json_extract(b.document,'$.subject')='admin' AND json_extract(u.document,'$.enabled')=1;
-- statement
CREATE TRIGGER control_bootstrap_admin_update BEFORE UPDATE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
-- statement
CREATE TRIGGER control_bootstrap_admin_delete BEFORE DELETE ON control_bootstrap_admins
BEGIN SELECT RAISE(ABORT,'setup administrator is immutable'); END;
