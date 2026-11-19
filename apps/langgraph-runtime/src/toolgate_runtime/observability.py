# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Opt-in content-free OTLP/HTTP spans. Telemetry never changes execution authority."""
import hashlib
import json
import os
import queue
import secrets
import threading
import time
import urllib.parse
import urllib.request


def attributes(values):
    return [{'key':key,'value':{'stringValue':str(value)}} for key,value in values.items()]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


class Observability:
    def __init__(self,endpoint='',*,environment='local',send=None):
        self.endpoint=endpoint;self.environment=environment;self.pending=queue.Queue(maxsize=16)
        self.stopped=threading.Event();self.dropped=0;self.thread=None
        if endpoint:
            parsed=urllib.parse.urlsplit(endpoint)
            if (parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username is not None
                    or parsed.password is not None or parsed.query or parsed.fragment or not parsed.path.endswith('/v1/traces')):
                raise ValueError('Use an explicit OTLP/HTTP trace endpoint without credentials/query/fragment')
            self.send=send or self._send
            self.thread=threading.Thread(target=self._drain,daemon=True,name='workflow-observability');self.thread.start()

    @classmethod
    def from_environment(cls):
        return cls(os.environ.get('TOOLGATE_WORKFLOW_OTLP_TRACES_ENDPOINT',''),
            environment=os.environ.get('TOOLGATE_WORKFLOW_OBSERVABILITY_ENVIRONMENT','local')[:128])

    def run(self,lease):return Observation(self,lease)

    def export(self,spans):
        if not self.endpoint:return
        payload=json.dumps({'resourceSpans':[{'resource':{'attributes':attributes({'service.name':'olo-toolgate-langgraph',
            'deployment.environment.name':self.environment,'openinference.project.name':'toolgate-workflows'})},
            'scopeSpans':[{'scope':{'name':'olo-toolgate.workflow','version':'1'},'spans':spans}]}]},separators=(',',':')).encode()
        if len(payload)>1024*1024:self.dropped+=1;return
        try:self.pending.put_nowait(payload)
        except queue.Full:self.dropped+=1

    def _send(self,payload):
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        request=urllib.request.Request(self.endpoint,payload,{'Content-Type':'application/json'},method='POST')
        with opener.open(request,timeout=2) as response:
            raw=response.read(65537)
            if len(raw)>65536:raise ValueError('Oversized telemetry acknowledgement')
            value=json.loads(raw) if raw else {}
            if value.get('partialSuccess',{}).get('rejectedSpans',0) not in (0,'0'):raise ValueError('Rejected telemetry')

    def _drain(self):
        while not self.stopped.is_set() or not self.pending.empty():
            try:payload=self.pending.get(timeout=.1)
            except queue.Empty:continue
            try:self.send(payload)
            except Exception:
                self.dropped+=1
                print(json.dumps({'event':'workflow_observability_export','state':'DROPPED','droppedBatches':self.dropped}),flush=True)
            finally:self.pending.task_done()

    def close(self):
        self.stopped.set()
        if self.thread:self.thread.join(timeout=3)


class Observation:
    def __init__(self,exporter,lease):
        self.exporter=exporter;self.enabled=bool(exporter.endpoint);self.spans=[];self.active=[];self.state='UNCONFIRMED'
        self.trace=hashlib.sha256((lease.run_id+'\n'+lease.profile_digest).encode()).hexdigest()[:32]
        self.common={'toolgate.run.id':lease.run_id,'toolgate.workflow.id':lease.workflow_id,'toolgate.profile.digest':lease.profile_digest,
            'langfuse.trace.name':lease.workflow_id,'langfuse.session.id':lease.run_id,'langsmith.metadata.run_id':lease.run_id,
            'langfuse.observation.metadata.run_id':lease.run_id,'toolgate.content_export':'disabled'}
        self.groups={agent.agent_id:agent.model_group_id for agent in lease.agents}
        self.groups[lease.workflow_id]=lease.supervisor_model_group_id
        self.root=self._start('workflow '+lease.workflow_id,'CHAIN',None)

    def _start(self,name,kind,parent,extra=None):
        values={**self.common,'openinference.span.kind':kind,'langsmith.span.kind':{'LLM':'llm','TOOL':'tool'}.get(kind,'chain'),
            'langfuse.observation.type':{'LLM':'generation','AGENT':'agent','TOOL':'tool','CHAIN':'chain'}.get(kind,'span'),**(extra or {})}
        span={'traceId':self.trace,'spanId':secrets.token_hex(8),'name':name,'kind':1,'startTimeUnixNano':str(time.time_ns()),'attributes':attributes(values)}
        if parent:span['parentSpanId']=parent['spanId']
        return span

    def _end(self,span,state='SUCCEEDED'):
        span['endTimeUnixNano']=str(max(time.time_ns(),int(span['startTimeUnixNano'])))
        span['status']={'code':1 if state=='SUCCEEDED' else 2}
        span['attributes']+=attributes({'toolgate.observation.state':state})
        self.spans.append(span)

    def event(self,kind,**details):
        if not self.enabled or len(self.spans)+len(self.active)>=511:return
        subject=details.get('subjectId','');tool=details.get('toolRef','')
        if kind.endswith('_STARTED'):
            category=kind.removesuffix('_STARTED')
            if category not in ('MODEL','AGENT','TOOL'):return
            extra={}
            if subject:extra['toolgate.subject.id']=subject
            if category=='MODEL':extra.update({'gen_ai.operation.name':'chat','toolgate.model_group.id':self.groups.get(subject,'')})
            if tool:extra['tool.name']=tool
            parent=next((item[3] for item in reversed(self.active) if item[0]=='AGENT' and item[1]==subject),self.root) if category!='AGENT' else self.root
            span=self._start(category.lower()+' '+(tool or subject),{'MODEL':'LLM','AGENT':'AGENT','TOOL':'TOOL'}[category],parent,extra)
            self.active.append((category,subject,tool,span))
        elif kind.endswith('_COMPLETED') and kind!='NODE_COMPLETED':
            category=kind.removesuffix('_COMPLETED')
            for index in range(len(self.active)-1,-1,-1):
                item=self.active[index]
                if item[:3]==(category,subject,tool):self._end(self.active.pop(index)[3]);break
        elif kind=='NODE_COMPLETED':
            # The progress interface has no node-entry timestamp: record an event,
            # never invent an elapsed node duration.
            span=self._start('node '+details.get('nodeId',''),'CHAIN',self.root)
            span['endTimeUnixNano']=span['startTimeUnixNano'];span['status']={'code':1};self.spans.append(span)

    def finish(self,state):self.state=state if state in ('SUCCEEDED','FAILED','AWAITING_APPROVAL') else 'UNCONFIRMED'

    def __enter__(self):return self

    def __exit__(self,*exception):
        if not self.enabled:return
        if exception[0] is not None:self.state='UNCONFIRMED'
        for item in self.active:self._end(item[3],self.state if self.state!='SUCCEEDED' else 'UNCONFIRMED')
        self._end(self.root,self.state);self.exporter.export(self.spans)
