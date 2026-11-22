# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Bounded Maven Central resolution recovery; never retry compiler or test failures."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as XML

ROOT=Path(__file__).resolve().parents[2]


COORDINATE = re.compile(r'([A-Za-z0-9_][A-Za-z0-9_.-]{0,127}):([A-Za-z0-9_][A-Za-z0-9_.-]{0,127}):([A-Za-z0-9_][A-Za-z0-9_.+-]{0,127})')
CENTRAL = 'https://repo.maven.apache.org/maven2/'


def pom_url(coordinate):
    group,artifact,version=coordinate
    return CENTRAL+f'{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}.pom'


def published_dependency(coordinate):
    if not COORDINATE.fullmatch(':'.join(coordinate)) or any('..' in part for part in coordinate):return False
    group,artifact,version=coordinate
    url=pom_url(coordinate)
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url,timeout=10) as response:
            raw=response.read(2*1024*1024+1)
            if response.status!=200 or len(raw)>2*1024*1024:return False
        root=XML.fromstring(raw)
        fields={child.tag.rsplit('}',1)[-1]:child.text for child in root}
        parent=next((child for child in root if child.tag.rsplit('}',1)[-1]=='parent'),None)
        inherited={} if parent is None else {child.tag.rsplit('}',1)[-1]:child.text for child in parent}
        return (root.tag.rsplit('}',1)[-1]=='project' and fields.get('artifactId')==artifact and (fields.get('groupId') or inherited.get('groupId'))==group
            and (fields.get('version') or inherited.get('version'))==version)
    except (OSError,ValueError,XML.ParseError):return False


def retry_reason(text,published=published_dependency):
    if not re.search(r'Could not resolve all (?:files|artifacts) for configuration',text):return None
    # These tasks resolve their Java classpath before running the compiler or
    # SDK examples. A failed test task or another failed action is never retried.
    allowed={'compileJava','compileTestJava','toolgateCheck','toolgatePackage'}
    if any(task.rsplit(':',1)[-1] not in allowed
           for task in re.findall(r'(?m)^\s*> Task (\S+) FAILED\b',text)):return None
    if 'There were failing tests' in text or re.search(r'(?mi)^\s*(?:Compilation failed|.*\berror:)',text):return None
    missing=set()
    for line in text.splitlines():
        if 'Could not find ' not in line:continue
        value=line.split('Could not find ',1)[1].strip()
        match=COORDINATE.fullmatch(value[:-1]) if value.endswith('.') else None
        if not match:return None
        missing.add(match.groups())
    if missing:
        if len(missing)>16 or not all(pom_url(item) in text and published(item) for item in sorted(missing)):return None
        return 'Missing Maven metadata is independently published'
    if 'Received status code 429 from server' in text and CENTRAL in text:
        return 'Maven Central request was throttled'
    return None


def build(command,output,*,cwd=None,env=None,sleep=time.sleep,published=published_dependency):
    output.mkdir(parents=True,exist_ok=True)
    current=list(command)
    for attempt in range(3):
        path=output/f'gradle-{attempt+1}.log'
        with path.open('wb') as log:
            result=subprocess.run(current,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT)
        with path.open('rb') as log:
            for chunk in iter(lambda:log.read(65536),b''):sys.stdout.write(chunk.decode('utf-8',errors='replace'))
        if result.returncode==0:return
        reason=None if attempt==2 else retry_reason(path.read_text(encoding='utf-8',errors='replace'),published)
        if reason is None:raise subprocess.CalledProcessError(result.returncode,current)
        delay=(5,15)[attempt]
        print(f'{reason}; refreshing dependency lookup after {delay} seconds.',flush=True)
        sleep(delay)
        if '--refresh-dependencies' not in current:current.append('--refresh-dependencies')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log-directory',type=Path,required=True)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args();command=args.command[1:] if args.command[:1]==['--'] else args.command
    if not command or Path(command[0]).name not in ('gradlew','gradlew.bat','gradle'):parser.error('Supply a Gradle build command after --')
    build(command,args.log_directory,cwd=Path.cwd())


if __name__=='__main__':main()
