# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Group selection must retain enrollment, availability and platform boundaries."""
import contextlib
from copy import deepcopy
import io
import json
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
    return dict(deviceId=device, platform=platform, gateway=manage.gateway_url(),
                profiles=[dict(tool=dict(id=name, enabled=True), extractor=dict(enabled=True))
                          for name in sorted(manage.TOOLS)])


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

        def profiles(*args):
            self.assertEqual(events, ['approved', 'checked-in'])
            return json.dumps(self.records[0]['profiles'])

        with tempfile.TemporaryDirectory() as temporary, patch.object(manage, 'STATE', Path(temporary)), \
                patch.object(self.config, 'api', side_effect=api), \
                patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='PENDING', userCode='test-code', keyFingerprint='test-fingerprint')), \
                patch.object(manage, 'docker', side_effect=profiles), \
                patch.object(manage.time, 'sleep', side_effect=check_in):
            records = manage.register_devices(self.config, SimpleNamespace(approve_linux_device=True, profiles=None, device_id=None, target='linux'))
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
                manage.register_devices(self.config, SimpleNamespace(approve_linux_device=True, profiles=None, device_id=None, target='linux'))
        docker.assert_not_called()
        self.assertEqual(self.config.allocations, [])

    def test_register_both_devices_preserving_existing_groups(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            (state / 'devices').mkdir()
            (state / 'devices' / (WINDOWS + '.json')).write_text(json.dumps(self.records[1]))
            args = SimpleNamespace(profiles=None, device_id=None, target='any', approve_linux_device=True)

            def private_file(path, value):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value)

            with patch.object(manage, 'STATE', state), \
                    patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='ACTIVE')), \
                    patch.object(manage, 'docker', return_value=json.dumps(self.records[0]['profiles'])), \
                    patch.object(manage.shared, 'private_file', side_effect=private_file):
                records = manage.register_devices(self.config, args)
            self.assertEqual({record['deviceId'] for record in records}, {LINUX, WINDOWS})
            self.assertEqual(self.config.allocations, [
                ('devices', device, sorted(['existing-team-device-group', manage.DEVICE_GROUP]))
                for device in (LINUX, WINDOWS)])

    def test_foreign_gateway_registration_is_not_allocated(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            (state / 'devices').mkdir()
            foreign = deepcopy(self.records[1])
            foreign['gateway'] = 'https://localhost:18450'
            (state / 'devices' / (WINDOWS + '.json')).write_text(json.dumps(foreign))
            args = SimpleNamespace(profiles=None, device_id=None, target='any', approve_linux_device=False)
            with patch.object(manage, 'STATE', state), \
                    patch.object(manage, 'linux_status', return_value=dict(deviceId=LINUX, state='ACTIVE')), \
                    patch.object(manage, 'docker', return_value=json.dumps(self.records[0]['profiles'])), \
                    patch.object(manage.shared, 'private_file', side_effect=lambda path, value: path.write_text(value)):
                records = manage.register_devices(self.config, args)
            self.assertEqual([record['deviceId'] for record in records], [LINUX])
            self.assertEqual([row[1] for row in self.config.allocations], [LINUX])


def extractor(version='0.10.0-dev', revision=1):
    return dict(id='extract-hotfolder', enabled=True, revision=revision, version=version, fields=[])


class ExtractorConflictTests(unittest.TestCase):
    def selection(self, version='0.10.0-dev'):
        return dict(deviceId=WINDOWS, platform='windows',
                    profiles=[dict(tool=dict(id='hotfolder.list'), extractor=extractor(version))])

    def test_missing_or_identical_extractor_is_accepted(self):
        for stored in ([], [extractor()]):
            with self.subTest(stored=stored):
                manage.check_extractors(SimpleNamespace(all=lambda collection: stored), self.selection())

    def test_different_client_version_stops_before_replacing_shared_extractor(self):
        config = SimpleNamespace(all=lambda collection: [extractor('0.9.0')])
        with self.assertRaisesRegex(ValueError, 'version 0.10.0-dev, but this Gateway has version 0.9.0'):
            manage.check_extractors(config, self.selection())

    def test_edited_extractor_can_never_match_and_requires_reset(self):
        config = SimpleNamespace(all=lambda collection: [extractor(revision=2)])
        with self.assertRaisesRegex(ValueError, 'revision 2.*docker compose down -v'):
            manage.check_extractors(config, self.selection())


class DeploymentMetadataTests(unittest.TestCase):
    def container(self,service,identity,ip):
        return dict(Id=identity,Name='/toolgate-langchain-'+service+'-1',
                    Config=dict(Hostname=service,Labels={'com.docker.compose.service':service},Env=['SECRET=must-not-export']),
                    NetworkSettings=dict(Networks={'toolgate-langchain_default':dict(NetworkID='c'*64,IPAddress=ip,GlobalIPv6Address='')}))

    def test_four_execution_identities_share_two_containers_and_one_actual_network(self):
        containers=[self.container('quickstart','a'*64,'172.18.0.2'),self.container('linux-device','b'*64,'172.18.0.3')]
        identities=dict(QUICKSTART_CLIENT='native-device',TOOL_HOST_DIRECT='tool-host-local',TOOL_HOST_PULL='tool-host-remote')
        replies=[SimpleNamespace(stdout='a'*64+'\n'+'b'*64+'\n'),SimpleNamespace(stdout=json.dumps(containers))]
        with patch.object(manage.subprocess,'run',side_effect=replies), \
                patch.object(manage,'docker',side_effect=[json.dumps(identities),'']) as docker, \
                patch.object(manage,'linux_status',return_value=dict(deviceId=LINUX)),contextlib.redirect_stdout(io.StringIO()):
            document=manage.sync_deployment_metadata()
        rows=document['devices']
        self.assertEqual(set(rows),{'native-device','tool-host-local','tool-host-remote',LINUX})
        self.assertEqual({row['containerId'] for row in rows.values()},{'a'*64,'b'*64})
        self.assertEqual({row['networkId'] for row in rows.values()},{'c'*64})
        self.assertEqual(rows['tool-host-local']['executionKind'],'TOOL_HOST_DIRECT')
        self.assertEqual(rows['tool-host-remote']['executionKind'],'TOOL_HOST_PULL')
        self.assertEqual(rows[LINUX]['ipAddress'],'172.18.0.3')
        self.assertNotIn('SECRET',json.dumps(document))
        self.assertEqual(json.loads(docker.call_args.args[2]),document)

    def test_missing_shared_network_stops_before_export(self):
        containers=[self.container('quickstart','a'*64,'172.18.0.2'),self.container('linux-device','b'*64,'172.18.0.3')]
        containers[1]['NetworkSettings']['Networks']={}
        with patch.object(manage.subprocess,'run',side_effect=[SimpleNamespace(stdout='a'*64+'\n'+'b'*64),SimpleNamespace(stdout=json.dumps(containers))]), \
                patch.object(manage,'docker') as docker,self.assertRaisesRegex(ValueError,'shared deployment network'):
            manage.sync_deployment_metadata()
        docker.assert_not_called()


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
        with self.assertRaisesRegex(ValueError, 'client.read_log_entry.*prepare-windows.ps1'):
            self.verify([sorted(manage.TOOLS - {'client.read_log_entry'})] * 2)

    def test_linux_remedy_rebuilds_the_device(self):
        with self.assertRaisesRegex(ValueError, 'without -SkipBuild'):
            self.verify([[]] * 2, platform='linux')


class LocalRegistrationTests(unittest.TestCase):
    def refresh(self, records, installed, host='HOST-A'):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, record in records.items():
                (directory / name).write_text(json.dumps(record))
            exe = directory / 'client.exe'
            exe.write_text('')
            result = SimpleNamespace(returncode=0, stdout=json.dumps(installed))
            with patch.object(manage.os, 'name', 'nt'), patch.object(manage, 'LOCAL_CLIENT', exe), \
                    patch.dict(manage.os.environ, {'COMPUTERNAME': host}), \
                    patch.object(manage.subprocess, 'run', return_value=result), \
                    patch.object(manage.shared, 'private_file', side_effect=lambda path, value: path.write_text(value)), \
                    contextlib.redirect_stdout(io.StringIO()):
                manage.refresh_local_registration(directory)
            return {name: json.loads((directory / name).read_text()) for name in records}

    def profiles(self, digest):
        return [dict(tool=dict(id=name, enabled=True, packageDigest=digest), extractor=dict(enabled=True))
                for name in sorted(manage.TOOLS)]

    def record(self, device, digest, **extra):
        return dict(deviceId=device, platform='windows', gateway=manage.gateway_url(),
                    profiles=self.profiles(digest), **extra)

    def test_this_machines_registration_follows_the_installed_client(self):
        refreshed = self.refresh({'device-a.json': self.record(WINDOWS, 'old', host='host-a')}, self.profiles('new'))
        self.assertEqual(refreshed['device-a.json']['profiles'], self.profiles('new'))
        self.assertEqual(refreshed['device-a.json']['host'], 'host-a')

    def test_a_single_registration_without_host_is_treated_as_this_machine(self):
        refreshed = self.refresh({'device-a.json': self.record(WINDOWS, 'old')}, self.profiles('new'))
        self.assertEqual(refreshed['device-a.json']['profiles'], self.profiles('new'))

    def test_other_machines_registrations_are_left_alone(self):
        records = {'device-a.json': self.record(WINDOWS, 'old', host='host-b'),
                   'device-b.json': self.record('device-' + 'c' * 32, 'old')}
        refreshed = self.refresh(records, self.profiles('new'))
        self.assertEqual(refreshed, records)


class ExecutionSelectionTests(unittest.TestCase):
    def test_binding_and_grants_use_only_the_selected_device_and_example_tools(self):
        stored = {}
        allocations = []
        config = SimpleNamespace(upsert=lambda collection, row: stored.__setitem__((collection, row['id']), row),
            memberships=lambda *args: allocations.append(args),
            api=lambda path: dict(scope=dict(resources=[dict(kind='FILE', locator='', match='ANY')],
                                            conditions=dict(requireOnline=True))))
        profiles = [dict(tool=dict(id=tool, packageDigest='installed-client',
                                  definition=dict(actions=[dict(name='read' if 'read' in tool else 'write')])) )
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
            self.assertEqual(grant['scope']['toolGroups'], dict(ids=['ReadAndWriteToolGroup'], all=False))
        self.assertEqual(allocations, [('agents', 'agent', ['ReadAndWriteAgentGroup', 'langchain-linux-agents'])])


if __name__ == '__main__':
    unittest.main()
