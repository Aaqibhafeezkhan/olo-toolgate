# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Reviewed-candidate compilation, fetched through live Control authorization."""
from dataclasses import dataclass, field
import json
from pathlib import Path
import ssl
from urllib.parse import quote, urlsplit
import urllib.request

from .graph_api import json_value
from .graph_packages import GraphPackages, GraphRefused, schema_validator, _object
from .json_graph import COMPILER_DIGEST, FEATURES, specification_digest, validate

DEFINITIONS = json.loads(Path(__file__).with_name('graph-runtime.schema.json').read_text(encoding='utf-8'))['$defs']


def contract(name):
    return schema_validator({'$ref':'#/$defs/'+name,'$defs':DEFINITIONS})


@dataclass(frozen=True)
class ReviewedCandidate:
    tenant_id: str
    profile_id: str
    profile_digest: str
    workflow_id: str
    generation: int
    graph: dict = field(repr=False)
    agent_ids: tuple[str,...] = ()


class GraphRuntime:
    def __init__(self, candidates, *, graph_directory=None):
        self.candidates = candidates
        self.packages = GraphPackages(graph_directory)
        self.request = contract('WorkflowGraphBuildRequest')
        self.result = contract('WorkflowGraphBuildResult')

    def rebuild(self, value):
        value = json_value(value, max_bytes=8192)
        if not self.request.is_valid(value):
            raise GraphRefused('GRAPH_BUILD_REQUEST')
        # Always reauthorize/fetch. A cache hit is never authority to read a candidate.
        candidate = self.candidates(value)
        if (not isinstance(candidate, ReviewedCandidate)
                or (candidate.tenant_id,candidate.profile_id,candidate.profile_digest,candidate.workflow_id,candidate.generation)
                    != (value['tenantId'],value['profileId'],value['profileDigest'],value['workflowId'],value['expectedGeneration'])):
            raise GraphRefused('GRAPH_CANDIDATE_MISMATCH')
        selection = json_value(candidate.graph)
        features = ['native-factory']
        if selection.get('packageId') == 'olo.json-graph':
            if selection.get('entryPoint') != 'workflow':
                raise GraphRefused('GRAPH_BUILD_FACTORY_UNSUPPORTED')
            spec = validate(selection.get('config'))
            if any(node.get('agentId') not in candidate.agent_ids for node in spec['nodes'] if node['kind'] in ('agent','model','tool')):
                raise GraphRefused('GRAPH_AGENT_UNASSIGNED')
            features = list(FEATURES)
        with self.packages.load(selection):
            pass  # Compilation only; no RunContext, model call, tool effect or publication.
        receipt = dict(tenantId=candidate.tenant_id,profileId=candidate.profile_id,
            profileDigest=candidate.profile_digest,workflowId=value['workflowId'],generation=candidate.generation,
            specificationDigest=specification_digest(selection['config']),compilerDigest=COMPILER_DIGEST,
            features=features,state='READY')
        if not self.result.is_valid(receipt):
            raise GraphRefused('GRAPH_BUILD_RECEIPT')
        return receipt


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None


class ControlCandidates:
    """Installation-scoped workload credentials, never accepted from a build request.

    The operator explicitly binds each credential file to its Control tenant.
    Control verifies runtime service authority on every request. This
    adapter requires a reviewed candidate; it cannot publish or provision it.
    """
    def __init__(self, readers, *, ca_file=None, timeout=10):
        if not 0 < timeout <= 30 or not isinstance(readers,dict) or not readers:
            raise ValueError('Configure bounded Control readers')
        if len(readers)>256:
            raise ValueError('Configure bounded Control readers')
        selected={}
        for tenant,reader in readers.items():
            if (not isinstance(tenant,str) or not tenant or len(tenant)>128
                    or not isinstance(reader,dict) or set(reader)!={'origin','tokenFile'}
                    or not isinstance(reader['origin'],str) or not isinstance(reader['tokenFile'],str)
                    or not Path(reader['tokenFile']).is_absolute()):
                raise ValueError('Configure a tenant origin and absolute reader token file')
            parts=urlsplit(reader['origin'])
            if (parts.scheme!='https' or not parts.hostname or parts.username or parts.password
                    or parts.path not in ('','/') or parts.query or parts.fragment):
                raise ValueError('Use a direct TLS Control origin')
            parts.port  # Refuse malformed ports before loading credentials or opening transport.
            selected[tenant]=dict(origin=reader['origin'].rstrip('/'),tokenFile=reader['tokenFile'])
        self.readers,self.timeout=selected,timeout
        context = ssl.create_default_context(cafile=ca_file)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=context))

    def _get(self, origin, path, token):
        request=urllib.request.Request(origin+path,headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
        try:
            with self.opener.open(request,timeout=self.timeout) as response:
                if response.status != 200 or response.headers.get_content_type() != 'application/json':
                    raise GraphRefused('GRAPH_CONTROL_RESPONSE')
                raw=response.read(262145)
                if len(raw)>262144:
                    raise GraphRefused('GRAPH_CONTROL_RESPONSE')
                return json.loads(raw,object_pairs_hook=_object)
        except GraphRefused:
            raise
        except Exception:
            raise GraphRefused('GRAPH_CANDIDATE_UNAVAILABLE') from None

    def __call__(self, request):
        reader=self.readers.get(request['tenantId'])
        if reader is None:
            raise GraphRefused('GRAPH_TENANT_REFUSED')
        try:
            with Path(reader['tokenFile']).open(encoding='ascii') as stream:
                token=stream.read(8193).strip()
            if not token or len(token)>8192:
                raise ValueError()
        except (OSError,ValueError):
            raise GraphRefused('GRAPH_READER_UNAVAILABLE') from None
        path='/internal/v1/runtime/graph-candidates/'+quote(request['profileId'],safe='')+'/'+quote(request['profileDigest'],safe='')+'/'+quote(request['workflowId'],safe='')
        value=self._get(reader['origin'],path+'?expectedGeneration='+str(request['expectedGeneration']),token)
        if (not contract('WorkflowGraphCandidate').is_valid(value)
                or (value['tenantId'],value['profileId'],value['profileDigest'],value['workflowId'],value['generation'])
                    != (request['tenantId'],request['profileId'],request['profileDigest'],request['workflowId'],request['expectedGeneration'])):
            raise GraphRefused('GRAPH_CANDIDATE_MISMATCH')
        return ReviewedCandidate(value['tenantId'],value['profileId'],value['profileDigest'],value['workflowId'],value['generation'],value['graph'],tuple(value['agentIds']))
