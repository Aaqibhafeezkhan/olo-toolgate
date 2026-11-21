# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Render the maintained bot/resource matrix directly from the checked-in configurations."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def codes(values):
    return ', '.join('`'+value+'`' for value in sorted(set(values))) or 'None'


def render():
    rows = []
    for folder in sorted((ROOT/'bot/configurations').iterdir()):
        if (folder/'bot.json').is_file():
            rows.append((folder.name, json.loads((folder/'bot.json').read_text()),
                         json.loads((folder/'referenced-config.json').read_text()),
                         json.loads((folder/'trigger-connections.json').read_text())))
    out = ['<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->',
           '<!-- GENERATED FILE: python tools/workflows/bot_permissions.py -->',
           '# Bot users, permissions and required resources', '',
           'This guide covers all checked-in bot configurations. IDs and dependencies below come',
           'from their source JSON, not a running installation. It does not grant access or prove',
           'runtime readiness. Follow each bot README for its exact input and file commands.', '',
           '## Who needs which authority', '',
           '| Identity | Required access |', '|---|---|',
           '| Installation operator | A live Super Admin for bot staging/review/preparation/publication and installation custody; administer owned devices, packages, model endpoints and scoped grants independently |',
           '| Manual/API caller | Existing enabled OLO user; username/password login for the selected tenant; membership in an enabled Team with explicit HUMAN grants, directly or through a HUMAN runtime role. A BASIC portal role is sufficient for the external APIs |',
           '| Caller reading their run | `workflow.progress.read` and/or `workflow.result.read` on that workflow, with current required resource access. Ordinary private run reads are owner-scoped; current tenant Super Admins can inspect all tenant runs |',
           '| Caller registering live events/callbacks | `workflow.subscribe` plus the selected progress/result read permissions, current at registration and each delivery |',
           '| Trigger-status observer | `trigger.read` on `trigger-assignment:<actual-assignment-UID>`, scoped to its target Device Groups; add `trigger.subscribe` for status events/streams/callbacks. No workflow execution is granted by observation |',
           '| Runtime agents | Enabled agent/group membership, ACTOR_SERVICE roles and current SERVICE/CAPABILITY/SECRET grants, workload credentials and execution bindings. Human login tokens never replace these |', '',
           'Control derives tenant-wide human runtime authority from live Super Admin membership.',
           'Other users require explicit runtime grants; device approval never supplies them. Imports preserve human',
           'accounts and do not automatically put a person into a runtime role. Choose the actual',
           'OLO users who should run each bot; the source `admin` identity reference is not a',
           'requirement to share an administrator account.', '',
           '## Human grant recipe for every bot', '',
           'Use enabled HUMAN grants sourced from the user\'s enabled Team or its HUMAN role.',
           'For the exact IDs in each section, grant these CUSTOM/EXACT resource/action pairs:', '',
           '| Action | Resource locator |', '|---|---|',
           '| `workflow.execute` | `workflow:<workflow ID>` |',
           '| `agent.execute` | `agent:<agent ID>` for every bound agent, including later handoff agents |',
           '| `model.infer` | `model-group:<model group ID>` for the workflow ceiling and every bound agent model group |',
           '| `workflow.progress.read`, `workflow.result.read`, `workflow.subscribe` | `workflow:<workflow ID>` as needed for observing that caller\'s runs |', '',
           'Also cover the complete SERVICE/CAPABILITY scopes of all bound agents: tool groups,',
           'device groups, actions and resource match rules. This includes `workflow-group:...`',
           'and model resources present in their service grant. Cover any agent SECRET scopes',
           'with corresponding human SECRET grants. The grant IDs listed below identify those',
           'source scopes; they are not credentials or permissions to attach an ACTOR_SERVICE',
           'role to a human Team.', '',
           'For these file examples the tool action is **`invoke`**, scoped to the listed',
           '`tool://...` resources and Tool/Device Groups. Do not substitute invented `read` or',
           '`write` action strings. V1 human admission scopes need conditions that can be checked',
           'at admission: no online/high-risk/network/posture/amount/quota evidence conditions.',
           'Keep resource scopes narrow; service grants, bindings, device checks and policies still',
           'enforce their own current execution conditions. Copying a service scope containing',
           '`requireOnline: true` unchanged into a human admission grant is not sufficient.', '',
           'Native/JSON graphs can select tools dynamically, so V1 checks the complete declared',
           'agent capability ceiling, including capabilities not exercised by a particular input.',
           'A single human scope must cover each required declared scope; do not assume separate',
           'partial grants are automatically combined into a larger cross-product permission.', '',
           '## All supplied bots', '',
           '| Configuration | Profile / workflow ID | Entry agents | Device/tool work |', '|---|---|---|---|']
    for name, bot, refs, triggers in rows:
        for workflow in bot['workflows']['workflows']:
            entry = codes(a['agentId'] for a in workflow['agents'] if a['entryEligible'])
            out.append(f'| [{name}](configurations/{name}/README.md) | `{bot["profileId"]}` / `{workflow["id"]}` | {entry} | '+('File tools and watcher' if bot['packages'] else 'Text/models only')+' |')
    out += ['', '## Installation requirements shared by the examples', '',
            'Configure and approve the declared HTTPS model endpoints and installation-owned',
            'vault references. Model group permission does not disclose the provider secret.',
            'Local inference uses the model IDs listed in the source; standalone premium',
            'standby groups are not a required grant until explicitly assigned. Review actual',
            'graph/model routing in the bot README before downloading weights.', '',
            'File examples require exact verified `.olotool` package versions, an approved and',
            'enabled owned device in both the watcher-placement and tool-execution groups,',
            'ready executors/workload bindings, and the qualified device file boundary.',
            'Sources ship empty Device Groups; no physical device ID is implied. Paths below',
            'are relative to the configured device file root, normally `/var/lib/olo-toolgate/files`.',
            'Prepare broker-owned directories/files with the permissions in the device guide.', '',
            'These example extractors grant tool invocation, not per-bot directory isolation.',
            'A file tool grant covers its allowed arguments within the device\'s file boundary.',
            'Use separately scoped devices/roots when bots must not share file access.', '',
            '`hotswap-demo-a` and `hotswap-demo-b` intentionally share IDs and runtime groups.',
            'Publishing B changes new admissions; it does not move A\'s files or replace human',
            'permissions. Existing runs retain their pinned graph and admitted ceiling.', '']
    for name, bot, refs, triggers in rows:
        config = bot['workflows']; workflows = config['workflows']
        models = {w['modelGroupId'] for w in workflows}
        bound = {a['agentId'] for w in workflows for a in w['agents']}
        models.update(a['modelGroupId'] for a in config['agentDefinitions'] if a['agentId'] in bound)
        out += [f'## {name}', '', f'[Bot instructions](configurations/{name}/README.md) · [Bot definition](configurations/{name}/bot.json) · [Directory grants/resources](configurations/{name}/referenced-config.json)', '',
                '- Workflow execution/observation resource: '+codes('workflow:'+w['id'] for w in workflows)+'.',
                '- Workflow service resources: '+codes('workflow-group:'+w['workflowGroupId'] for w in workflows)+'.',
                '- Human agent execution resources: '+codes('agent:'+a['agentId'] for w in workflows for a in w['agents'])+'.',
                '- Bound Agent Groups: '+codes(a['agentGroupId'] for w in workflows for a in w['agents'])+'.',
                '- Model inference resources: '+codes('model-group:'+m for m in models)+'.',
                '- Service/capability source grants to cover: '+codes(g['id'] for g in refs['grants'] if g['purpose'] in ('SERVICE','CAPABILITY','SECRET'))+'.']
        deployed = {d for g in config['modelGroups'] if g['id'] in models for d in g['deploymentIds']}
        out.append('- Declared reachable model deployments: '+codes(d['id']+' → '+d['modelId'] for d in config['modelDeployments'] if d['id'] in deployed)+'.')
        out.append('- Installation model secret references: '+codes(d['secretName'] for d in config['modelDeployments'] if d['id'] in deployed)+'.')
        if not bot['packages']:
            out += ['- Tool packages, tool-execution devices and file paths: none in this configuration.', '- Trigger assignments: none.', '']
            continue
        out += ['- Packages: '+codes(p['packageId']+'@'+p['version'] for p in bot['packages'])+'.',
                '- Tool IDs: '+codes(t['id'] for t in refs['tools'])+'.']
        for binding in refs['bindings']:
            if binding['toolGroupId'] == 'default-tools':
                continue
            out.append(f'- Execution binding `{binding["id"]}`: Tool Group `{binding["toolGroupId"]}`, Device Group `{binding["deviceGroupId"]}`, action(s) '+codes(binding['actions'])+'.')
        for trigger in triggers:
            spec = trigger['assignmentSpec']
            out.append(f'- Watcher `{trigger["assignment"]}`: Device Group(s) '+codes(spec['target'].get('deviceGroups',[]))+'; configuration `'+json.dumps(spec['config'],separators=(',',':'))+'`.')
        paths = set()
        for workflow in workflows:
            for node in workflow['graph']['config'].get('nodes',[]):
                if node.get('kind') == 'tool':
                    for field in ('path','destination'):
                        value = node.get('input',{}).get(field)
                        if isinstance(value,str): paths.add(value)
                        elif value is not None: paths.add('input '+field+' resolved from '+json.dumps(value,separators=(',',':')))
        out += ['- Graph file paths: '+codes(paths)+'.', '']
    out += ['## Configure and verify', '',
            'Use [run/import guidance](../docs/help/run-bots.md), [model setup](../docs/help/models.md),',
            '[device/file requirements](../docs/help/device.md) and the',
            '[external API/Postman guide](../deploy/docker/quickstart/dev/externa-workflow-integration/postman/README.md).',
            'Obtain actual published profile digests/generations, trigger UIDs and device IDs',
            'from the destination. Review permissions again after changing a bot or role.', '',
            'Regenerate this matrix with `python tools/workflows/bot_permissions.py`;',
            'verify source agreement with `python tools/workflows/bot_permissions.py --check`.', '']
    return '\n'.join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    path = ROOT/'bot/PERMISSIONS.md'; content = render()
    if args.check:
        if not path.is_file() or path.read_text(encoding='utf-8') != content: raise SystemExit('Bot permission guide drift; regenerate tools/workflows/bot_permissions.py')
    else: path.write_text(content,encoding='utf-8',newline='\n')
    print('Bot permission/resource matrix verified' if args.check else 'Bot permission/resource matrix generated')


if __name__ == '__main__': main()
