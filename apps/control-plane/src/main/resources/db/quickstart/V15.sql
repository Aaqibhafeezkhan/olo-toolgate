-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
ALTER TABLE control_tenants ADD COLUMN directory_cache_generation INTEGER NOT NULL DEFAULT 0 CHECK(directory_cache_generation>=0);
-- statement
CREATE TRIGGER control_records_cache_insert AFTER INSERT ON control_records BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(NEW.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
-- statement
CREATE TRIGGER control_records_cache_update AFTER UPDATE ON control_records BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(OLD.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) SELECT NEW.tenant_id,0,1 WHERE NEW.tenant_id<>OLD.tenant_id ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
-- statement
CREATE TRIGGER control_records_cache_delete AFTER DELETE ON control_records BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(OLD.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
-- statement
CREATE TRIGGER control_settings_cache_insert AFTER INSERT ON control_server_settings BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(NEW.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
-- statement
CREATE TRIGGER control_settings_cache_update AFTER UPDATE ON control_server_settings BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(OLD.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) SELECT NEW.tenant_id,0,1 WHERE NEW.tenant_id<>OLD.tenant_id ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
-- statement
CREATE TRIGGER control_settings_cache_delete AFTER DELETE ON control_server_settings BEGIN
 INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(OLD.tenant_id,0,1) ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=directory_cache_generation+1;
END;
