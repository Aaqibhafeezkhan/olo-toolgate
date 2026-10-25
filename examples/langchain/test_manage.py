# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Group selection must retain enrollment, availability and platform boundaries."""
import contextlib
from copy import deepcopy
import io
import json
import subprocess
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import manage


LINUX = 'device-' + 'a' * 32
WINDOWS = 'device-' + 'b' * 32
NOW = 1_800_000_000_000


def registration(device, platform):
    return dict(deviceId=device, platform=platform, gateway=manage.gateway_url())


def endpoint(device):
    return dict(deviceId=device, systemExecutor=False, directoryDevice=dict(enabled=True),
                endpointDevice=dict(state='ACTIVE', connectionApproved=True,
                                    lastSeenUnixMs=NOW - 1000, connectionExpiresAtUnixMs=NOW + 60000))


class Configuration:
    def __init__(self):
        self.group = dict(enabled=True, deviceIds=[LINUX, WINDOWS])
        self.rows = [endpoint(LINUX), endpoint(WINDOWS)]
        self.allocations = []

    def api(self, path):
        if path == '/api/control/v1/device-groups/' + manage.DEVICE_GROUP:
            return self.group
        if path == '/api/control/v1/endpoint/devices':
            return dict(items=self.rows)
        if path in ['/api/control/v1/devices/' + device + '/groups' for device in (LINUX, WINDOWS)]:
            return dict(groupIds=['existing-team-device-group'])
        raise AssertionError('Unexpected API operation: ' + path)

    def memberships(self, collection, device, groups):
        self.allocations.append((collection, device, groups))


class DeviceSelectionTests(unittest.TestCase):
    def setUp(self):
        self.config = Configuration()
        self.records = [registration(LINUX, 'linux'), registration(WINDOWS, 'windows')]
        clock = patch.object(manage.time, 'time', return_value=NOW / 1000)
        clock.start()
        self.addCleanup(clock.stop)

    def test_any_offers_both_group_members_to_selection(self):
        with patch.object(manage.secrets, 'choice', return_value=self.records[1]) as choice:
            self.assertEqual(manage.select_device(self.config, self.records, 'any')['deviceId'], WINDOWS)
            self.assertEqual(choice.call_args.args[0], self.records)

    def test_windows_filter_and_explicit_linux_filter(self):
        self.assertEqual(manage.select_device(self.config, self.records, 'windows')['deviceId'], WINDOWS)
        self.assertEqual(manage.select_device(self.config, self.records, 'linux')['deviceId'], LINUX)

    def test_unavailable_windows_falls_back_to_linux_only_for_any(self):
        self.config.rows[1]['endpointDevice']['lastSeenUnixMs'] = NOW - 120000
        self.assertEqual(manage.select_device(self.config, self.records, 'any')['deviceId'], LINUX)
        with self.assertRaisesRegex(ValueError, 'No available prepared windows'):
            manage.select_device(self.config, self.records, 'windows')

    def test_each_ineligible_state_is_excluded(self):
        changes = [
            ('directoryDevice', 'enabled', False),
            ('endpointDevice', 'connectionApproved', False),
            ('endpointDevice', 'state', 'REVOKED'),
            ('endpointDevice', 'connectionExpiresAtUnixMs', NOW),
            ('endpointDevice', 'lastSeenUnixMs', NOW + 1000),
            ('endpointDevice', 'lastSeenUnixMs', 0),
        ]
        for section, key, value in changes:
            with self.subTest(section=section, key=key):
                self.config.rows = [endpoint(LINUX), endpoint(WINDOWS)]
                self.config.rows[1][section][key] = value
                self.assertEqual(manage.select_device(self.config, self.records, 'any')['deviceId'], LINUX)

    def test_group_membership_registration_and_enabled_group_required(self):
        self.config.group['deviceIds'] = [LINUX]
        with self.assertRaisesRegex(ValueError, 'No available'):
            manage.select_device(self.config, self.records, 'windows')
        self.config.group['deviceIds'] = [WINDOWS]
        with self.assertRaisesRegex(ValueError, 'No available'):
            manage.select_device(self.config, self.records[:1], 'any')
        self.config.group['enabled'] = False
        with self.assertRaisesRegex(ValueError, 'must be enabled'):
            manage.select_device(self.config, self.records, 'any')

    def test_device_override_cannot_escape_group_or_os_filter(self):
        self.assertEqual(manage.select_device(self.config, self.records, 'any', WINDOWS)['deviceId'], WINDOWS)
        with self.assertRaisesRegex(ValueError, 'No available'):
            manage.select_device(self.config, self.records, 'windows', LINUX)
        self.config.group['deviceIds'] = [LINUX]
        with self.assertRaisesRegex(ValueError, 'No available'):
            manage.select_device(self.config, self.records, 'any', WINDOWS)

    def test_restart_requires_fresh_check_in_before_running_tools(self):
        before = endpoint(WINDOWS)['endpointDevice']
        after = {**before, 'lastSeenUnixMs': NOW}
        config = SimpleNamespace(api=lambda path: None)
        with patch.object(config, 'api', side_effect=[before, dict(enabled=True), after, dict(enabled=True)]) as api, \
                patch.object(manage.time, 'sleep') as sleep:
            manage.wait_device(config, WINDOWS, seen_after=NOW, timeout=360)
        self.assertEqual(api.call_count, 4)
        sleep.assert_called_once_with(2)

    def test_new_linux_approval_waits_for_first_check_in_before_registration_and_selection(self):
        self.config.rows[0]['endpointDevice']['lastSeenUnixMs'] = 0
        original_api = self.config.api
        events = []

        def api(path, body=None):
            if path.startswith('/api/control/v1/endpoint/enrollments/review?'):
                return dict(deviceId=LINUX, keyFingerprint='test-fingerprint')
            if path == '/api/control/v1/endpoint/enrollments/decision':
                self.assertEqual(body['choice'], 'APPROVE')
                events.append('approved')
                return {}
            if path == '/api/control/v1/endpoint/devices/' + LINUX:
                return self.config.rows[0]['endpointDevice']
            if path == '/api/control/v1/devices/' + LINUX:
                return self.config.rows[0]['directoryDevice']
            return original_api(path)

        def check_in(seconds):
            self.assertEqual(seconds, 2)
            events.append('checked-in')
            self.config.rows[0]['endpointDevice']['lastSeenUnixMs'] = NOW

        with tempfile.TemporaryDirectory() as temporary, patch.object(manage, 'STATE', Path(temporary)), \
                patch.object(self.config, 'api', side_effect=api), \
                patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='PENDING', userCode='test-code', keyFingerprint='test-fingerprint')), \
                patch.object(manage.time, 'sleep', side_effect=check_in):
            records = manage.register_devices(self.config, SimpleNamespace(approve_linux_device=True, device_id=None, target='linux'))
        self.assertEqual(manage.select_device(self.config, records, 'linux')['deviceId'], LINUX)
        self.assertEqual(self.config.allocations, [('devices', LINUX, sorted(['existing-team-device-group', manage.DEVICE_GROUP]))])

    def test_new_linux_approval_cannot_prepare_a_device_that_never_checks_in(self):
        self.config.rows[0]['endpointDevice']['lastSeenUnixMs'] = 0
        original_api = self.config.api

        def api(path, body=None):
            if path.startswith('/api/control/v1/endpoint/enrollments/review?'):
                return dict(deviceId=LINUX, keyFingerprint='test-fingerprint')
            if path == '/api/control/v1/endpoint/enrollments/decision':
                return {}
            if path == '/api/control/v1/endpoint/devices/' + LINUX:
                return self.config.rows[0]['endpointDevice']
            if path == '/api/control/v1/devices/' + LINUX:
                return self.config.rows[0]['directoryDevice']
            return original_api(path)

        with tempfile.TemporaryDirectory() as temporary, patch.object(manage, 'STATE', Path(temporary)), \
                patch.object(self.config, 'api', side_effect=api), \
                patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='PENDING', userCode='test-code', keyFingerprint='test-fingerprint')), \
                patch.object(manage, 'docker') as docker, patch.object(manage.time, 'monotonic', side_effect=[0, 91]):
            with self.assertRaisesRegex(ValueError, 'Approve and enable'):
                manage.register_devices(self.config, SimpleNamespace(approve_linux_device=True, device_id=None, target='linux'))
        docker.assert_not_called()
        self.assertEqual(self.config.allocations, [])

    def test_register_selected_windows_device_preserves_existing_groups(self):
        args = SimpleNamespace(device_id=WINDOWS, target='windows', approve_linux_device=False)
        with patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='ACTIVE')):
            records = manage.register_devices(self.config, args)
        self.assertEqual({record['deviceId'] for record in records}, {LINUX, WINDOWS})
        self.assertEqual(self.config.allocations, [
            ('devices', device, sorted(['existing-team-device-group', manage.DEVICE_GROUP]))
            for device in (LINUX, WINDOWS)])

    def test_an_external_device_cannot_be_treated_as_linux(self):
        args = SimpleNamespace(device_id=WINDOWS, target='linux', approve_linux_device=False)
        with patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='ACTIVE')):
            with self.assertRaisesRegex(ValueError, 'external device requires'):
                manage.register_devices(self.config, args)

    def test_foreign_gateway_registration_is_not_selected(self):
        self.records[1]['gateway'] = 'https://localhost:18450'
        with self.assertRaisesRegex(ValueError, 'No available'):
            manage.select_device(self.config, self.records, 'windows')


class DeploymentMetadataTests(unittest.TestCase):
    def container(self,service,identity,ip):
        return dict(Id=identity,Name='/toolgate-langchain-'+service+'-1',
                    Config=dict(Hostname=service,Labels={'com.docker.compose.service':service},Env=['SECRET=must-not-export']),
                    NetworkSettings=dict(Networks={'toolgate-langchain_default':dict(NetworkID='c'*64,IPAddress=ip,GlobalIPv6Address='')}))

    def test_current_execution_identities_share_two_containers_and_registry_network(self):
        containers=[self.container('quickstart','a'*64,'172.18.0.2'),self.container('linux-device','b'*64,'172.18.0.3'),self.container('registry','c'*64,'172.18.0.2')]
        identities=dict(TOOL_HOST_DIRECT='tool-host-local',TOOL_HOST_PULL='tool-host-remote')
        replies=[SimpleNamespace(stdout='a'*64+'\n'+'b'*64+'\n'+'c'*64+'\n'),SimpleNamespace(stdout=json.dumps(containers))]
        with patch.object(manage.subprocess,'run',side_effect=replies), \
                patch.object(manage,'docker',side_effect=[json.dumps(identities),'']) as docker, \
                patch.object(manage,'linux_status',return_value=dict(deviceId=LINUX,state='ACTIVE',updatedAtUnixMs=NOW)),contextlib.redirect_stdout(io.StringIO()):
            document=manage.sync_deployment_metadata()
        rows=document['devices']
        self.assertEqual(set(rows),{'tool-host-local','tool-host-remote',LINUX})
        self.assertEqual({row['containerId'] for row in rows.values()},{'a'*64,'b'*64})
        self.assertEqual({row['networkId'] for row in rows.values()},{'c'*64})
        self.assertEqual(rows['tool-host-local']['executionKind'],'TOOL_HOST_DIRECT')
        self.assertEqual(rows['tool-host-remote']['executionKind'],'TOOL_HOST_PULL')
        self.assertEqual(rows[LINUX]['ipAddress'],'172.18.0.3')
        self.assertNotIn('SECRET',json.dumps(document))
        self.assertEqual(json.loads(docker.call_args.args[2]),document)

    def test_verified_identity_is_not_reread_from_a_transient_status_file(self):
        containers=[self.container('quickstart','a'*64,'172.18.0.2'),self.container('linux-device','b'*64,'172.18.0.3'),self.container('registry','c'*64,'172.18.0.2')]
        replies=[SimpleNamespace(stdout='a'*64+'\n'+'b'*64+'\n'+'c'*64),SimpleNamespace(stdout=json.dumps(containers))]
        local=dict(deviceId=LINUX,state='ACTIVE',updatedAtUnixMs=NOW)
        with patch.object(manage.subprocess,'run',side_effect=replies), \
                patch.object(manage,'docker',side_effect=['{}','']), \
                patch.object(manage,'linux_status',side_effect=AssertionError('Do not reopen the status file')), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertIn(LINUX,manage.sync_deployment_metadata(local)['devices'])

    def test_missing_shared_network_stops_before_export(self):
        containers=[self.container('quickstart','a'*64,'172.18.0.2'),self.container('linux-device','b'*64,'172.18.0.3'),self.container('registry','c'*64,'172.18.0.2')]
        containers[1]['NetworkSettings']['Networks']={}
        with patch.object(manage.subprocess,'run',side_effect=[SimpleNamespace(stdout='a'*64+'\n'+'b'*64+'\n'+'c'*64),SimpleNamespace(stdout=json.dumps(containers))]), \
                patch.object(manage,'docker') as docker,self.assertRaisesRegex(ValueError,'shared deployment network'):
            manage.sync_deployment_metadata()
        docker.assert_not_called()


class PackageRecoveryTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root=patch.object(manage,'ROOT',Path(temporary.name))
        root.start();self.addCleanup(root.stop)

    def test_fresh_heartbeat_is_not_enough_until_expected_runtime_is_ready(self):
        release={'packageId':'examples.hotfolder','version':'0.1.0'}
        package={**release,'state':'READY'}
        states=[dict(lastSeenUnixMs=99,report={'packages':[package]}),
                dict(lastSeenUnixMs=100,report={'packages':[]}),
                dict(lastSeenUnixMs=101,report={'packages':[package]})]
        config=SimpleNamespace(api=unittest.mock.Mock(side_effect=states))
        with patch.object(manage.time,'sleep'):
            manage.wait_package_executor(config,release,LINUX,100)
        self.assertEqual(config.api.call_count,3)

    def test_restart_finishes_before_the_heartbeat_cutoff_is_taken(self):
        now=[100.0]
        def restart(*args,**kwargs):
            now[0]=200.0
            return SimpleNamespace(returncode=0)
        config=object();release=dict(packageId='examples.hotfolder',version='0.1.0')
        local=dict(deviceId=LINUX,state='ACTIVE',updatedAtUnixMs=200001)
        with patch.object(manage.subprocess,'run',side_effect=restart), \
                patch.object(manage.time,'time',side_effect=lambda:now[0]), \
                patch.object(manage,'wait_device') as wait, \
                patch.object(manage,'wait_package_executor',return_value=local) as package:
            self.assertEqual(local,manage.recover_device(config,registration(LINUX,'linux'),release))
        wait.assert_called_once_with(config,LINUX,seen_after=200000,timeout=360)
        package.assert_called_once_with(config,release,LINUX,200000,local_device=True)

    def test_old_heartbeat_and_missing_or_stale_local_status_cannot_pass_recovery(self):
        release=dict(packageId='examples.hotfolder',version='0.1.0')
        report=dict(packages=[dict(**release,state='READY')])
        config=SimpleNamespace(api=unittest.mock.Mock(side_effect=[
            dict(lastSeenUnixMs=99,report=report),dict(lastSeenUnixMs=100,report=report),
            *[dict(lastSeenUnixMs=101,report=report)]*3]))
        missing=manage.DockerCommandError('linux-device','read-device-status','status-not-published',1,True)
        stale=dict(deviceId=LINUX,state='ACTIVE',updatedAtUnixMs=99)
        fresh=dict(stale,updatedAtUnixMs=101)
        with patch.object(manage,'linux_status',side_effect=[missing,stale,fresh]), \
                patch.object(manage.time,'sleep') as sleep:
            self.assertEqual(fresh,manage.wait_package_executor(config,release,LINUX,100,local_device=True))
        self.assertEqual(config.api.call_count,5)
        self.assertEqual(sleep.call_count,4)

    def test_recovery_timeout_is_not_extended_for_missing_local_status(self):
        release=dict(packageId='examples.hotfolder',version='0.1.0')
        config=SimpleNamespace(api=lambda path:dict(lastSeenUnixMs=101,report=dict(packages=[dict(**release,state='READY')])))
        missing=manage.DockerCommandError('linux-device','read-device-status','status-not-published',1,True)
        with patch.object(manage,'linux_status',side_effect=missing), \
                patch.object(manage.time,'monotonic',side_effect=[0,241]), \
                self.assertRaisesRegex(ValueError,'did not recover'):
            manage.wait_package_executor(config,release,LINUX,100,local_device=True)

    def test_changed_identity_or_malformed_status_fails_immediately(self):
        for status in (dict(deviceId=WINDOWS,state='ACTIVE',updatedAtUnixMs=NOW),[],None):
            with self.subTest(status=status),patch.object(manage,'linux_status',return_value=status), \
                    self.assertRaises(ValueError):
                manage.ready_linux_status(LINUX,100)

    def test_windows_recovery_does_not_restart_the_local_linux_device(self):
        config=object();release={}
        with patch.object(manage.subprocess,'run') as restart, \
                patch.object(manage,'wait_device'),patch.object(manage,'wait_package_executor') as package:
            manage.recover_device(config,registration(WINDOWS,'windows'),release)
        restart.assert_not_called()
        self.assertFalse(package.call_args.kwargs['local_device'])


class CommandDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        root=patch.object(manage,'ROOT',self.root)
        root.start();self.addCleanup(root.stop)

    def test_only_a_missing_status_read_is_retryable(self):
        reply=SimpleNamespace(returncode=1,stdout='',stderr='cat: /run/olo-toolgate/device-status.json: No such file or directory')
        with patch.object(manage.subprocess,'run',return_value=reply) as run:
            with self.assertRaises(manage.DockerCommandError) as status:
                manage.linux_status()
            self.assertTrue(status.exception.retryable)
            with self.assertRaises(manage.DockerCommandError) as mutation:
                manage.docker('linux-device',['python','-c','write configuration'])
            self.assertFalse(mutation.exception.retryable)
        self.assertEqual(run.call_count,2)

    def test_failure_artifact_and_message_exclude_private_command_data(self):
        secret='private-test-value'
        reply=SimpleNamespace(returncode=7,stdout=secret,stderr='Permission denied '+secret)
        with patch.object(manage.subprocess,'run',return_value=reply),self.assertRaises(manage.DockerCommandError) as error:
            manage.docker('quickstart',['python','-c',secret],secret)
        self.assertFalse(error.exception.retryable)
        self.assertNotIn(secret,str(error.exception))
        text=(self.root/'build/langchain/command-failures.jsonl').read_text()
        self.assertNotIn(secret,text)
        self.assertEqual(json.loads(text),dict(service='quickstart',operation='container-command',reason='permission-denied',exitCode=7))

    def test_command_timeout_is_sanitized_and_not_replayed(self):
        with patch.object(manage.subprocess,'run',side_effect=subprocess.TimeoutExpired('private-command',60)) as run, \
                self.assertRaises(manage.DockerCommandError) as error:
            manage.docker('linux-device',['cat','/run/olo-toolgate/device-status.json'])
        self.assertNotIn('private-command',str(error.exception))
        self.assertFalse(error.exception.retryable)
        self.assertEqual(run.call_count,1)

    def test_permission_failure_does_not_enter_the_readiness_retry_loop(self):
        reply=SimpleNamespace(returncode=1,stdout='',stderr='Permission denied')
        with patch.object(manage.subprocess,'run',return_value=reply) as run, \
                patch.object(manage.time,'sleep') as sleep, \
                self.assertRaisesRegex(manage.DockerCommandError,'permission-denied'):
            manage.wait_linux_status()
        run.assert_called_once()
        sleep.assert_not_called()


class DeploymentTrustTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.state = Path(temporary.name)
        self.directory = self.state / 'agents'
        self.directory.mkdir()
        self.descriptor = dict(deviceId=LINUX, platform='linux', gateway=manage.gateway_url(),
                               expiresAtUnixMs=NOW + 60000)
        self.save()
        (self.directory / 'any.token').write_text('test-only-agent-credential')
        # Inert DER bytes test PEM comparison only; the real Agent still validates X.509/TLS.
        self.ca = manage.ssl.DER_cert_to_PEM_cert(b'test-only-public-certificate')
        (self.directory / 'gateway-ca.crt').write_text(self.ca)
        state = patch.object(manage, 'STATE', self.state)
        state.start(); self.addCleanup(state.stop)
        clock = patch.object(manage.time, 'time', return_value=NOW / 1000)
        clock.start(); self.addCleanup(clock.stop)
        docker = patch.object(manage, 'docker', return_value=self.ca)
        self.docker = docker.start(); self.addCleanup(docker.stop)
        device = patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX))
        self.device = device.start(); self.addCleanup(device.stop)

    def save(self):
        (self.directory / 'any.json').write_text(json.dumps(self.descriptor))

    def test_current_deployment_accepts_equivalent_pem_line_endings(self):
        (self.directory / 'gateway-ca.crt').write_bytes(self.ca.replace('\n', '\r\n').encode())
        self.assertEqual(manage.verify_deployment('any'), self.descriptor)
        self.docker.assert_called_once_with('quickstart', ['cat', '/data/keys/device-ca.crt'])

    def test_changed_gateway_ca_stops_without_adopting_trust_or_reading_device(self):
        path = self.directory / 'gateway-ca.crt'
        before = path.read_bytes()
        self.docker.return_value = manage.ssl.DER_cert_to_PEM_cert(b'different-test-only-public-certificate')
        with self.assertRaisesRegex(ValueError, 'Gateway trust changed.*deploy.bat'):
            manage.verify_deployment('any')
        self.assertEqual(path.read_bytes(), before)
        self.device.assert_not_called()

    def test_changed_linux_device_requires_deployment(self):
        self.device.return_value = dict(deviceId=WINDOWS)
        with self.assertRaisesRegex(ValueError, 'Linux device changed.*deploy.bat'):
            manage.verify_deployment('any')

    def test_other_gateway_and_expired_credential_stop_before_docker(self):
        for field, value, message in [('gateway', 'https://localhost:18450', 'another Gateway'),
                                      ('expiresAtUnixMs', NOW, 'expired')]:
            with self.subTest(field=field):
                before = self.descriptor[field]
                self.descriptor[field] = value; self.save()
                with self.assertRaisesRegex(ValueError, message): manage.verify_deployment('any')
                self.descriptor[field] = before
        self.docker.assert_not_called()

    def test_missing_credential_or_ca_stops_before_docker(self):
        for name in ('any.token', 'gateway-ca.crt'):
            with self.subTest(name=name):
                path = self.directory / name; data = path.read_bytes(); path.unlink()
                with self.assertRaisesRegex(ValueError, 'incomplete'): manage.verify_deployment('any')
                path.write_bytes(data)
        self.docker.assert_not_called()

    def test_windows_deployment_does_not_depend_on_compose_device_identity(self):
        self.descriptor.update(platform='windows', deviceId=WINDOWS); self.save()
        manage.verify_deployment('any')
        self.device.assert_not_called()


class DiscoveryTests(unittest.TestCase):
    def verify(self, catalogs, platform='windows'):
        gateway = SimpleNamespace(rpc=lambda method: dict(tools=[dict(name=n) for n in catalogs.pop(0)]))
        mimic = SimpleNamespace(Agent=lambda *args: gateway)
        selection = dict(deviceId=WINDOWS, platform=platform)
        with patch.object(manage, 'load_mimic', return_value=mimic), \
                patch.object(manage.time, 'sleep'), \
                patch.object(manage.time, 'monotonic', side_effect=[0, 1, 31]):
            manage.verify_discovery(selection, Path('.'), 'windows')

    def test_waits_for_the_catalog_to_include_every_example_tool(self):
        self.verify([sorted(manage.TOOLS)[:1], sorted(manage.TOOLS)])

    def test_missing_tools_name_the_cause_and_windows_remedy(self):
        with self.assertRaisesRegex(ValueError, 'examples.hotfolder/write_text.*signed package rollout'):
            self.verify([sorted(manage.TOOLS - {'examples.hotfolder/write_text'})] * 2)

    def test_linux_remedy_rebuilds_the_device(self):
        with self.assertRaisesRegex(ValueError, 'signed package rollout'):
            self.verify([[]] * 2, platform='linux')


class ExecutionSelectionTests(unittest.TestCase):
    def test_binding_and_grants_use_only_the_selected_device_and_example_tools(self):
        stored = {}
        allocations = []
        config = SimpleNamespace(upsert=lambda collection, row: stored.__setitem__((collection, row['id']), row),
            memberships=lambda *args: allocations.append(args),
            api=lambda path: dict(scope=dict(resources=[dict(kind='FILE', locator='', match='ANY')],
                                            conditions=dict(requireOnline=True))))
        profiles = [dict(tool=dict(id=tool, packageDigest='installed-client',
                                  definition=dict(actions=[dict(name='invoke')])) )
                    for tool in sorted(manage.TOOLS)]
        binding, devices = manage.configure_execution(config, 'langchain-linux', 'agent', LINUX, profiles)
        self.assertEqual(stored['device-groups', devices]['deviceIds'], [LINUX])
        self.assertEqual(stored['bindings', binding]['deviceGroupId'], devices)
        self.assertEqual(stored['bindings', binding]['allowedPackageDigests'], ['installed-client'])
        self.assertFalse(any(collection == 'tool-groups' for collection, _ in stored))
        for purpose in ('capability', 'service'):
            grant = stored['grants', 'langchain-linux-'+purpose]
            self.assertEqual(grant['sourceId'], 'langchain-linux-agents')
            self.assertEqual(grant['scope']['deviceGroups'], dict(ids=[devices], all=False))
            self.assertEqual(grant['scope']['toolGroups'], dict(ids=[manage.TOOL_GROUP], all=False))
            self.assertEqual(grant['scope']['actions'], ['invoke'])
            self.assertEqual(grant['scope']['resources'], [dict(kind='CUSTOM',locator='tool://'+tool,match='EXACT') for tool in sorted(manage.TOOLS)])
        self.assertEqual(allocations, [('agents', 'agent', ['ReadAndWriteAgentGroup', 'langchain-linux-agents'])])


if __name__ == '__main__':
    unittest.main()
