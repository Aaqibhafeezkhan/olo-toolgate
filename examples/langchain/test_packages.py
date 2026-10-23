# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Package publication and rollout gates must fail before unauthenticated or unready effects."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

import packages


class PackageFlowTests(unittest.TestCase):
    def release(self):
        return dict(packageId=packages.PACKAGE_ID, version='0.1.0', signed=True,
                    document=dict(tools=[dict(toolId=t) for t in packages.TOOLS]))

    def test_uploads_exact_package_and_requests_its_control_signed_release(self):
        with tempfile.TemporaryDirectory() as folder:
            package=Path(folder)/'hotfolder.tgpkg';package.write_bytes(b'sealed-package')
            config=Mock()
            with patch.object(packages, 'release_request', side_effect=[{'packageDigest':'sha256:fixture'}, self.release()]) as request:
                packages.publish(config, package)
            self.assertEqual(request.call_args_list[0].args,
                (config,'packages/uploads',b'sealed-package','application/vnd.ololabs.toolgate.package+zip'))
            self.assertEqual(request.call_args_list[1].args,
                (config,'packages/client-releases',{'packageDigest':'sha256:fixture'}))

    def test_unsigned_or_incomplete_release_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            package=Path(folder)/'hotfolder.tgpkg';package.write_bytes(b'fixture')
            for release in (dict(self.release(),signed=False),dict(self.release(),document={'tools':[]})):
                with self.subTest(release=release), patch.object(packages,'release_request',side_effect=[{'packageDigest':'digest'},release]):
                    with self.assertRaisesRegex(ValueError,'must sign'):
                        packages.publish(Mock(),package)

    def test_waits_for_selected_device_ready_and_stops_on_activation_failure(self):
        row=lambda ready,failed:dict(rollout={'id':'selected'},ready=ready,failed=failed)
        for final,success in ((row(1,0),True),(row(0,1),False)):
            config=Mock();config.api.side_effect=[{'id':'selected'},{'items':[row(0,0)]},{'items':[final]}]
            with patch.object(packages.time,'sleep'):
                if success:
                    packages.assign(config,self.release(),'device-selected')
                else:
                    with self.assertRaisesRegex(ValueError,'activation failed'):
                        packages.assign(config,self.release(),'device-selected')
            request=config.api.call_args_list[0].args[1]
            self.assertEqual(request['deviceIds'],['device-selected'])
            self.assertEqual(request['packageId'],packages.PACKAGE_ID)

    def test_api_failure_does_not_echo_credential_or_response_body(self):
        from urllib.error import HTTPError
        config=SimpleNamespace(origin='http://127.0.0.1:18091',tokens={'admin':'test-only-token'})
        error=HTTPError(config.origin,403,'private upstream body',{},None)
        with patch.object(packages.urllib.request,'urlopen',side_effect=error):
            with self.assertRaisesRegex(ValueError,'HTTP 403') as failure:
                packages.release_request(config,'packages/client-releases',{})
        self.assertNotIn('test-only-token',str(failure.exception))
        self.assertNotIn('private upstream',str(failure.exception))


if __name__ == '__main__':
    unittest.main()
