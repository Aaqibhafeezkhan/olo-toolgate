# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""One-time configuration-file conversion. The application does not accept legacy rules.

Export before upgrading, convert the file, review its diff, then use normal configuration
validation/import. This utility never connects to a database or changes a running demo.
"""
import argparse
import json
from pathlib import Path
from permissions import catalog

SPECIAL = {
    'manage-role': ['role.create','role.update','role.delete','role.enable','role.disable','role.manage_permissions','team.assign_roles','agent_group.assign_roles'],
    'grant': ['access_grant.create','access_grant.update','access_grant.delete','access_grant.enable','access_grant.disable','access_grant.grant'],
    'audit': ['audit.read'], 'simulate': ['simulation.run'],
    'export': ['configuration_backup.export'], 'import': ['configuration_backup.import'],
    'publish': ['access_snapshot.publish','access_snapshot.rollback','package.publish'],
    'recover': ['administration.recover'],
    'approve-device': ['device.approve','device.enroll'],
    'revoke-device': ['device.revoke'], 'revoke': ['device.revoke'],
    'approve-operation': ['operation.approve'], 'cancel-operation': ['operation.cancel'],
    'approve-configuration': ['configuration_change.approve','configuration_change.apply'],
    'build': ['tool_builder.build'], 'deploy': ['package.deploy'],
    'vault-read': ['secret.read','secret.read_value'], 'vault-write': ['secret.write'],
    'attest': ['device_evidence.attest'],
    'rotate-credential': ['user.rotate_credential','agent.rotate_credential','workload_binding.rotate_credential','identity_binding.rotate_credential'],
    'policy': ['policy.create','policy.update','policy.delete','policy.enable','policy.disable'],
}


def expand(actions):
    resources = catalog()['resources']
    known = {f"{r['id']}.{a}" for r in resources for a in r['actions']}
    result = set()
    for action in actions:
        if action in known:
            result.add(action)
        elif action in SPECIAL:
            result.update(SPECIAL[action])
        elif action in ('read','create','update','delete','enable','disable'):
            result.update(f"{r['id']}.{action}" for r in resources if action in r['actions'])
            if action == 'read':
                result.add('configuration_backup.read')
            if action == 'update':
                result.update(f"{r['id']}.manage_members" for r in resources if 'manage_members' in r['actions'])
        else:
            raise ValueError(f'Unrecognized legacy management action: {action}')
    return sorted(result)


def migrate(value):
    if isinstance(value, dict):
        ownership=any(rule.get('ownDevices') is True for rule in value.get('managementRules',[]))
        for rule in value.get('managementRules', []):
            own = rule.pop('ownDevices', False)
            rule['actions'] = expand(rule['actions'])
            if own:
                rule['deviceScope'] = 'OWNED'
                rule['actions'] = ['device.approve','device.enroll','device.read']
            elif ownership:
                rule['actions']=[p for p in rule['actions'] if p!='device.read']
        for child in value.values():
            migrate(child)
    elif isinstance(value, list):
        for child in value:
            migrate(child)
    return value


def write_migrations():
    """Author the v1 vocabulary upgrade once; runtime never interprets old actions."""
    root=Path(__file__).resolve().parents[2]
    mapping={action:expand([action]) for action in sorted(set(SPECIAL)|{'read','create','update','delete','enable','disable'})}
    raw=json.dumps(mapping,separators=(',',':')).replace("'","''")
    known=json.dumps(sorted(expand([f"{r['id']}.{a}" for r in catalog()['resources'] for a in r['actions']])),separators=(',',':'))
    header='-- Copyright 2026 OLO Labs\n-- SPDX-License-Identifier: Apache-2.0\n-- GENERATED one-time vocabulary upgrade by tools/access/migrate_management.py.\n-- Ownership-limited roles retain private device visibility; explicit demo profiles are applied normally.\n'
    owned="EXISTS(SELECT 1 FROM jsonb_array_elements(r.document->'managementRules') o WHERE o->>'ownDevices'='true')"
    converted=f"""CASE WHEN rule->>'ownDevices'='true' THEN '["device.approve","device.enroll","device.read"]'::jsonb ELSE COALESCE((SELECT jsonb_agg(permission ORDER BY permission) FROM (SELECT DISTINCT p.value AS permission FROM jsonb_array_elements_text(rule->'actions') a CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE('{raw}'::jsonb->a.value,jsonb_build_array(a.value))) p WHERE NOT ({owned} AND p.value='device.read')) permissions),'[]'::jsonb) END"""
    needs="EXISTS(SELECT 1 FROM jsonb_array_elements(document->'managementRules') rule WHERE rule ? 'ownDevices' OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(rule->'actions') a WHERE strpos(a.value,'.')=0))"
    pg=header+f"""DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM control_records r CROSS JOIN LATERAL jsonb_array_elements(r.document->'managementRules') rule CROSS JOIN LATERAL jsonb_array_elements_text(rule->'actions') a WHERE r.kind='ROLE' AND NOT ('{raw}'::jsonb ? a.value) AND NOT ('{known}'::jsonb ? a.value)) THEN RAISE EXCEPTION 'Unknown management actions require explicit migration'; END IF;
END $$;
CREATE TEMP TABLE management_upgrade_tenants ON COMMIT DROP AS SELECT DISTINCT tenant_id FROM control_records WHERE kind='ROLE' AND {needs};
UPDATE control_records r SET revision=r.revision+1,document=jsonb_set(jsonb_set(r.document,'{{revision}}',to_jsonb(r.revision+1)),'{{managementRules}}',COALESCE((SELECT jsonb_agg((rule-'ownDevices')||jsonb_build_object('actions',{converted})||CASE WHEN rule->>'ownDevices'='true' THEN '{{"deviceScope":"OWNED"}}'::jsonb ELSE '{{}}'::jsonb END) FROM jsonb_array_elements(r.document->'managementRules') rule),'[]'::jsonb)) WHERE r.kind='ROLE' AND {needs};
UPDATE control_tenants SET revision=revision+1,authorization_epoch=authorization_epoch+1 WHERE tenant_id IN (SELECT tenant_id FROM management_upgrade_tenants);
INSERT INTO control_authorization_outbox(tenant_id,revision,authorization_epoch,occurred_at) SELECT tenant_id,revision,authorization_epoch,(extract(epoch from clock_timestamp())*1000)::bigint FROM control_tenants WHERE tenant_id IN (SELECT tenant_id FROM management_upgrade_tenants);
"""
    (root/'apps/control-plane/src/main/resources/db/migration/V24__feature_management_permissions.sql').write_text(pg,encoding='utf-8',newline='\n')
    owned_sqlite="EXISTS(SELECT 1 FROM json_each(r.document,'$.managementRules') owned WHERE json_extract(owned.value,'$.ownDevices')=1)"
    actions=f"""CASE WHEN json_extract(rule.value,'$.ownDevices')=1 THEN '["device.approve","device.enroll","device.read"]' ELSE COALESCE((SELECT json_group_array(permission) FROM (SELECT DISTINCT p.value AS permission FROM json_each(rule.value,'$.actions') a JOIN json_each(COALESCE(json_extract('{raw}','$.'||'"'||a.value||'"'),json_array(a.value))) p WHERE NOT ({owned_sqlite} AND p.value='device.read') ORDER BY permission)),'[]') END"""
    need_sqlite="EXISTS(SELECT 1 FROM json_each(document,'$.managementRules') rule WHERE json_type(rule.value,'$.ownDevices') IS NOT NULL OR EXISTS(SELECT 1 FROM json_each(rule.value,'$.actions') a WHERE instr(a.value,'.')=0))"
    sqlite=header+f"""CREATE TEMP TABLE management_upgrade_valid(value INTEGER CHECK(value=0));
-- statement
INSERT INTO management_upgrade_valid SELECT count(*) FROM control_records r JOIN json_each(r.document,'$.managementRules') rule JOIN json_each(rule.value,'$.actions') a WHERE r.kind='ROLE' AND json_type('{raw}','$.'||'"'||a.value||'"') IS NULL AND a.value NOT IN(SELECT value FROM json_each('{known}'));
-- statement
DROP TABLE management_upgrade_valid;
-- statement
CREATE TEMP TABLE management_upgrade_tenants AS SELECT DISTINCT tenant_id FROM control_records WHERE kind='ROLE' AND {need_sqlite};
-- statement
UPDATE control_records AS r SET revision=revision+1,document=json_set(document,'$.revision',revision+1,'$.managementRules',json(COALESCE((SELECT json_group_array(json(json_patch(json_set(json_remove(rule.value,'$.ownDevices'),'$.actions',json({actions})),json_object('deviceScope',CASE WHEN json_extract(rule.value,'$.ownDevices')=1 THEN 'OWNED' ELSE json_extract(rule.value,'$.deviceScope') END)))) FROM json_each(r.document,'$.managementRules') rule),'[]'))) WHERE kind='ROLE' AND {need_sqlite};
-- statement
UPDATE control_tenants SET revision=revision+1,authorization_epoch=authorization_epoch+1 WHERE tenant_id IN(SELECT tenant_id FROM management_upgrade_tenants);
-- statement
INSERT INTO control_authorization_outbox(tenant_id,revision,authorization_epoch,occurred_at) SELECT tenant_id,revision,authorization_epoch,CAST(strftime('%s','now') AS INTEGER)*1000 FROM control_tenants WHERE tenant_id IN(SELECT tenant_id FROM management_upgrade_tenants);
-- statement
DROP TABLE management_upgrade_tenants;
"""
    (root/'apps/control-plane/src/main/resources/db/quickstart/V17.sql').write_text(sqlite,encoding='utf-8',newline='\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('files', nargs='*', type=Path)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--write-migrations', action='store_true')
    args = parser.parse_args()
    if args.write_migrations:
        write_migrations()
    for path in args.files:
        original = path.read_text(encoding='utf-8')
        value = json.loads(original)
        converted = json.dumps(migrate(value), indent=2) + '\n'
        if args.write:
            path.write_text(converted, encoding='utf-8', newline='\n')
        else:
            print(converted, end='')


if __name__ == '__main__':
    main()
