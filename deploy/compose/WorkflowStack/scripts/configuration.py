# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Strict operator configuration and inert identifier substitution for mounted profiles."""
import json
from copy import deepcopy
import math
from pathlib import Path
import re
from urllib.parse import urlsplit

NAME = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}')


def fields(value, names):
    if not isinstance(value,dict) or set(value)!=set(names.split()):
        raise ValueError('Unsupported workflow deployment configuration')
    return value


def image(value, pinned=False):
    if (not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:@_-]{0,511}',value)
            or pinned and not re.search(r'@sha256:[a-f0-9]{64}$',value)):
        raise ValueError('Use a valid image reference; model engines require a digest pin')


def port(value):
    if type(value) is not int or not 1024<=value<=65535:
        raise ValueError('Use unprivileged TCP ports in the workflow profile')


def effective(document, workflow_id):
    """Project the active workflow's declared dependency closure; retain the source profile unchanged."""
    document=deepcopy(document)
    catalog=document['workflows']
    workflow=next(item for item in catalog['workflows'] if item['id']==workflow_id)
    agents={item['agentId'] for item in workflow['agents']}
    definitions=[item for item in catalog['agentDefinitions'] if item['agentId'] in agents]
    if len(definitions)!=len(agents):
        raise ValueError('Active workflow agent references are not closed')
    groups={workflow['modelGroupId']}|{item['modelGroupId'] for item in definitions}
    model_groups=[item for item in catalog['modelGroups'] if item['id'] in groups]
    deployments={identity for item in model_groups for identity in item['deploymentIds']}
    catalog.update(workflows=[workflow],agentDefinitions=definitions,modelGroups=model_groups,
        modelDeployments=[item for item in catalog['modelDeployments'] if item['id'] in deployments],
        workflowGroups=[item for item in catalog['workflowGroups'] if item['id']==workflow['workflowGroupId']],
        dispatchBindings=[item for item in catalog['dispatchBindings'] if item['workflowId']==workflow_id])
    return document


def load(directory):
    directory=Path(directory)
    profile=directory/'workflow-profile.json'
    if profile.is_symlink():
        raise ValueError('Profile links are unsupported')
    document=json.loads(profile.read_text(encoding='utf-8'))
    if document.get('profileVersion')!=2:
        raise ValueError('Use the current workflow profile format')
    active=[item for item in document['workflows']['workflows'] if item.get('deployment',{}).get('enabled') is True]
    if len(active)!=1:
        raise ValueError('A local stack profile must select exactly one enabled workflow deployment')
    workflow=active[0]
    deployment=fields(workflow['deployment'],'enabled runtime platform model source fileAgent monitoring')
    runtime=fields(deployment['runtime'],'engine image')
    if runtime['engine']!='LANGGRAPH':
        raise ValueError('Unsupported workflow runtime engine')
    image(runtime['image'])
    platform=fields(deployment['platform'],'quickstartImage deviceImage httpPort tlsPort registryPort')
    image(platform['quickstartImage']);image(platform['deviceImage'])
    for name in ('httpPort','tlsPort','registryPort'):port(platform[name])
    if len({platform[name] for name in ('httpPort','tlsPort','registryPort')})!=3:
        raise ValueError('Workflow service ports must be distinct')
    engine=fields(deployment['model'],'deploymentId engine image modelFile contextSize threads')
    if (engine['engine']!='LLAMA_CPP' or not isinstance(engine['modelFile'],str)
            or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.gguf',engine['modelFile'])
            or type(engine['contextSize']) is not int or not 1024<=engine['contextSize']<=131072
            or type(engine['threads']) is not int or not 1<=engine['threads']<=32):
        raise ValueError('Unsupported or unbounded workflow model engine')
    image(engine['image'],pinned=True)
    scoped=effective(document,workflow['id'])
    models=scoped['workflows']['modelDeployments']
    if len(models)!=1 or models[0]['id']!=engine['deploymentId']:
        raise ValueError('The active local workflow must resolve its declared model deployment')
    model=models[0]
    if not isinstance(model['modelId'],str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,255}',model['modelId']):
        raise ValueError('Use a bounded model alias in the local profile')
    origin=urlsplit(model['origin'])
    if (origin.scheme!='https' or origin.hostname!='workflow-model' or origin.username or origin.password
            or origin.path not in ('','/') or origin.query or origin.fragment or origin.port is None):
        raise ValueError('The local model origin must name its workflow-model service and TLS port')
    port(origin.port)
    generation=fields(model.get('generation'),'maxOutputTokens temperature')
    if (type(generation['maxOutputTokens']) is not int or not 1<=generation['maxOutputTokens']<=4096
            or type(generation['temperature']) not in (int,float) or not math.isfinite(generation['temperature'])
            or not 0<=generation['temperature']<=2):
        raise ValueError('Use bounded model generation parameters in the profile')
    source=fields(deployment['source'],'subscription assignment packageId version triggerId deviceGroupId config')
    if (any(not isinstance(source[name],str) or not NAME.fullmatch(source[name]) for name in ('subscription','assignment','deviceGroupId'))
            or source['packageId']!='examples.inbox-watch' or source['version']!='0.1.0'
            or source['triggerId']!='examples.inbox-watch/new-file' or source['config']!={'folder':'inbox','extension':'.txt'}):
        raise ValueError('Unsupported signed hot-folder trigger source')
    agent=deployment['fileAgent']
    if (not isinstance(agent,dict) or set(agent) != {'agentId','inbox','processed','output','archive','projection'}
            or not isinstance(agent['agentId'],str) or not NAME.fullmatch(agent['agentId'])
            or type(agent['archive']) is not bool or agent['projection'] not in ('json','summary')
            or any(not isinstance(agent[k],str) or not NAME.fullmatch(agent[k]) for k in ('inbox','processed','output'))
            or len({agent[k] for k in ('inbox','processed','output')}) != 3):
        raise ValueError('Folder agent needs distinct confined input, processed and output folders')
    if agent['agentId'] not in {item['agentId'] for item in workflow['agents']}:
        raise ValueError('Folder agent must belong to the selected workflow')
    monitoring=fields(deployment['monitoring'],'enabled mode collectorConfig spanSampleRatio grafanaUser grafanaSecret grafanaPort prometheusPort images')
    if (type(monitoring['enabled']) is not bool or monitoring['mode'] not in ('NONE','BASIC','DETAILED')
            or not isinstance(monitoring['collectorConfig'],dict) or len(monitoring['collectorConfig'])>16
            or type(monitoring['spanSampleRatio']) not in (int,float) or not 0<=monitoring['spanSampleRatio']<=1
            or any(not isinstance(monitoring[name],str) or not NAME.fullmatch(monitoring[name]) for name in ('grafanaUser','grafanaSecret'))):
        raise ValueError('Unsupported workflow monitoring configuration')
    port(monitoring['grafanaPort']);port(monitoring['prometheusPort'])
    for reference in fields(monitoring['images'],'collector prometheus grafana tempo').values():image(reference)
    return dict(id=directory.name,workflowProfile=profile.name,workflowId=workflow['id'],document=scoped,
        subscription=source['subscription'],assignment=source['assignment'],fileAgent=agent,
        modelAlias=model['modelId'],modelDeployment=model,deployment=deployment,modelPort=origin.port)


def resolve(value, identities):
    if isinstance(value,dict):
        return {key:resolve(item,identities) for key,item in value.items()}
    if isinstance(value,list):
        return [resolve(item,identities) for item in value]
    if isinstance(value,str) and '${' in value:
        if value not in ('${SUBSCRIPTION_UID}','${ASSIGNMENT_UID}') or value not in identities:
            raise ValueError('Unknown profile identity placeholder')
        return identities[value]
    return value
