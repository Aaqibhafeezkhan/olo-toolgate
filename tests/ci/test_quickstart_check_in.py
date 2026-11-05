# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""The image gate waits for a current first check-in, never an obsolete journal."""
import unittest
from unittest.mock import Mock, patch
from tools.quickstart import check


class FirstCheckInTests(unittest.TestCase):
    def test_enrolled_host_must_finish_its_first_check_in(self):
        final={'formatVersion':3,'sequence':1,'identity':{'tenantId':'fixture'}}
        read=Mock(side_effect=[{'formatVersion':3,'sequence':0},final])
        with patch.object(check.time,'monotonic',return_value=0),patch.object(check.time,'sleep') as sleep:
            self.assertIs(final,check.wait_checked_in_journal(read,1))
        sleep.assert_called_once_with(.5)
        self.assertEqual(2,read.call_count)

    def test_missing_check_in_fails_within_the_existing_deadline(self):
        read=Mock(return_value={'formatVersion':3,'sequence':0})
        with patch.object(check.time,'monotonic',side_effect=[0,1]),patch.object(check.time,'sleep') as sleep:
            with self.assertRaisesRegex(AssertionError,'first check-in'):
                check.wait_checked_in_journal(read,1)
        self.assertEqual(2,read.call_count)
        sleep.assert_called_once_with(.5)

    def test_obsolete_format_is_refused_without_waiting(self):
        with patch.object(check.time,'sleep') as sleep:
            with self.assertRaisesRegex(AssertionError,'Current Tool Host'):
                check.wait_checked_in_journal(lambda:{'formatVersion':2,'sequence':1},1)
        sleep.assert_not_called()

    def test_malformed_sequences_are_not_treated_as_pending_or_ready(self):
        for sequence in (-1,'1',True,None):
            with self.subTest(sequence=sequence),patch.object(check.time,'sleep') as sleep:
                with self.assertRaisesRegex(AssertionError,'Invalid Tool Host'):
                    check.wait_checked_in_journal(lambda:{'formatVersion':3,'sequence':sequence},1)
                sleep.assert_not_called()
