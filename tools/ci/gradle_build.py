# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Bounded recovery of a false-negative pinned Quarkus BOM lookup during plugin configuration."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import tomllib
import urllib.request
import xml.etree.ElementTree as XML

ROOT=Path(__file__).resolve().parents[2]


def quarkus_version():
    version=tomllib.loads((ROOT/'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']['quarkus']
    if not re.fullmatch(r'[0-9][A-Za-z0-9_.-]{0,63}',version):raise ValueError('Invalid pinned Quarkus version')
    return version


def published_bom(version):
    url=f'https://repo.maven.apache.org/maven2/io/quarkus/quarkus-bom/{version}/quarkus-bom-{version}.pom'
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url,timeout=10) as response:
            raw=response.read(2*1024*1024+1)
            if response.status!=200 or len(raw)>2*1024*1024:return False
        root=XML.fromstring(raw)
        fields={child.tag.rsplit('}',1)[-1]:child.text for child in root}
        parent=next((child for child in root if child.tag.rsplit('}',1)[-1]=='parent'),None)
        inherited={} if parent is None else {child.tag.rsplit('}',1)[-1]:child.text for child in parent}
        return (fields.get('artifactId')=='quarkus-bom' and (fields.get('groupId') or inherited.get('groupId'))=='io.quarkus'
            and (fields.get('version') or inherited.get('version'))==version)
    except (OSError,ValueError,XML.ParseError):return False


def build(command,output,*,cwd=None,sleep=time.sleep,published=published_bom):
    output.mkdir(parents=True,exist_ok=True)
    version=quarkus_version();current=list(command)
    for attempt in range(3):
        path=output/f'gradle-{attempt+1}.log'
        with path.open('wb') as log:
            result=subprocess.run(current,cwd=cwd,stdout=log,stderr=subprocess.STDOUT)
        has_task=False
        with path.open('r',encoding='utf-8',errors='replace') as log:
            for line in log:
                if re.match(r'\s*> Task ',line):has_task=True
        with path.open('rb') as log:
            for chunk in iter(lambda:log.read(65536),b''):sys.stdout.write(chunk.decode('utf-8',errors='replace'))
            log.seek(max(0,log.tell()-65536));tail=log.read().decode('utf-8',errors='replace')
        if result.returncode==0:return
        missing=(f'Could not find io.quarkus:quarkus-bom:{version}.' in tail
            and 'A problem occurred configuring project' in tail
            and 'Could not resolve all artifacts for configuration' in tail
            and not has_task
            and 'There were failing tests' not in tail
            and not re.search(r'(?m)^\s*(?:Compilation failed|error:)',tail))
        if attempt==2 or not missing or not published(version):raise subprocess.CalledProcessError(result.returncode,current)
        delay=(5,15)[attempt]
        print(f'Pinned Quarkus BOM is published; refreshing the failed dependency lookup after {delay} seconds.',flush=True)
        sleep(delay)
        if '--refresh-dependencies' not in current:current.insert(1,'--refresh-dependencies')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log-directory',type=Path,required=True)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args();command=args.command[1:] if args.command[:1]==['--'] else args.command
    if not command or Path(command[0]).name not in ('gradlew','gradlew.bat','gradle'):parser.error('Supply a Gradle build command after --')
    build(command,args.log_directory,cwd=Path.cwd())


if __name__=='__main__':main()
