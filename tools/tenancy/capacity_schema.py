# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Generate reviewed first-release V1 capacity accounting triggers for both stores."""
import argparse
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
START='-- BEGIN TENANT CAPACITY\n'
END='-- END TENANT CAPACITY\n'
TRACKED={
 'control_builder':(['document'],"{row}.kind='TEST' AND {state} IN ('QUEUED','RUNNING')"),
 'control_mcp_requests':(['record','task','result'],"{row}.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED')"),
 'control_enterprise_invocations':(['document'],"{row}.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','EXECUTING')"),
 'control_tool_host_dispatches':(['document'],"{row}.state IN ('QUEUED','CLAIMED','ADMITTED')"),
 'control_server_packages':(['document','content'],None),
 'control_server_documents':(['document'],None),
 'control_secrets':(['cipher'],None),
 'control_invocation_events':(['document'],None),
}

def expression(row,fields,queued,postgres):
    length='octet_length' if postgres else 'length'
    values=[f"COALESCE({length}({row}.{field}),0)" if postgres else f"COALESCE(length(CAST({row}.{field} AS BLOB)),0)" for field in fields]
    state=f"({row}.document::jsonb->>'state')" if postgres else f"json_extract({row}.document,'$.state')"
    queue='0' if queued is None else 'CASE WHEN '+queued.format(row=row,state=state)+' THEN 1 ELSE 0 END'
    return '+'.join(values),queue

def block(postgres):
    integer='bigint' if postgres else 'INTEGER';text='varchar(128)' if postgres else 'TEXT'
    owner='' if postgres else ' REFERENCES control_tenants(tenant_id)'
    statements=[f'''CREATE TABLE control_tenant_capacity(
 tenant_id {text} NOT NULL PRIMARY KEY{owner},
 stored_bytes {integer} NOT NULL DEFAULT 0 CHECK(stored_bytes>=0),
 stored_rows {integer} NOT NULL DEFAULT 0 CHECK(stored_rows>=0),
 queued_rows {integer} NOT NULL DEFAULT 0 CHECK(queued_rows>=0),
 max_stored_bytes {integer} NOT NULL DEFAULT 1073741824 CHECK(max_stored_bytes BETWEEN 4096 AND 1099511627776),
 max_stored_rows {integer} NOT NULL DEFAULT 100000 CHECK(max_stored_rows BETWEEN 1 AND 1000000),
 max_queued_rows {integer} NOT NULL DEFAULT 256 CHECK(max_queued_rows BETWEEN 1 AND 100000),
 CHECK(stored_rows<=max_stored_rows AND queued_rows<=max_queued_rows AND stored_bytes+queued_rows*16777216<=max_stored_bytes));''']
    if postgres:
        statements+=['GRANT SELECT ON control_tenant_capacity TO toolgate_control_runtime;',
                     'GRANT SELECT,INSERT,UPDATE,DELETE ON control_tenant_capacity TO toolgate_control_platform;',
'''CREATE FUNCTION control_capacity_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN INSERT INTO public.control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id);RETURN NEW;END;$$;
REVOKE ALL ON FUNCTION control_capacity_seed() FROM PUBLIC;
CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants FOR EACH ROW EXECUTE FUNCTION control_capacity_seed();''']
    else:
        statements+=['CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants BEGIN INSERT INTO control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id); END;']
    for table,(fields,queued) in TRACKED.items():
        new_bytes,new_queue=expression('NEW',fields,queued,postgres);old_bytes,old_queue=expression('OLD',fields,queued,postgres)
        if postgres:
            statements.append(f'''CREATE FUNCTION {table}_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=({new_bytes});rows_delta:=1;queue_delta:=({new_queue});END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-({old_bytes});rows_delta:=rows_delta-1;queue_delta:=queue_delta-({old_queue});END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION {table}_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON {table} FOR EACH ROW EXECUTE FUNCTION {table}_capacity();''')
        else:
            for operation,row,bytes_delta,rows_delta,queue_delta in [
                ('INSERT','NEW',new_bytes,'1',new_queue),
                ('DELETE','OLD','-('+old_bytes+')','-1','-('+old_queue+')'),
                ('UPDATE','NEW','('+new_bytes+')-('+old_bytes+')','0','('+new_queue+')-('+old_queue+')')]:
                statements.append(f'''CREATE TRIGGER {table}_capacity_{operation.lower()} AFTER {operation} ON {table} BEGIN
 UPDATE control_tenant_capacity SET stored_bytes=stored_bytes+({bytes_delta}),stored_rows=stored_rows+({rows_delta}),queued_rows=queued_rows+({queue_delta}) WHERE tenant_id={row}.tenant_id;
 SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'tenant capacity missing') END;
END;''')
    return START+('\n\n' if postgres else '\n-- statement\n').join(statements)+'\n'+END

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    for postgres,filename in [(True,'migration/V1__initial_schema.sql'),(False,'quickstart/V1.sql')]:
        path=ROOT/'apps/control-plane/src/main/resources/db'/filename;source=path.read_text()
        if START in source:
            a=source.index(START);b=source.index(END,a)+len(END);updated=source[:a]+block(postgres)+source[b:]
        elif postgres:
            at=source.index('DO $tenant_policies$');updated=source[:at]+block(True)+'\n'+source[at:]
            updated=updated.replace("        ,'control_tenant_identity_keys'","        ,'control_tenant_identity_keys'\n        ,'control_tenant_capacity'")
        else:updated=source+'\n-- statement\n'+block(False)
        if args.check:
            if source!=updated:raise SystemExit('Capacity schema drift: '+filename)
        else:path.write_text(updated)
    print('Tenant capacity schema blocks verified' if args.check else 'Tenant capacity V1 blocks generated')

if __name__=='__main__':main()
