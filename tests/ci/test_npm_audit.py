# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Only the reviewed, version-pinned advisory and its dependent findings may be temporarily accepted."""
from copy import deepcopy
from datetime import date
import json
from pathlib import Path
import unittest

from tools.security.npm_audit import evaluate,exceptions

ROOT=Path(__file__).resolve().parents[2]
TODAY=date(2026,11,17)


class NpmAuditTests(unittest.TestCase):
    def setUp(self):
        self.policy=json.loads((ROOT/'tools/security/npm-audit-exceptions.json').read_text(encoding='utf-8'))
        self.lock={'packages':{'node_modules/picomatch':{'version':'4.0.7'}}}
        leaf={'name':'picomatch','dependency':'picomatch','url':'https://github.com/advisories/GHSA-fc5q-39xc-vjpf','range':'<=4.0.7','severity':'high'}
        self.report={'auditReportVersion':2,'vulnerabilities':{
            'picomatch':{'severity':'high','via':[leaf],'nodes':['node_modules/picomatch'],'fixAvailable':{'name':'vitest','version':'3.1.4'}},
            'vite':{'severity':'high','via':['picomatch']},
            'vitest':{'severity':'high','via':['vite']}}}

    def test_exact_advisory_and_derived_findings_are_visible_without_mutating_the_report(self):
        original=deepcopy(self.report)
        result=evaluate(self.report,self.lock,self.policy,today=TODAY)
        self.assertEqual([],result['blocking'])
        self.assertEqual(['picomatch','vite','vitest'],result['temporarilyAccepted'])
        self.assertEqual(original,self.report)

    def test_unrelated_or_mixed_high_findings_remain_blocking(self):
        leaf=deepcopy(self.report['vulnerabilities']['picomatch']['via'][0]);leaf['url']='https://github.com/advisories/GHSA-abcd-abcd-abcd'
        self.report['vulnerabilities']['picomatch']['via'].append(leaf)
        result=evaluate(self.report,self.lock,self.policy,today=TODAY)
        self.assertEqual(['picomatch','vite','vitest'],result['blocking'])
        self.assertEqual([],result['temporarilyAccepted'])

    def test_expired_or_unreviewed_versions_ranges_and_severity_are_not_waived(self):
        self.assertTrue(evaluate(self.report,self.lock,self.policy,today=date(2026,12,18))['blocking'])
        for change in ({'version':'4.0.6'},{'version':'4.0.8'}):
            lock=deepcopy(self.lock);lock['packages']['node_modules/picomatch'].update(change)
            self.assertTrue(evaluate(self.report,lock,self.policy,today=TODAY)['blocking'])
        for change in ({'range':'*'},{'severity':'critical'},{'dependency':'another-package'}):
            report=deepcopy(self.report);report['vulnerabilities']['picomatch']['via'][0].update(change)
            self.assertTrue(evaluate(report,self.lock,self.policy,today=TODAY)['blocking'])

    def test_an_available_picomatch_fix_stops_the_temporary_exception(self):
        for fix in (True,{'name':'picomatch','version':'4.0.8'}):
            report=deepcopy(self.report);report['vulnerabilities']['picomatch']['fixAvailable']=fix
            self.assertTrue(evaluate(report,self.lock,self.policy,today=TODAY)['blocking'])

    def test_missing_and_cyclic_dependency_paths_never_suppress_findings(self):
        for via in (['missing'],['vitest'],[]):
            report=deepcopy(self.report);report['vulnerabilities']['vite']['via']=via
            self.assertIn('vite',evaluate(report,self.lock,self.policy,today=TODAY)['blocking'])

    def test_peer_cycles_require_all_reachable_advisories_to_be_reviewed(self):
        self.report['vulnerabilities']['coverage']={'severity':'high','via':['vitest']}
        self.report['vulnerabilities']['vitest']['via'].append('coverage')
        self.assertEqual([],evaluate(self.report,self.lock,self.policy,today=TODAY)['blocking'])
        other=deepcopy(self.report['vulnerabilities']['picomatch']['via'][0]);other['url']='https://github.com/advisories/GHSA-abcd-abcd-abcd'
        self.report['vulnerabilities']['coverage']['via'].append(other)
        self.assertEqual(['coverage','vitest'],evaluate(self.report,self.lock,self.policy,today=TODAY)['blocking'])

    def test_malformed_reports_and_broad_or_indefinite_exceptions_fail(self):
        for report in ({'auditReportVersion':1,'vulnerabilities':{}},{'auditReportVersion':2,'error':{'message':'offline'}},[]):
            with self.assertRaises(ValueError):evaluate(report,self.lock,self.policy,today=TODAY)
        for change in ({'versions':['*']},{'expires':'2028-01-01'},{'reason':'skip'},{'unknown':True}):
            policy=deepcopy(self.policy);policy['exceptions'][0].update(change)
            with self.assertRaises(ValueError):exceptions(policy)

    def test_low_and_moderate_findings_retain_the_existing_high_threshold(self):
        self.report['vulnerabilities']={'other':{'severity':'moderate','via':[]}}
        self.assertEqual({'threshold':'high','temporarilyAccepted':[],'blocking':[]},evaluate(self.report,self.lock,self.policy,today=TODAY))
