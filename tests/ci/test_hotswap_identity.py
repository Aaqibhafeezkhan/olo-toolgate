# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Identity comparisons exclude observation counters, not credential authority."""
from copy import deepcopy
import unittest
from unittest.mock import patch
from tools.workflows import hotswap_demo as demo


class IdentitySnapshotTests(unittest.TestCase):
    def test_audit_activity_is_not_a_credential_change_but_session_epoch_is(self):
        user={'id':'admin','enabled':True,'revision':1}
        binding={'id':'installed-admin','userId':'admin','subject':'admin','issuer':'installation',
                 'enabled':True,'revision':1,'attemptCount':1,'lastAttemptUnixMs':1,'sessionEpoch':1}
        def answer(client,path):return {'items':deepcopy([binding] if 'identity-bindings' in path else [user])}
        with patch.object(demo,'call',side_effect=answer):
            before=demo.snapshot_identity(None)
            binding.update(revision=40,attemptCount=40,lastAttemptUnixMs=50)
            self.assertEqual(before,demo.snapshot_identity(None))
            binding['sessionEpoch']=2
            self.assertNotEqual(before,demo.snapshot_identity(None))


if __name__=='__main__':unittest.main()
