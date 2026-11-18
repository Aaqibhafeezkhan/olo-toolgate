# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Installation-only cloud trace fan-out; secrets never enter bots or runtime containers."""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse

HERE=Path(__file__).resolve().parent


def endpoint(value,suffix):
    parsed=urllib.parse.urlsplit(value)
    if (parsed.scheme!='https' or not parsed.hostname or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or any(c.isspace() for c in value)):
        raise ValueError('Use an HTTPS cloud base URL without credentials/query/fragment')
    return value.rstrip('/')+suffix


def configuration(settings,*,allow_local=False):
    exporters={};environment={};enabled=[]
    def credential(name,value):
        if not isinstance(value,str) or not value or len(value)>8192 or any(c in value for c in '\r\n\0'):
            raise ValueError('Invalid private monitoring credential')
        environment[name]=value
    lf=settings.get('langfuse',{})
    if lf.get('publicKey') and lf.get('secretKey'):
        credential('LANGFUSE_BASIC_AUTH',base64.b64encode((lf['publicKey']+':'+lf['secretKey']).encode()).decode())
        exporters['otlp_http/langfuse']={'endpoint':endpoint(lf.get('baseUrl','https://cloud.langfuse.com'),'/api/public/otel'),
            'headers':{'Authorization':'Basic ${env:LANGFUSE_BASIC_AUTH}','x-langfuse-ingestion-version':'4'}};enabled.append('langfuse')
    ls=settings.get('langsmith',{})
    if ls.get('apiKey'):
        credential('LANGSMITH_API_KEY',ls['apiKey'])
        headers={'x-api-key':'${env:LANGSMITH_API_KEY}'}
        if ls.get('agentId'):
            headers.update({'Langsmith-Agent-Id':ls['agentId'],'Langsmith-Agent-Environment':ls.get('agentEnvironment','local')})
        else:headers['Langsmith-Project']=ls.get('project','toolgate-community-debug')
        if ls.get('workspaceId'):headers['X-Tenant-Id']=ls['workspaceId']
        exporters['otlp_http/langsmith']={'endpoint':endpoint(ls.get('baseUrl','https://api.smith.langchain.com'),'/otel'),'headers':headers};enabled.append('langsmith')
    phoenix=settings.get('phoenix',{})
    if phoenix.get('apiKey') and phoenix.get('baseUrl'):
        credential('PHOENIX_API_KEY',phoenix['apiKey'])
        exporters['otlp_http/phoenix']={'endpoint':endpoint(phoenix['baseUrl'],''),'headers':{'Authorization':'Bearer ${env:PHOENIX_API_KEY}'}};enabled.append('phoenix')
    if not enabled:
        if not allow_local:raise ValueError('Configure at least one cloud destination in credentials/observability.json')
        exporters['debug/local']={'verbosity':'basic'};enabled.append('local (no cloud keys configured)')
    for name,value in exporters.items():
        if name.startswith('otlp_http/'):
            value.update({'timeout':'10s','retry_on_failure':{'enabled':True,'max_elapsed_time':'30s'},'sending_queue':{'enabled':True,'queue_size':64}})
    document={'receivers':{'otlp':{'protocols':{'http':{'endpoint':'0.0.0.0:4318'}}}},
        'processors':{'memory_limiter':{'check_interval':'1s','limit_mib':128},'batch':{'timeout':'1s','send_batch_size':128},
            'resource/project':{'attributes':[{'key':'openinference.project.name','value':phoenix.get('project','toolgate-community-debug'),'action':'upsert'}]}},
        'exporters':exporters,'service':{'pipelines':{'traces':{'receivers':['otlp'],'processors':['memory_limiter','resource/project','batch'],'exporters':list(exporters)}}}}
    return document,environment,enabled


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('operation',choices=['configure','start','stop']);args=parser.parse_args()
    profiled=HERE.parent;root=profiled.parents[1];private=profiled/'credentials';sys.path.insert(0,str(root))
    compose=['docker','compose','-p','toolgate-profiled-observability','-f',str(HERE/'compose.yaml')]
    def run(command,**options):return subprocess.run(command,cwd=root,check=True,**options)
    if args.operation=='stop':run(compose+['stop']);return
    private.mkdir(mode=0o700,exist_ok=True)
    def write(name,raw):
        with open(private/name,'w',encoding='utf-8',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(raw)
    settings_path=private/'observability.json'
    if not settings_path.exists():
        write('observability.json',(HERE/'credentials.example.json').read_text(encoding='utf-8'))
        print('Created private credentials template; edit it to enable cloud destinations. Existing files are never replaced.',flush=True)
    settings=json.loads(settings_path.read_text(encoding='utf-8-sig'))
    document,environment,enabled=configuration(settings,allow_local=True)
    write('collector.json',json.dumps(document,indent=2)+'\n')
    write('monitoring.env',''.join(key+'='+value+'\n' for key,value in environment.items()))
    write('runtime-observability.env','TOOLGATE_WORKFLOW_OTLP_TRACES_ENDPOINT=http://insights-collector:4318/v1/traces\nTOOLGATE_WORKFLOW_OBSERVABILITY_ENVIRONMENT=community-debug\n')
    print('Configured trace destinations: '+', '.join(enabled)+'; secrets retained only in private collector files.',flush=True)
    if args.operation=='configure':return
    run(compose+['run','--rm','--no-deps','collector','validate','--config=/etc/otelcol/config.json'])
    run(compose+['up','-d'])
    spec=importlib.util.spec_from_file_location('profiled_stack',profiled/'stack.py');stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack)
    stack.check_owned();current=json.loads((profiled/'state/compose.json').read_text(encoding='utf-8'))
    selected=stack.images();base=current['services']['quickstart']['environment']['TOOLGATE_CONTROL_CLIENT_IMAGES_BASE']
    command=stack.compose(stack.definition(selected,base))
    stack.provision_runtime(command)
    # Explicit installation instrumentation update, never called by bot import/switch.
    run(command+['up','-d','--no-deps','langgraph'])
    print('Runtime telemetry enabled. Run a bot and locate toolgate.run.id in the configured vendor projects.',flush=True)


if __name__=='__main__':main()
