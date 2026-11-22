# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Portable profile build/embedded package/release integrity, without live providers."""
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from bot import bundle
from bot import build as builder
from tools.release.profiles import collect

ROOT=Path(__file__).resolve().parents[2]


class ProfileTests(unittest.TestCase):
    def test_docker_builder_does_not_share_gradle_state(self):
        with tempfile.TemporaryDirectory(prefix='.profile-build-test-',dir=ROOT) as output:
            with patch.object(builder.sys,'argv',['build.py','--configuration','text-summary','--output',output]), \
                    patch.object(builder.shutil,'which',side_effect=lambda name: '/docker' if name=='docker' else None), \
                    patch.dict(builder.os.environ,{'TOOLGATE_CLI':''}), \
                    patch.object(builder.subprocess,'check_output',side_effect=['a'*40,b'bot/build.py\0']), \
                    patch.object(builder.subprocess,'run') as run:
                builder.main()
            command=run.call_args.args[0]
            self.assertEqual(['docker','run','--rm'],command[:3])
            mounts=[command[i+1] for i,argument in enumerate(command) if argument=='-v']
            self.assertEqual(2,len(mounts))
            self.assertTrue(mounts[0].endswith(':/workspace'))
            self.assertTrue(mounts[1].endswith(':/output'))
            self.assertFalse(any('gradle' in mount for mount in mounts))

    def test_sdk_publication_is_root_qualified_and_isolated(self):
        with tempfile.TemporaryDirectory() as output:
            with patch.object(builder.sys,'argv',['build.py','--configuration','text-summary','--output',output,'--source-commit','a'*40]), \
                    patch.dict(builder.os.environ,{'TOOLGATE_CLI':'/toolgate'}), \
                    patch.object(builder.subprocess,'run') as run, \
                    patch.object(builder,'build',return_value=[]):
                builder.main()
            command=run.call_args.args[0]
            self.assertIn('--no-daemon',command)
            self.assertIn('--configure-on-demand',command)
            self.assertIn('tools/ci/sdk-isolation.init.gradle',command)
            self.assertEqual(':publishJavaSdkLocally',command[-1])

    def test_multiagent_incident_handoff_keeps_entry_and_delegation_eligibility(self):
        folder=ROOT/'bot/configurations/multiagent-incident'
        profile=json.loads((folder/'bot.json').read_text(encoding='utf-8'))
        workflow=profile['workflows']['workflows'][0]
        first,second=workflow['agents']
        self.assertTrue(first['entryEligible'])
        self.assertFalse(second['entryEligible'])
        self.assertTrue(second['delegationAllowed'])
        graph=workflow['graph']['config']
        self.assertEqual([first['agentId'],second['agentId']],[n['agentId'] for n in graph['nodes']])
        self.assertEqual(['agent','agent'],[n['kind'] for n in graph['nodes']])
        self.assertEqual({'$path':'/values/triage'},graph['nodes'][1]['input']['triage'])
        self.assertEqual([(['START'],'triage'),(['triage'],'handover'),(['handover'],'END')],
            [(e['from'],e['to']) for e in graph['edges']])
        scoped=builder.scope(profile,workflow,[])
        self.assertEqual({first['agentId'],second['agentId']},{a['agentId'] for a in scoped['workflows']['agentDefinitions']})
        self.assertEqual([],scoped['packages'])

    def test_same_release_different_configurations_cannot_alias_immutable_workflow_versions(self):
        for identity in ('hotswap-demo','a'*128):
            a=builder.workflow_version_id(identity,'hotswap-demo-a','1.0.0-demo.local')
            b=builder.workflow_version_id(identity,'hotswap-demo-b','1.0.0-demo.local')
            self.assertNotEqual(a,b)
            self.assertLessEqual(len(a),128)
            self.assertEqual(a,builder.workflow_version_id(identity,'hotswap-demo-a','1.0.0-demo.local'))

    def test_public_bots_keep_their_declared_local_or_three_tier_model_order(self):
        memberships={}
        three_tier={'tenant-incident-triage','device-file-processing','governed-file-processing',
                    'publication-switch-a','publication-switch-b','portable-summary'}
        for folder in (ROOT/'bot/configurations').iterdir():
            if not folder.is_dir():continue
            profile=json.loads((folder/'bot.json').read_text(encoding='utf-8'))
            references=json.loads((folder/'referenced-config.json').read_text(encoding='utf-8'))
            for group in references['toolGroups']:
                for tool in group['toolIds']:memberships.setdefault(tool,set()).add(group['id'])
            config=profile['workflows']
            def logical(value):return value if folder.name.startswith('hotswap-demo-') else value.removeprefix(folder.name+'-')
            groups={logical(g['id']):[logical(x) for x in g['deploymentIds']] for g in config['modelGroups']}
            deployments={logical(m['id']):m for m in config['modelDeployments']}
            tiered=folder.name in three_tier
            expected=['local-qlora','local-lightweight','premium-openrouter']
            self.assertEqual(expected if tiered else ['local-qlora','local-lightweight'],groups['hotfolder-models'])
            self.assertEqual(expected if tiered else ['local-lightweight'],groups['general-models'])
            for workflow in config['workflows']:
                self.assertNotIn('deployment',workflow)
                if tiered:self.assertEqual(expected,groups[logical(workflow['modelGroupId'])])
                else:self.assertNotIn('premium-openrouter',groups[logical(workflow['modelGroupId'])])
                tools={node['agentId'] for node in workflow['graph']['config']['nodes'] if node['kind']=='tool'}
                for agent in config['agentDefinitions']:
                    self.assertEqual('hotfolder-models' if agent['agentId'] in tools else 'general-models',logical(agent['modelGroupId']))
                    if tiered:self.assertEqual(expected,groups[logical(agent['modelGroupId'])])
                    else:self.assertNotIn('premium-openrouter',groups[logical(agent['modelGroupId'])])
                scoped=builder.scope(profile,workflow,json.loads((folder/'trigger-connections.json').read_text(encoding='utf-8')))
                self.assertIn('premium-openrouter',{logical(m['id']) for m in scoped['workflows']['modelDeployments']})
            self.assertEqual('olo-qlora-hotfolder:e376fb40',deployments['local-qlora']['modelId'])
            self.assertEqual('qwen3:0.6b',deployments['local-lightweight']['modelId'])
        self.assertTrue(all(len(groups)==1 for groups in memberships.values()),memberships)

    def test_trigger_connections_keep_their_packages_without_deployment_fields(self):
        from tools.workflows.demo_profiles import configurations
        _,profile,_,connections=next(configurations())
        scoped=builder.scope(profile,profile['workflows']['workflows'][0],connections)
        self.assertEqual({'examples.hotfolder','examples.hotfolder-watch'},{p['packageId'] for p in scoped['packages']})
        self.assertNotIn('deployment',scoped['workflows']['workflows'][0])

    def test_sdk_descriptors_become_control_definitions_not_raw_sdk_objects(self):
        descriptor=dict(toolId='files/read',description='Read',inputSchema={'type':'object'},outputSchema={'type':'object'},
                        effect=dict(safety='READ_ONLY'),kind='JAVA',descriptorGeneration=2)
        definition=builder.control_definition(descriptor)
        self.assertEqual({'id','name','description','actions','inputSchema','outputSchema'},set(definition))
        self.assertEqual([dict(name='invoke',resourceKinds=['CUSTOM'])],definition['actions'])
        descriptor.update(effect=dict(safety='NON_IDEMPOTENT'),reconciliation='MANUAL')
        self.assertEqual('MANUAL',builder.control_definition(descriptor)['reconciliation'])
    def test_graph_only_tool_packages_are_kept_in_workflow_scope(self):
        profile=json.loads((ROOT/'bot/configurations/text-summary/bot.json').read_text(encoding='utf-8'))
        workflow=profile['workflows']['workflows'][0]
        workflow['graph']['config']['nodes']=[dict(id='read',kind='tool',agentId=workflow['agents'][0]['agentId'],
            toolRef='graph.files/read',input={'toolRef':'unrelated/fake'})]
        profile['packages']=[dict(packageId=name) for name in ('graph.files','unrelated')]
        self.assertEqual(['graph.files'],[item['packageId'] for item in builder.scope(profile,workflow,[])['packages']])
        workflow['graph']['config']['nodes'][0]['toolRef']='invalid'
        with self.assertRaisesRegex(ValueError,'tool reference'):builder.scope(profile,workflow,[])

    def test_profile_artifact_names_are_platform_safe_and_do_not_alias_identifiers(self):
        self.assertNotEqual(builder.filename('workflow:one'),builder.filename('workflow-one'))
        self.assertNotIn(':',builder.filename('workflow:one'))
        self.assertLessEqual(len(builder.filename('workflow-'+'a'*128)),48)

    def test_file_recipe_builds_a_workflow_and_repins_its_descriptor_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'source';folder.mkdir();output=root/'bin';output.mkdir()
            raw=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olotool').read_bytes();metadata,manifest=bundle.package(raw)
            (folder/'tool.olotool').write_bytes(raw)
            config=deepcopy(json.loads((ROOT/'tests/fixtures/contracts/v2/valid.json').read_text())['WorkflowConfiguration'])
            config['dispatchBindings']=[]
            for agent in config['agentDefinitions']:agent['contextSources']=[]
            tool=manifest['tools'][0]
            config['agentDefinitions'][0]['contextSources']=[dict(toolId=tool['descriptor']['toolId'],arguments={})]
            profile=dict(profileVersion=2,profileId='file-source',packages=[],workflows=config)
            refs=dict(tools=[dict(id=tool['descriptor']['toolId'],packageDigest='a'*64)],bindings=[dict(allowedPackageDigests=['a'*64])])
            for name,data in [('bot.json',profile),('referenced-config.json',refs),('trigger-connections.json',[]),('package-build.json',dict(packages=[dict(packageId=metadata['packageId'],version=metadata['version'],file='tool.olotool')]))]:
                (folder/name).write_text(json.dumps(data))
            rows=builder.build(folder,output,'1.2.3','b'*40,'test',{},None)
            self.assertEqual(1,len(rows))
            with zipfile.ZipFile(output/rows[0]['filename']) as archive:
                actual=json.loads(archive.read('referenced-data.json'))
                self.assertEqual(metadata['version'],actual['tools'][0]['version'])
                self.assertEqual([tool['toolDescriptorDigest'].removeprefix('sha256:')],actual['bindings'][0]['allowedPackageDigests'])
            self.assertTrue(rows[0]['filename'].endswith('.olo'))
            for name in ('obsolete.tgpkg','workflow.olo'):
                with self.assertRaisesRegex(ValueError,'must use .olotool'):
                    builder.source_package(dict(packageId='x',version='1.0.0',file=name),folder,{},None)

    def test_embedded_package_bytes_round_trip_and_tampering_is_rejected(self):
        package=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olotool').read_bytes()
        item,manifest=bundle.package(package)
        profile=dict(profileVersion=2,profileId='test',packages=[],workflows={'workflows':[{'id':'workflow'}]})
        data,inventory=bundle.pack(profile,{},[],[package],dict(version='1.0.0',sourceCommit='a'*40,build='local'))
        self.assertEqual([item],inventory['packages'])
        self.assertTrue(item['path'].endswith('.olotool'))
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(package,archive.read(item['path']))
            self.assertFalse(any('readme' in name.lower() for name in archive.namelist()))
            entries={name:archive.read(name) for name in archive.namelist()}
        for extension in ('.olo','.tgpkg'):
            obsolete=item['path'].removesuffix('.olotool')+extension
            renamed=dict(entries);renamed[obsolete]=renamed.pop(item['path'])
            metadata=deepcopy(inventory);metadata['packages'][0]['path']=obsolete
            renamed['manifest.json']=json.dumps(metadata).encode()
            archive_bytes=io.BytesIO()
            with zipfile.ZipFile(archive_bytes,'w',zipfile.ZIP_DEFLATED) as archive:
                for name,value in renamed.items():archive.writestr(name,value)
            with self.assertRaisesRegex(ValueError,'package path'):bundle.verify(archive_bytes.getvalue())
        entries[item['path']]=b'tampered'
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
            for name,value in entries.items():archive.writestr(name,value)
        with self.assertRaises((ValueError,zipfile.BadZipFile)):bundle.verify(output.getvalue())

    @patch('tools.release.profiles.expected_profiles',return_value={('release','one')})
    def test_release_collection_checks_version_source_hashes_and_embedded_inventory(self,_expected):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'bin';source.mkdir();output=root/'assets'
            data,manifest=bundle.pack(dict(profileVersion=2,profileId='release',packages=[],workflows={'workflows':[{'id':'one'}]}),{},[],[],dict(version='1.2.3',sourceCommit='a'*40,build='1'))
            (source/'one-1.2.3.olo').write_bytes(data)
            inventory=dict(format='olo-profiles',version='1.2.3',sourceCommit='a'*40,profiles=[dict(configuration='release',workflowId='one',filename='one-1.2.3.olo',sha256=bundle.digest(data),bytes=len(data),packages=[])])
            (source/'olo-profiles-1.2.3.json').write_text(json.dumps(inventory))
            self.assertEqual(2,len(collect(source,output,'1.2.3','a'*40)))
            self.assertEqual(data,(output/'one-1.2.3.olo').read_bytes())
            with self.assertRaisesRegex(ValueError,'source/version'):collect(source,output,'2.0.0')
            with self.assertRaisesRegex(ValueError,'source/version'):collect(source,output,commit='b'*40)
            (source/'one-1.2.3.olo').write_bytes(data+b'changed')
            with self.assertRaises(ValueError):collect(source,output)

    def test_release_requires_all_configurations_even_when_workflow_ids_are_shared(self):
        from tools.release.profiles import expected_profiles
        expected=expected_profiles()
        self.assertIn(('hotswap-demo-a','hotswap-demo'),expected)
        self.assertIn(('hotswap-demo-b','hotswap-demo'),expected)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'bin';source.mkdir();output=root/'assets'
            rows=[]
            for configuration,workflow in sorted(expected):
                data,_=bundle.pack(dict(profileVersion=2,profileId=configuration,packages=[],workflows={'workflows':[{'id':workflow}]}),{},[],[],dict(version='1.2.3',sourceCommit='a'*40,build='test'))
                name=configuration+'-1.2.3.olo';(source/name).write_bytes(data)
                rows.append(dict(configuration=configuration,workflowId=workflow,filename=name,sha256=bundle.digest(data),bytes=len(data),packages=[]))
            index=source/'olo-profiles-1.2.3.json'
            def write(items):index.write_text(json.dumps(dict(format='olo-profiles',version='1.2.3',sourceCommit='a'*40,profiles=items)),encoding='utf-8')
            write(rows)
            self.assertEqual(len(expected)+1,len(collect(source,output)))
            for incomplete in (rows[:-1],rows+[rows[0]],rows[:-1]+[dict(rows[-1],configuration='unknown')]):
                write(incomplete)
                with self.assertRaisesRegex(ValueError,'every bot'):collect(source,root/'refused')
                self.assertFalse((root/'refused').exists())
            write(rows)
            (source/rows[0]['filename']).unlink()
            with self.assertRaises(FileNotFoundError):collect(source,root/'missing')
            self.assertFalse((root/'missing').exists())

    @patch('tools.release.profiles.expected_profiles',return_value={('release','expected')})
    def test_release_inventory_cannot_relabel_another_workflow(self,_expected):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            data,_=bundle.pack(dict(profileVersion=2,profileId='release',packages=[],workflows={'workflows':[{'id':'other'}]}),{},[],[],dict(version='1.2.3',sourceCommit='a'*40,build='test'))
            (root/'bot.olo').write_bytes(data)
            (root/'olo-profiles-1.2.3.json').write_text(json.dumps(dict(format='olo-profiles',version='1.2.3',sourceCommit='a'*40,profiles=[dict(configuration='release',workflowId='expected',filename='bot.olo',sha256=bundle.digest(data),bytes=len(data),packages=[])])),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'evidence mismatch'):collect(root,root/'refused')

    def test_source_configurations_are_complete_and_only_new_archive_extension_is_used(self):
        for folder in (ROOT/'bot/configurations').iterdir():
            if not folder.is_dir():continue
            for name in ('bot.json','referenced-config.json','trigger-connections.json','package-build.json','README.md'):self.assertTrue((folder/name).is_file(),str(folder/name))
            refs=json.loads((folder/'referenced-config.json').read_text(encoding='utf-8'))
            self.assertNotIn('users',refs);self.assertNotIn('identityBindings',refs)
            recipes=json.loads((folder/'package-build.json').read_text(encoding='utf-8'))['packages']
            pins=json.loads((folder/'bot.json').read_text(encoding='utf-8'))['packages']
            self.assertIsInstance(recipes,list)
            self.assertTrue({item['packageId'] for item in pins}<={item['packageId'] for item in recipes})
        workflow=(ROOT/'.github/workflows/foundation.yml').read_text()
        self.assertIn('python bot/build.py',workflow)
        self.assertIn('foundation-stage-profiles',workflow)
        self.assertIn('tools/release/profiles.py',workflow)

    def test_profile_pins_use_exact_embedded_content_without_an_image_lock(self):
        raw=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olotool').read_bytes()
        item,_=bundle.package(raw)
        pin=dict(packageId=item['packageId'],version=item['version'],source='PREBUILT_CORE',required=True)
        profile=dict(profileVersion=2,profileId='portable',packages=[pin],workflows={'workflows':[{'id':'one'}]})
        data,_=bundle.pack(profile,{},[],[raw],dict(version='1.0.0',sourceCommit='a'*40,build='test'))
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            exported=json.loads(archive.read('workflow.json'))
        self.assertEqual('BUNDLE',exported['packages'][0]['source'])
        self.assertEqual(item['packageDigest'],exported['packages'][0]['packageDigest'])
        self.assertEqual('PREBUILT_CORE',profile['packages'][0]['source'],'export does not mutate build configuration')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:files={name:archive.read(name) for name in archive.namelist()}
        exported['packages'][0]['source']='PREBUILT_CORE';files['workflow.json']=bundle.encoded(exported)
        metadata=json.loads(files['manifest.json']);metadata['sha256']=bundle.digest(files['workflow.json']);files['manifest.json']=bundle.encoded(metadata)
        old=io.BytesIO()
        with zipfile.ZipFile(old,'w',zipfile.ZIP_DEFLATED) as archive:
            for name,value in files.items():archive.writestr(name,value)
        with self.assertRaisesRegex(ValueError,'exact embedded archive'):bundle.verify(old.getvalue())
        with self.assertRaisesRegex(ValueError,'exact embedded archive'):bundle.pack(profile,{},[],[],{})
        profile['packages'][0]['packageDigest']='sha256:'+'f'*64
        with self.assertRaisesRegex(ValueError,'exact embedded archive'):bundle.pack(profile,{},[],[raw],{})


if __name__=='__main__':unittest.main()
