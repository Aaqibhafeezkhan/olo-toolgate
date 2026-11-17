# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Private rebuild API: real TLS, immutable candidate checks, no workflow effects."""
from copy import deepcopy
from dataclasses import replace
import http.client
import http.server
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest

from toolgate_runtime.graph_packages import GraphRefused
from toolgate_runtime.graph_runtime import GraphRuntime, ReviewedCandidate, ControlCandidates
from toolgate_runtime.graph_server import server
from toolgate_runtime.json_graph import cache_status, clear_cache
from test_json_graph import spec, installed_selection
from test_runner import lease


REQUEST=dict(tenantId='tenant-a',profileId='profile',profileDigest='sha256:'+'a'*64,workflowId='flow',expectedGeneration=0)


class RuntimeBuildTests(unittest.TestCase):
    def test_explicit_installed_native_factory_compiles_without_a_json_fallback(self):
        self.candidate=replace(self.candidate,graph=lease().graph)
        result=self.runtime.rebuild(REQUEST)
        self.assertEqual('READY',result['state'])
        self.assertEqual(['native-factory'],result['features'])
        invalid={**self.candidate.graph,'digest':'sha256:'+'0'*64}
        self.candidate=replace(self.candidate,graph=invalid)
        with self.assertRaisesRegex(GraphRefused,'GRAPH_PACKAGE_UNAVAILABLE'):
            self.runtime.rebuild(REQUEST)

    def setUp(self):
        clear_cache()
        self.candidate=ReviewedCandidate('tenant-a','profile',REQUEST['profileDigest'],'flow',0,{**installed_selection(),'config':spec()})
        self.calls=[]
        def resolve(value):
            self.calls.append(deepcopy(value))
            return self.candidate
        self.runtime=GraphRuntime(resolve)

    def test_build_receipt_is_stable_and_every_cache_hit_reauthorizes(self):
        first=self.runtime.rebuild(REQUEST)
        self.assertEqual(first,self.runtime.rebuild(REQUEST))
        self.assertEqual('READY',first['state'])
        self.assertEqual(2,len(self.calls))
        self.assertEqual(1,cache_status()['entries'])
        self.assertNotIn('graph',first)
        self.assertNotIn('config',first)

    def test_other_tenant_workflow_digest_and_generation_cannot_reuse_cache_authority(self):
        self.runtime.rebuild(REQUEST)
        original=self.candidate
        for changes in (dict(tenant_id='tenant-b'),dict(workflow_id='other'),dict(profile_digest='sha256:'+'b'*64),dict(generation=1)):
            self.candidate=replace(original,**changes)
            with self.assertRaisesRegex(GraphRefused,'CANDIDATE_MISMATCH'):
                self.runtime.rebuild(REQUEST)

    def test_request_cannot_inject_graph_code_or_client_configuration(self):
        for changes in (dict(graph={}),dict(token='secret'),dict(expectedGeneration=True),dict(profileDigest='bad')):
            with self.assertRaises(GraphRefused):self.runtime.rebuild({**REQUEST,**changes})
        self.assertEqual([],self.calls)

    def test_failed_rebuild_keeps_previous_compilation(self):
        previous=self.runtime.rebuild(REQUEST)
        bad=deepcopy(self.candidate.graph);bad['config']['nodes'][0]['kind']='python'
        self.candidate=replace(self.candidate,graph=bad)
        with self.assertRaises(GraphRefused):self.runtime.rebuild(REQUEST)
        self.assertEqual(1,cache_status()['entries'])
        self.assertEqual('READY',previous['state'])

    def test_control_adapter_checks_authoritative_tenant_and_candidate_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            token=Path(temporary)/'worker.token';token.write_text('test-workload-token')
            client=ControlCandidates('https://control.invalid',{'tenant-a':str(token)})
            value=dict(tenantId='tenant-a',profileId='profile',profileDigest=REQUEST['profileDigest'],workflowId='flow',generation=0,graph=self.candidate.graph,agentIds=[])
            observed=[]
            def get(path,credential):
                observed.append((path,credential));return value
            client._get=get
            self.assertEqual(self.candidate,client(REQUEST))
            self.assertTrue(observed[0][0].startswith('/internal/v1/runtime/graph-candidates/'))
            value['tenantId']='tenant-b'
            with self.assertRaisesRegex(GraphRefused,'CANDIDATE_MISMATCH'):client(REQUEST)
            with self.assertRaisesRegex(GraphRefused,'TENANT_REFUSED'):client({**REQUEST,'tenantId':'tenant-b'})

    def test_build_refuses_agents_outside_the_reviewed_workflow_binding(self):
        config=spec([dict(id='agent',kind='agent',agentId='selected',input={'$path':'/message'})],
            [{'from':['START'],'to':'agent'},{'from':['agent'],'to':'END'}],{'$path':'/values/agent'})
        self.candidate=replace(self.candidate,graph={**self.candidate.graph,'config':config})
        with self.assertRaisesRegex(GraphRefused,'AGENT_UNASSIGNED'):self.runtime.rebuild(REQUEST)
        self.candidate=replace(self.candidate,agent_ids=('selected',))
        self.assertEqual('READY',self.runtime.rebuild(REQUEST)['state'])


@unittest.skipUnless(shutil.which('openssl'),'OpenSSL required for ephemeral mutual TLS tests')
class RuntimeTlsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.home=Path(cls.temp.name)
        def run(*args):subprocess.run(['openssl',*args],cwd=cls.home,check=True,capture_output=True)
        run('req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','ca.key','-out','ca.crt',
            '-subj','/CN=Test Runtime CA','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
        for name in ('localhost','control-builder','other-service'):
            run('req','-new','-newkey','rsa:2048','-nodes','-keyout',name+'.key','-out',name+'.csr','-subj','/CN='+name)
            ext=cls.home/(name+'.ext')
            ext.write_text('basicConstraints=critical,CA:FALSE\nextendedKeyUsage='+('serverAuth\nsubjectAltName=DNS:localhost\n' if name=='localhost' else 'clientAuth\n'))
            run('x509','-req','-days','1','-in',name+'.csr','-CA','ca.crt','-CAkey','ca.key','-CAcreateserial','-out',name+'.crt','-extfile',str(ext))

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def setUp(self):
        clear_cache()
        self.calls=[]
        def resolve(value):
            self.calls.append(value)
            return ReviewedCandidate('tenant-a','profile',REQUEST['profileDigest'],'flow',0,{**installed_selection(),'config':spec()})
        self.http=server(('127.0.0.1',0),GraphRuntime(resolve),certificate=str(self.home/'localhost.crt'),
            key=str(self.home/'localhost.key'),client_ca=str(self.home/'ca.crt'),client_names=['control-builder'])
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()

    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.thread.join(3)
        self.assertFalse(self.thread.is_alive())

    def request(self,method,path,body=None,identity='control-builder',headers=None):
        context=ssl.create_default_context(cafile=str(self.home/'ca.crt'))
        if identity:context.load_cert_chain(str(self.home/(identity+'.crt')),str(self.home/(identity+'.key')))
        connection=http.client.HTTPSConnection('localhost',self.http.server_port,context=context,timeout=5)
        try:
            connection.request(method,path,body=json.dumps(body) if body is not None else None,headers=headers or {'Content-Type':'application/json'})
            reply=connection.getresponse()
            return reply.status,json.loads(reply.read())
        finally:connection.close()

    def test_build_capabilities_and_eviction_over_authenticated_tls(self):
        status,value=self.request('POST','/internal/runtime/v1/graphs:rebuild',REQUEST)
        self.assertEqual((200,'READY'),(status,value['state']))
        self.assertEqual(1,self.request('GET','/internal/runtime/v1/capabilities')[1]['entries'])
        self.assertEqual(0,self.request('DELETE','/internal/runtime/v1/graphs/cache')[1]['entries'])
        self.assertEqual(1,len(self.calls))

    def test_missing_certificate_and_wrong_service_are_refused_before_candidate_access(self):
        with self.assertRaises((ssl.SSLError,ConnectionError,OSError)):
            self.request('POST','/internal/runtime/v1/graphs:rebuild',REQUEST,identity=None)
        self.assertEqual(403,self.request('POST','/internal/runtime/v1/graphs:rebuild',REQUEST,identity='other-service')[0])
        self.assertEqual([],self.calls)

    def test_wrong_media_and_oversized_request_are_refused(self):
        self.assertEqual(415,self.request('POST','/internal/runtime/v1/graphs:rebuild',REQUEST,headers={'Content-Type':'text/plain'})[0])
        self.assertEqual(413,self.request('POST','/internal/runtime/v1/graphs:rebuild',{'padding':'x'*8193})[0])
        self.assertEqual([],self.calls)

    def test_control_candidate_transport_uses_strict_tls_and_exact_tenant_response(self):
        value=dict(tenantId='tenant-a',profileId='profile',profileDigest=REQUEST['profileDigest'],workflowId='flow',
            generation=0,graph={**installed_selection(),'config':spec()},agentIds=[])
        observed=[]
        class CandidateHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                observed.append((self.path,self.headers.get('Authorization')))
                body=json.dumps(value).encode()
                self.send_response(200);self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        endpoint=http.server.ThreadingHTTPServer(('127.0.0.1',0),CandidateHandler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(self.home/'localhost.crt'),str(self.home/'localhost.key'))
        endpoint.socket=context.wrap_socket(endpoint.socket,server_side=True)
        thread=threading.Thread(target=endpoint.serve_forever,daemon=True);thread.start()
        token=self.home/'candidate-reader.token';token.write_text('synthetic-workload-token')
        try:
            reader=ControlCandidates('https://localhost:'+str(endpoint.server_port),{'tenant-a':str(token)},ca_file=str(self.home/'ca.crt'))
            result=reader(REQUEST)
            self.assertEqual(('tenant-a','flow'),(result.tenant_id,result.workflow_id))
            self.assertEqual('Bearer synthetic-workload-token',observed[0][1])
            self.assertIn('sha256%3A',observed[0][0])
            value['tenantId']='tenant-b'
            with self.assertRaisesRegex(GraphRefused,'CANDIDATE_MISMATCH'):reader(REQUEST)
        finally:
            endpoint.shutdown();endpoint.server_close();thread.join(3)
