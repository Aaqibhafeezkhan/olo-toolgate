-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Separate from configuration revision: identity observations update records without changing that revision.
ALTER TABLE control_tenants ADD COLUMN directory_cache_generation bigint NOT NULL DEFAULT 0 CHECK(directory_cache_generation>=0);
CREATE FUNCTION control_invalidate_directory_cache() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(OLD.tenant_id,0,1)
        ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=control_tenants.directory_cache_generation+1;
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.tenant_id<>OLD.tenant_id) THEN
        INSERT INTO control_tenants(tenant_id,revision,directory_cache_generation) VALUES(NEW.tenant_id,0,1)
        ON CONFLICT(tenant_id) DO UPDATE SET directory_cache_generation=control_tenants.directory_cache_generation+1;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER control_records_cache_generation AFTER INSERT OR UPDATE OR DELETE ON control_records
FOR EACH ROW EXECUTE FUNCTION control_invalidate_directory_cache();
CREATE TRIGGER control_settings_cache_generation AFTER INSERT OR UPDATE OR DELETE ON control_server_settings
FOR EACH ROW EXECUTE FUNCTION control_invalidate_directory_cache();
