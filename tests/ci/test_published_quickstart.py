# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Published deployment boundaries: no app builds, isolated custody and configured runtime origins."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


class PublishedQuickstartTests(unittest.TestCase):
    def stack(self):
        return load('debug/profiled/stack.py','published_stack_case')

    def test_missing_images_are_pulled_once_and_never_built(self):
        env={'OLO_STACK_IMAGE_MODE':'published',**{'OLO_STACK_'+k+'_IMAGE':'publisher/'+k.lower()+':dev' for k in ('QUICKSTART','DEVICE','RUNTIME')}}
        with patch.dict(os.environ,env):
            stack=self.stack()
            with patch.object(stack,'image_exists',return_value=False),patch.object(stack,'run') as run:
                selected=stack.images()
                self.assertEqual(3,run.call_count)
                self.assertEqual({tuple(['docker','pull',v]) for v in selected.values()},{tuple(call.args[0]) for call in run.call_args_list})
            with patch.object(stack,'run') as run:
                self.assertEqual(selected,stack.images(validate_only=True));run.assert_not_called()
            with patch.object(stack,'image_exists',return_value=True),patch.object(stack,'run') as run:
                self.assertEqual(selected,stack.images())
                self.assertEqual({tuple(['docker','pull',v]) for v in selected.values()},{tuple(call.args[0]) for call in run.call_args_list})

    def test_cached_versioned_images_and_digests_are_reused_but_branch_dev_aliases_refresh(self):
        for tag,refresh in [('1.0.0-dev.77',False),('dev-feature-example',True),('@sha256:'+'a'*64,False)]:
            reference='publisher/image'+(tag if tag.startswith('@') else ':'+tag)
            env={'OLO_STACK_IMAGE_MODE':'published',**{'OLO_STACK_'+k+'_IMAGE':reference for k in ('QUICKSTART','DEVICE','RUNTIME')}}
            with patch.dict(os.environ,env):
                stack=self.stack()
                with patch.object(stack,'image_exists',return_value=True),patch.object(stack,'run') as run:
                    stack.images();self.assertEqual(3 if refresh else 0,run.call_count)

    def test_pull_failure_and_invalid_reference_never_fall_back_to_build(self):
        env={'OLO_STACK_IMAGE_MODE':'published',**{'OLO_STACK_'+k+'_IMAGE':'publisher/'+k.lower()+':dev' for k in ('QUICKSTART','DEVICE','RUNTIME')}}
        with patch.dict(os.environ,env):
            stack=self.stack()
            with patch.object(stack,'image_exists',return_value=False),patch.object(stack,'run',side_effect=RuntimeError('refused pull')) as run:
                with self.assertRaises(RuntimeError):stack.images()
                self.assertEqual(['docker','pull'],run.call_args.args[0][:2]);self.assertEqual(1,run.call_count)
            with patch.dict(os.environ,{'OLO_STACK_DEVICE_IMAGE':'--bad reference'}),patch.object(stack,'run') as run:
                with self.assertRaises(ValueError):stack.images(validate_only=True)
                run.assert_not_called()

    def test_new_installation_uses_separate_ports_custody_and_no_seed_profile(self):
        with tempfile.TemporaryDirectory() as temporary,patch.dict(os.environ,dict(OLO_STACK_HOME=temporary,OLO_STACK_PROJECT='olo-quickstart-dev',OLO_STACK_IMAGE_MODE='published',OLO_STACK_HTTP_PORT='28090',OLO_STACK_TLS_PORT='28450',OLO_STACK_REGISTRY_PORT='28552')):
            stack=self.stack();d=stack.definition(dict(quickstart='q:dev',device='d:dev',runtime='r:dev'))
        self.assertEqual({'quickstart','langgraph','device'},d['services'].keys())
        self.assertEqual(['127.0.0.1:28090:8080','127.0.0.1:28450:8443','127.0.0.1:28552:18552'],d['services']['quickstart']['ports'])
        self.assertEqual('https://localhost:28450',d['services']['quickstart']['environment']['TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL'])
        self.assertEqual('',d['services']['quickstart']['environment']['TOOLGATE_CONTROL_PROFILE_PATH'])
        self.assertEqual('true',d['services']['quickstart']['environment']['TOOLGATE_QUICKSTART_ADMIN_ONLY'])
        self.assertEqual('/opt/toolgate/installation_device.py',d['services']['device']['entrypoint'][-1])
        for volume in d['volumes'].values():
            self.assertTrue(volume['name'].startswith('olo-quickstart-dev_'));self.assertEqual(str(Path(temporary).resolve()),volume['labels']['io.ololabs.toolgate.profiled-root'])
        self.assertNotIn('admin',str(d['services']['langgraph']))

    def test_unsafe_or_colliding_ports_are_refused(self):
        for port in ('80','70000','18450'):
            with patch.dict(os.environ,{'OLO_STACK_HTTP_PORT':port,'OLO_STACK_TLS_PORT':'18450','OLO_STACK_REGISTRY_PORT':'18552'}):
                with self.assertRaises(ValueError):self.stack()

    def test_provisioned_runtime_uses_configured_control_and_gateway_origins(self):
        module=load('apps/quickstart/workflow_installation.py','installation_ports_case')
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);keys=root/'data/keys';keys.mkdir(parents=True)
            for name,data in [('device-ca.crt','fixture-ca'),('release-keys.json','{}'),('organization-keys.json','{}')]:
                (keys/name).write_text(data,encoding='utf-8')
            captured={}
            with patch.object(module,'Path',side_effect=lambda path:root/str(path).lstrip('/')),patch.object(module,'private',side_effect=lambda path,value,**kwargs:captured.update({Path(path).name:value})),patch.dict(os.environ,{'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL':'https://localhost:28450','TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL':'https://localhost:28451'}):
                module.provision(dict(accessToken='fixture-token',tenantId='tenant-fixture',expiresAtUnixMs=123))
            self.assertEqual('https://localhost:28450',json.loads(captured['graph.json'])['controlOrigin'])
            service=json.loads(captured['service.json']);self.assertEqual('https://localhost:28450',service['controlOrigin']);self.assertEqual('https://localhost:28451',service['gatewayOrigin'])
            self.assertNotIn('fixture-token',captured['service.json'])

    def test_env_parser_keeps_secrets_out_of_application_settings(self):
        module=load('deploy/docker/quickstart/dev/manage.py','published_settings_case')
        with tempfile.TemporaryDirectory() as temporary,patch.object(module,'HERE',Path(temporary)):
            here=Path(temporary);(here/'.env.example').write_text('HTTP_PORT=18090\n',encoding='utf-8')
            (here/'.env').write_text('HTTP_PORT=28090\n',encoding='utf-8');self.assertEqual({'HTTP_PORT':'28090'},module.settings())
            for invalid in ('API_KEY=fixture\n','HTTP_PORT=28090\nHTTP_PORT=29090\n'):
                (here/'.env').write_text(invalid,encoding='utf-8')
                with self.assertRaises(ValueError):module.settings()


if __name__=='__main__':unittest.main()
