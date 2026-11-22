# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Shared deployment ownership and fail-closed startup; no live qualification claim."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]
HOME=ROOT/'deploy/docker/multitenant/dev'
spec=importlib.util.spec_from_file_location('multitenant_deployment',HOME/'manage.py')
deployment=importlib.util.module_from_spec(spec);spec.loader.exec_module(deployment)


class MultitenantDeploymentTests(unittest.TestCase):
    def layout(self,home):
        shutil.copyfile(HOME/'.env.example',home/'.env.example')
        root=home/'credentials'
        def write(name,value):
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(json.dumps(value) if isinstance(value,dict) else value,encoding='utf-8')
        passwords={name:'synthetic-'+name for name in ('admin','migrator','runtime','platform')}
        for name,value in passwords.items():write('database/'+name+'-password',value)
        for name in ('database/server.crt','database/server.key','control/database-ca.pem',
                     'ingress/tls.crt','ingress/tls.key','ingress/control-ca.pem',
                     'graph-builder/server.crt','graph-builder/server.key','graph-builder/ca.crt','graph-builder/control-ca.crt'):
            write(name,'synthetic-structure-only')
        write('control/platform-password',passwords['platform'])
        control=dict(QUARKUS_DATASOURCE_USERNAME='control_app',QUARKUS_FLYWAY_USERNAME='control_migrator',
            QUARKUS_DATASOURCE_PASSWORD=passwords['runtime'],QUARKUS_FLYWAY_PASSWORD=passwords['migrator'],
            TOOLGATE_CONTROL_PLATFORM_USERNAME='control_provisioner',
            TOOLGATE_CONTROL_PLATFORM_PASSWORD_PATH='/etc/toolgate/platform-password',
            TOOLGATE_INITIALIZE_MANIFEST='/etc/toolgate/manifest.json',
            TOOLGATE_CONTROL_RUNTIME_BUILDER_ORIGIN='https://graph-builder:19460',
            QUARKUS_DATASOURCE_JDBC_URL='jdbc:postgresql://postgres:5432/toolgate?sslmode=verify-full&sslrootcert=/etc/toolgate/database-ca.pem')
        write('control/control.env','\n'.join(k+'='+v for k,v in control.items()))
        graph=json.loads((HOME/'examples/graph.json').read_text())
        rows=[]
        for index,letter in enumerate(('a','b'),1):
            tenant='10000000-0000-4000-8000-00000000000'+str(index)
            host='tenant-'+letter+'.olo.test'
            control_origin='https://'+host+(':'+str(18443 if letter=='a' else 19443))
            gateway_origin='https://'+host+(':'+str(18444 if letter=='a' else 19444))
            rows.append(dict(alias=letter,name=letter,hosts=[host],administratorProvisioningRef=letter,
                             endpoints=dict(serverId='server-'+letter,controlUrl=control_origin,gatewayUrl=gateway_origin)))
            write('control/'+letter+'/packet.json',{});write('control/'+letter+'/trust.json',{})
            write('tenant-'+letter+'/gateway/config.json',dict(control=dict(tenantId=tenant,url=control_origin),
                publicDiscovery=dict(controlOrigin=control_origin,gatewayOrigin=gateway_origin),
                listen='0.0.0.0:8081',managementListen='0.0.0.0:9091',toolHosts=[],trustedTlsProxy=True))
            write('tenant-'+letter+'/gateway/credentials.json',{})
            write('tenant-'+letter+'/gateway/model.env','TOOLGATE_WORKFLOW_MODEL_ORIGINS=https://model.invalid')
            write('tenant-'+letter+'/host/config.json',{})
            write('tenant-'+letter+'/worker/worker.token','synthetic-'+letter)
            write('tenant-'+letter+'/worker/ca.crt','synthetic-public-ca')
            write('graph-builder/reader-'+letter+'.token','synthetic-'+letter)
        write('control/manifest.json',dict(formatVersion=1,defaultTenantAlias='a',tenants=rows))
        write('graph-builder/graph.json',graph)
        return root

    def test_missing_or_cross_tenant_custody_stops_before_compose(self):
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary);root=self.layout(home)
            self.assertEqual(root,deployment.validate(home))
            (root/'graph-builder/reader-b.token').write_text('synthetic-a')
            with patch.object(deployment.subprocess,'run') as run, self.assertRaises(ValueError):
                deployment.command('start',home)
            run.assert_not_called()
            (root/'graph-builder/reader-b.token').unlink()
            with self.assertRaises(ValueError):deployment.validate(home)

    def test_old_reader_contract_and_non_image_settings_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary);root=self.layout(home)
            path=root/'graph-builder/graph.json';graph=json.loads(path.read_text())
            graph['controlOrigin']='https://tenant-a.olo.test:18443';path.write_text(json.dumps(graph))
            with self.assertRaises(ValueError):deployment.validate(home)
            (home/'.env').write_text('CREDENTIALS_DIR=/another-installation')
            with self.assertRaises(ValueError):deployment.settings(home)

    @unittest.skipUnless(shutil.which('docker'),'Docker Compose required')
    def test_render_preserves_pool_custody_ports_and_platform_boundaries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=self.layout(Path(temporary))
            env=dict(os.environ,**deployment.settings(),CREDENTIALS_DIR=str(root))
            result=subprocess.run(['docker','compose','-f',str(HOME/'compose.yaml'),'config','--format','json'],
                                  env=env,capture_output=True,text=True,check=True,timeout=60)
            config=json.loads(result.stdout);services=config['services']
            self.assertEqual({'postgres','control','gateway-a','gateway-b','host-a','host-b','worker-a','worker-b','graph-builder','ingress'},set(services))
            self.assertEqual({'platform'},set(services['postgres']['networks']))
            self.assertEqual({'graph-control'},set(services['graph-builder']['networks']))
            self.assertEqual({18443,18444,19443,19444},{int(p['published']) for p in services['ingress']['ports']})
            self.assertTrue(all(p['host_ip']=='127.0.0.1' for p in services['ingress']['ports']))
            for letter in ('a','b'):
                self.assertEqual({'tenant-'+letter},set(services['host-'+letter]['networks']))
                self.assertEqual({'ingress-'+letter},set(services['worker-'+letter]['networks']))
                for kind in ('gateway','host','worker'):
                    service=services[kind+'-'+letter]
                    self.assertTrue(service['read_only']);self.assertFalse(service.get('ports'))
                    for mount in service['volumes']:
                        self.assertNotIn('tenant-'+('b' if letter=='a' else 'a'),mount['source'])
                        self.assertNotIn('/control',mount['source'].replace('\\','/'))
                        self.assertNotIn('docker.sock',mount['source'])

    def test_stop_retains_volumes_and_never_validates_or_resets_stores(self):
        with patch.object(deployment,'validate') as validate,patch.object(deployment.subprocess,'run') as run:
            deployment.command('stop')
        validate.assert_not_called()
        self.assertEqual('stop',run.call_args.args[0][-1])
        self.assertNotIn('down',run.call_args.args[0])

    def test_startup_menu_defaults_to_no_import_and_exposes_use_cases(self):
        with patch.object(deployment.sys.stdin,'isatty',return_value=True),patch('builtins.input',return_value=''):
            self.assertEqual('none',deployment.choose_use_case())
        for choice,expected in (('1','incident'),('2','log-triage'),('3','mcp-proxy'),
                                ('4','device-file'),('5','authority'),('6','publication-a'),
                                ('7','publication-b'),('8','portable')):
            with patch.object(deployment.sys.stdin,'isatty',return_value=True),patch('builtins.input',return_value=choice):
                self.assertEqual(expected,deployment.choose_use_case())

    @unittest.skipUnless(os.name=='nt' and shutil.which('powershell.exe'),'Windows PowerShell required')
    def test_windows_start_help_from_another_directory_never_deploys(self):
        with tempfile.TemporaryDirectory() as temporary:
            result=subprocess.run(['cmd','/c',str(HOME/'start.bat'),'--help'],cwd=temporary,
                                  capture_output=True,text=True,timeout=30)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('--use-case',result.stdout)

    def test_actual_export_bytes_are_reused_with_separate_destination_operators(self):
        from bot.build import build
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary);root=self.layout(home)
            rows=build(ROOT/'bot/configurations/tenant-incident-triage',home,'1.0.0-test','a'*40,'test',{},None)
            bundle=home/rows[0]['filename'];raw=bundle.read_bytes()
            for letter in ('a','b'):(root/('tenant-'+letter)/'operator.token').write_text('synthetic-operator-'+letter)
            clients=[Mock(),Mock()]
            with patch('tools.admin.control.Control',side_effect=clients) as constructor, \
                 patch('tools.admin.import_workflow.deploy',return_value={'state':'PUBLISHED'}) as deploy:
                deployment.import_bot(bundle,('a','b'),'incident',home)
            self.assertEqual(['https://tenant-a.olo.test:18443','https://tenant-b.olo.test:19443'],
                             [call.args[0] for call in constructor.call_args_list])
            self.assertEqual([(clients[0],raw),(clients[1],raw)],[call.args for call in deploy.call_args_list])
            (root/'tenant-b/operator.token').unlink()
            with patch('tools.admin.control.Control',return_value=Mock()), \
                 patch('tools.admin.import_workflow.deploy') as deploy, self.assertRaises(ValueError):
                deployment.import_bot(bundle,('a','b'),'incident',home)
            deploy.assert_not_called()
