# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Retain npm findings and narrowly apply reviewed, expiring advisory exceptions."""
from datetime import date
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]
POLICY=Path(__file__).with_name('npm-audit-exceptions.json')
SEVERITIES={'info','low','moderate','high','critical'}


def exceptions(value):
    if not isinstance(value,dict) or set(value)-{'$comment','formatVersion','exceptions'} or value.get('formatVersion')!=1 or not isinstance(value.get('exceptions'),list):raise ValueError('Invalid npm exception policy')
    result={}
    fields={'advisory','cve','package','versions','affectedRange','severity','created','expires','reason'}
    for entry in value['exceptions']:
        if set(entry)!=fields or not re.fullmatch(r'GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}',entry['advisory']):raise ValueError('Invalid advisory exception')
        if not re.fullmatch(r'CVE-\d{4}-\d{4,}',entry['cve']):raise ValueError('Invalid advisory CVE')
        if not isinstance(entry['versions'],list) or not entry['versions'] or any(not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?',version) for version in entry['versions']):raise ValueError('Only exact exception versions are allowed')
        start,end=date.fromisoformat(entry['created']),date.fromisoformat(entry['expires'])
        if not 1<=(end-start).days<=30 or len(entry['reason'].strip())<40 or entry['severity'] not in SEVERITIES:raise ValueError('Invalid exception review window')
        url='https://github.com/advisories/'+entry['advisory']
        if url in result:raise ValueError('Duplicate advisory exception')
        result[url]=entry
    return result


def evaluate(report,lock,policy,*,today=None):
    today=today or date.today()
    allowed=exceptions(policy)
    if not isinstance(report,dict) or report.get('auditReportVersion')!=2 or report.get('error') or not isinstance(report.get('vulnerabilities'),dict):raise ValueError('Unverifiable npm audit response')
    vulnerabilities=report['vulnerabilities']
    accepted,blocking=[],[]

    def permitted(leaf,root):
        entry=allowed.get(leaf.get('url'))
        if not entry or leaf.get('name')!=entry['package'] or leaf.get('dependency')!=entry['package'] or leaf.get('severity')!=entry['severity'] or leaf.get('range')!=entry['affectedRange']:return False
        if not date.fromisoformat(entry['created'])<=today<=date.fromisoformat(entry['expires']):return False
        # npm sometimes proposes downgrading an unrelated parent. That isn't a Picomatch fix.
        fix=root.get('fixAvailable')
        if fix is True or isinstance(fix,dict) and fix.get('name')==entry['package']:return False
        nodes=root.get('nodes')
        packages=lock.get('packages',{})
        return bool(nodes) and all(packages.get(node,{}).get('version') in entry['versions'] for node in nodes)

    def waived(name):
        # npm's peer-dependency findings can form cycles. Inspect every reachable
        # leaf once, requiring at least one reviewed advisory rather than trusting
        # the cycle itself as evidence for an exception.
        pending,seen,leaves=[name],set(),0
        while pending:
            current=pending.pop()
            if current in seen:continue
            seen.add(current)
            entry=vulnerabilities.get(current)
            if not isinstance(entry,dict):return False
            via=entry.get('via')
            if not isinstance(via,list) or not via:return False
            for item in via:
                if isinstance(item,str):pending.append(item)
                elif isinstance(item,dict) and permitted(item,entry):leaves+=1
                else:return False
        return leaves>0

    for name,entry in vulnerabilities.items():
        if not isinstance(entry,dict) or entry.get('severity') not in SEVERITIES:raise ValueError('Invalid vulnerability severity')
        if entry['severity'] not in {'high','critical'}:continue
        (accepted if waived(name) else blocking).append(name)
    return {'threshold':'high','temporarilyAccepted':sorted(accepted),'blocking':sorted(blocking)}


def main():
    output=ROOT/'build/source-scan';output.mkdir(parents=True,exist_ok=True)
    executable=shutil.which('npm.cmd' if os.name=='nt' else 'npm')
    if not executable:raise SystemExit('npm audit unavailable; scan remains blocking')
    response=subprocess.run([executable,'audit','--json'],cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
    try:
        if response.returncode not in (0,1) or len(response.stdout)>2097152:raise ValueError()
        report=json.loads(response.stdout)
        decision=evaluate(report,json.loads((ROOT/'package-lock.json').read_text(encoding='utf-8')),json.loads(POLICY.read_text(encoding='utf-8')))
    except (ValueError,TypeError,KeyError,OSError):raise SystemExit('npm audit or exception policy could not be verified; scan remains blocking') from None
    (output/'npm-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    (output/'npm-audit-decision.json').write_text(json.dumps(decision,indent=2)+'\n',encoding='utf-8')
    if decision['temporarilyAccepted']:
        policy=json.loads(POLICY.read_text(encoding='utf-8'))
        for entry in policy['exceptions']:
            if entry['package'] in decision['temporarilyAccepted']:
                print('TEMPORARY EXCEPTION '+entry['advisory']+' for '+entry['package']+'; expires '+entry['expires']+'. Raw audit findings remain in build/source-scan/npm-audit.json.')
    if decision['blocking']:raise SystemExit('Blocking npm high/critical vulnerabilities: '+', '.join(decision['blocking']))
    print('npm high/critical gate passed; raw findings retained in build/source-scan/npm-audit.json')


if __name__=='__main__':main()
