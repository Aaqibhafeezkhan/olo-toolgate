# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Build deterministic acceptance fixtures separately from the real local-model bots."""
from copy import deepcopy
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


def configurations():
    for revision in ('a','b'):
        folder=ROOT/'bot/configurations'/('hotswap-demo-'+revision)
        profile=json.loads((folder/'bot.json').read_text(encoding='utf-8'))
        references=json.loads((folder/'referenced-config.json').read_text(encoding='utf-8'))
        connections=json.loads((folder/'trigger-connections.json').read_text(encoding='utf-8'))
        config=profile['workflows'];deployment=deepcopy(config['modelDeployments'][0])
        deployment.update(id='fixture',origin='https://demo-provider:9443',modelId='demo-'+revision,secretName='demo-provider-key')
        config['modelDeployments']=[deployment]
        config['modelGroups']=[dict(id=group['id'],deploymentIds=['fixture']) for group in config['modelGroups']]
        workflow=config['workflows'][0];graph=workflow['graph']['config']
        graph['nodes']=[node for node in graph['nodes'] if node['kind']!='model' and node['id']!='file-messages']
        for node in graph['nodes']:
            if node['id']=='summarize':node['input'].pop('fileAgentProposal',None)
        names=[node['id'] for node in graph['nodes']]
        graph['edges']=[{'from':[a],'to':b} for a,b in zip(['START']+names,names+['END'])]
        yield revision,profile,references,connections


if __name__=='__main__':
    import sys
    sys.path.insert(0,str(ROOT))
    from bot.build import build
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packages-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/hotswap-mock')
    args=parser.parse_args();cache={};output=args.output/'bin';output.mkdir(parents=True,exist_ok=True)
    import subprocess
    commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True,cwd=ROOT).strip()
    for revision,profile,references,connections in configurations():
        name='hotswap-demo-'+revision;folder=args.output/name;folder.mkdir(parents=True,exist_ok=True)
        recipe=json.loads((ROOT/'bot/configurations'/name/'package-build.json').read_text(encoding='utf-8'))
        for filename,value in [('bot.json',profile),('referenced-config.json',references),('trigger-connections.json',connections),('package-build.json',recipe)]:
            (folder/filename).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
        build(folder,output,'1.0.0-demo.'+revision,commit,'mock-acceptance',cache,args.packages_dir)
    print('Mock-only acceptance bundles: '+str(output))
