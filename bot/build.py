# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Build versioned profiles with their exact required tool/trigger packages."""
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent
sys.path.insert(0,str(ROOT))
from bot.bundle import digest,package,pack,verify


def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))


def control_definition(descriptor):
    result=dict(id=descriptor['toolId'],name=descriptor['toolId'],description=descriptor['description'],
        actions=[dict(name=descriptor.get('authorization',{}).get('action','invoke'),resourceKinds=['CUSTOM'])],
        inputSchema=descriptor['inputSchema'],outputSchema=descriptor['outputSchema'])
    if descriptor.get('effect',{}).get('safety')!='READ_ONLY':
        result['reconciliation']=descriptor.get('reconciliation','MANUAL')
    return result


def filename(value,limit=48):
    safe=re.sub(r'[^A-Za-z0-9_.-]','-',value)
    if safe!=value or len(safe)>limit:safe=safe[:limit-9]+'-'+digest(value.encode())[7:15]
    return safe


def workflow_version_id(workflow_id,configuration,version):
    identity=workflow_id+'-'+filename(configuration)+'-'+version.replace('+','-')
    if len(identity)>128:
        identity=workflow_id[:80]+'-'+digest((workflow_id+'\0'+configuration+'\0'+version).encode())[7:47]
    return identity


def inside(root,name):
    path=(root/name).resolve()
    if not path.is_relative_to(root.resolve()) or path.is_symlink():raise ValueError('Build input leaves its source directory')
    return path


def source_package(recipe,folder,cache,prebuilt):
    coordinate=(recipe['packageId'],recipe['version'])
    key=(coordinate,json.dumps(recipe,sort_keys=True),str(folder.resolve()) if 'file' in recipe else '')
    if key in cache:return cache[key]
    candidates=[]
    if 'file' in recipe:
        if not recipe['file'].endswith('.olotool'):raise ValueError('Tool package inputs must use .olotool')
        candidates=[inside(folder,recipe['file'])]
    elif prebuilt:candidates=sorted(prebuilt.rglob('*.olotool'))
    else:
        project=inside(ROOT,recipe['project']);task=recipe['task']
        if not re.fullmatch(r':[A-Za-z0-9-]+:toolgatePackage',task):raise ValueError('Unsupported Gradle package task')
        wrapper=ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')
        subprocess.run([str(wrapper),'-p',str(project),'-PtoolgateSdkRepo='+str(ROOT/'build/sdk-repo'),task],cwd=ROOT,check=True)
        candidates=sorted((project/task.split(':')[1]/'build/toolgate').glob('*.olotool'))
    matches=[]
    for path in candidates:
        try:
            data=path.read_bytes();item,manifest=package(data)
        except (ValueError,KeyError):
            if 'file' in recipe:raise
            continue
        if (item['packageId'],item['version'])==coordinate:matches.append((item,data,manifest))
    unique={item[0]['packageDigest']:item for item in matches}
    if len(unique)!=1:raise ValueError('Exact tool package missing or ambiguous: '+str(coordinate))
    cache[key]=next(iter(unique.values()));return cache[key]


def scope(profile,workflow,connections):
    result=deepcopy(profile);config=result['workflows'];id=workflow['id']
    config['workflows']=[deepcopy(workflow)]
    agents={item['agentId'] for item in workflow['agents']}
    config['agentDefinitions']=[item for item in config['agentDefinitions'] if item['agentId'] in agents]
    groups={workflow['modelGroupId']}|{item['modelGroupId'] for item in config['agentDefinitions']}
    config['modelGroups']=[item for item in config['modelGroups'] if item['id'] in groups or item.get('standby') is True]
    models={model for item in config['modelGroups'] for model in item['deploymentIds']}
    config['modelDeployments']=[item for item in config['modelDeployments'] if item['id'] in models]
    config['workflowGroups']=[item for item in config['workflowGroups'] if item['id']==workflow['workflowGroupId']]
    config['dispatchBindings']=[item for item in config['dispatchBindings'] if item['workflowId']==id]
    bindings={item['id'] for item in config['dispatchBindings']}
    assignments={item['assignment'] for item in connections if item['id'] in bindings}
    packages={item['toolId'].split('/')[0] for agent in config['agentDefinitions'] for item in agent.get('contextSources',[])}|{item['toolId'].split('/')[0] for item in workflow.get('contextSources',[])}
    if workflow['graph']['packageId']=='olo.json-graph':
        for node in workflow['graph']['config']['nodes']:
            if node['kind']=='tool':
                reference=node['toolRef']
                if not isinstance(reference,str) or len(reference.split('/'))!=2 or not all(reference.split('/')):
                    raise ValueError('JSON graph tool reference is invalid')
                packages.add(reference.split('/')[0])
    if workflow.get('deployment'):packages.add(workflow['deployment']['source']['packageId'])
    packages.update(item['packageId'] for item in profile['packages']
                    if any(trigger['assignment'] in assignments for trigger in item.get('triggers',[])))
    result['packages']=[item for item in result['packages'] if item['packageId'] in packages]
    for item in result['packages']:
        if 'triggers' in item:item['triggers']=[trigger for trigger in item['triggers'] if trigger['assignment'] in assignments]
    return result


def build(folder,output,version,commit,build_id,cache,prebuilt):
    profile=read(folder/'bot.json');references=read(folder/'referenced-config.json');connections=read(folder/'trigger-connections.json')
    artifacts=[source_package(recipe,folder,cache,prebuilt) for recipe in read(folder/'package-build.json')['packages']]
    rows=[]
    for workflow in profile['workflows']['workflows']:
        selected=scope(profile,workflow,connections);config=selected['workflows'];definition=config['workflows'][0]
        definition['versionId']=workflow_version_id(definition['id'],folder.name,version)
        mapped=[]
        for binding in config['dispatchBindings']:
            connection=next(item for item in connections if item['id']==binding['id'])
            binding.update(workflowVersionId=definition['versionId'],subscriptionUid='ts-'+digest(connection['subscription'].encode())[7:39],sourceAssignmentUid='ta-'+digest(connection['assignment'].encode())[7:39])
            mapped.append(connection)
        by_id={item[0]['packageId']:item for item in artifacts}
        source=definition.get('deployment',{}).get('source')
        if source:source['packageDigest']=by_id[source['packageId']][0]['packageDigest']
        for pin in selected['packages']:pin['packageDigest']=by_id[pin['packageId']][0]['packageDigest']
        # Descriptor identity comes from the package built by this invocation.
        refs=deepcopy(references)
        descriptor_pins={}
        for tool in refs['tools']:
            entry,tool_manifest=next((entry,manifest) for _,_,manifest in artifacts for entry in manifest['tools'] if entry['descriptor']['toolId']==tool['id'])
            if tool.get('packageDigest'):descriptor_pins[tool['packageDigest'].removeprefix('sha256:')]=entry['toolDescriptorDigest'].removeprefix('sha256:')
            tool.update(packageDigest=entry['toolDescriptorDigest'].removeprefix('sha256:'),definition=control_definition(entry['descriptor']),version=tool_manifest['version'])
        for binding in refs['bindings']:
            binding['allowedPackageDigests']=[descriptor_pins.get(pin.removeprefix('sha256:'),pin.removeprefix('sha256:')) for pin in binding.get('allowedPackageDigests',[])]
        data,manifest=pack(selected,refs,mapped,[item[1] for item in artifacts],dict(version=version,sourceCommit=commit,build=build_id))
        name=filename(folder.name)+'-'+filename(definition['id'])+'-'+filename(version)+'.olo'
        (output/name).write_bytes(data)
        rows.append(dict(configuration=folder.name,workflowId=definition['id'],version=version,filename=name,sha256=digest(data),bytes=len(data),packages=manifest['packages']))
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',action='append');parser.add_argument('--output',type=Path,default=HERE/'bin')
    parser.add_argument('--packages-dir',type=Path);parser.add_argument('--version',default=(ROOT/'VERSION').read_text().strip())
    parser.add_argument('--source-commit');parser.add_argument('--no-sdk-build',action='store_true')
    args=parser.parse_args()
    if len(args.version)>128 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?',args.version):parser.error('Use the exact release SemVer')
    commit=args.source_commit or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    if not re.fullmatch(r'[a-f0-9]{40}',commit):parser.error('Use an exact source commit')
    folders=sorted(path for path in (HERE/'configurations').iterdir() if path.is_dir() and (not args.configuration or path.name in args.configuration))
    if not folders or args.configuration and set(args.configuration)-{path.name for path in folders}:parser.error('Unknown configuration')
    compile_required=not args.packages_dir and any('file' not in recipe for folder in folders for recipe in read(folder/'package-build.json')['packages'])
    if compile_required and not args.no_sdk_build and not os.environ.get('TOOLGATE_CLI') and not shutil.which('cargo'):
        if not shutil.which('docker'):parser.error('Install Rust/JDK 21, or Docker Desktop for the containerized builder')
        subprocess.run(['docker','build','-f','bot/Dockerfile','-t','olo-profile-builder:local','.'],cwd=ROOT,check=True)
        # Map only workspace outputs; no host credentials or Docker socket enter the builder.
        output=args.output.resolve()
        if not output.is_relative_to(ROOT):parser.error('Docker build output must be within this workspace')
        output.mkdir(parents=True,exist_ok=True)
        # Export current source inputs only. Ignored stack state/secrets never enter the builder.
        names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
        with tempfile.TemporaryDirectory(prefix='olo-profile-source-') as temporary:
            source=Path(temporary).resolve()
            if not source.is_relative_to(Path(tempfile.gettempdir()).resolve()):raise ValueError('Unexpected build staging directory')
            for name in sorted(set(names)-{''}):
                path=ROOT/name
                if not path.is_file():continue
                if path.is_symlink() or not path.resolve().is_relative_to(ROOT):raise ValueError('Linked build source')
                target=source/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
            command=['docker','run','--rm','-v',source.as_posix()+':/workspace','-v',output.as_posix()+':/output','-v','olo-profile-gradle:/root/.gradle',
                     '-e','TOOLGATE_BUILD='+os.environ.get('TOOLGATE_BUILD','local'),'olo-profile-builder:local',
                     '--output','/output','--version',args.version,'--source-commit',commit]
            for name in args.configuration or []:command+=['--configuration',name]
            subprocess.run(command,cwd=ROOT,check=True)
        return
    if compile_required and not args.no_sdk_build:
        if not os.environ.get('TOOLGATE_CLI'):
            subprocess.run(['cargo','build','--locked','-p','toolgate-cli'],cwd=ROOT,check=True)
            os.environ['TOOLGATE_CLI']=str(ROOT/'target/debug'/('toolgate.exe' if os.name=='nt' else 'toolgate'))
        subprocess.run([str(ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')),'publishJavaSdkLocally'],cwd=ROOT,check=True)
    args.output.mkdir(parents=True,exist_ok=True);rows=[];cache={}
    for folder in folders:rows+=build(folder,args.output,args.version,commit,os.environ.get('TOOLGATE_BUILD','local'),cache,args.packages_dir)
    inventory=dict(format='olo-profiles',version=args.version,sourceCommit=commit,profiles=rows)
    name='olo-profiles-'+filename(args.version)+'.json';(args.output/name).write_text(json.dumps(inventory,indent=2)+'\n',encoding='utf-8',newline='\n')
    files=[name]+[row['filename'] for row in rows]
    (args.output/'SHA256SUMS').write_text(''.join(digest((args.output/file).read_bytes())[7:]+'  '+file+'\n' for file in sorted(files)),encoding='utf-8',newline='\n')
    print('Built '+str(len(rows))+' profiles in '+str(args.output))


if __name__=='__main__':main()
