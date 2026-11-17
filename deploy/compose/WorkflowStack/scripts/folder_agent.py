# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Trusted folder agent delivery adapter; models never choose host paths or authorize writes."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import stat
import time
import urllib.request
import urllib.error

import configuration

MAX_INPUT = 16384


def read_regular(path, limit):
    descriptor = os.open(path, os.O_RDONLY | getattr(os,'O_NOFOLLOW',0))
    with os.fdopen(descriptor,'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise ValueError('UNSUPPORTED_FILE')
        data = stream.read(limit+1)
        if len(data)>limit or info.st_mtime_ns != os.fstat(stream.fileno()).st_mtime_ns:
            raise ValueError('UNSTABLE_FILE')
        return data


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_once(path, data, owner=None):
    """Recover the same projection; collisions are never overwritten."""
    temporary=path.with_name('.delivery-'+secrets.token_hex(12))
    try:
        descriptor=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
        with os.fdopen(descriptor,'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if owner is not None:
            os.chown(temporary,*owner)
        try:
            os.link(temporary,path,follow_symlinks=False)
        except FileExistsError:
            if digest(read_regular(path,65536)) != digest(data):
                raise ValueError('OUTPUT_COLLISION')
    finally:
        temporary.unlink(missing_ok=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None


class ControlResults:
    def __init__(self, origin, custody):
        if not origin.startswith('https://localhost:') or not origin.removeprefix('https://localhost:').isdigit():
            raise ValueError('Use the local direct TLS Control origin')
        self.origin,self.custody=origin,custody
        self.binding=json.loads(read_regular(custody/'delivery.json',8192))
        if set(self.binding)!={'profileId','profileDigest','workflowIds'} or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}',self.binding['profileId']):
            raise ValueError('UNSUPPORTED_DELIVERY_BINDING')
        context=ssl.create_default_context(cafile=str(custody/'gateway-ca.crt'))
        context.verify_flags &= ~getattr(ssl,'VERIFY_X509_STRICT',0)
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=context))

    def get(self, run):
        if not re.fullmatch(r'run-[a-f0-9-]{36}',run):
            raise ValueError('INVALID_RUN')
        token=read_regular(self.custody/'result.token',8192).decode('ascii').strip()
        request=urllib.request.Request(self.origin+'/api/control/v1/workflow-runs/'+run,
            headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
        with self.opener.open(request,timeout=10) as response:
            raw=response.read(49153)
            if len(raw)>49152:
                raise ValueError('INVALID_RESULT')
            value=json.loads(raw)
            if value.get('workflowId') not in self.binding['workflowIds'] or not re.fullmatch(r'sha256:[a-f0-9]{64}',value.get('profileDigest','')):
                raise ValueError('FOREIGN_WORKFLOW')
            request=urllib.request.Request(self.origin+'/api/control/v1/workflow-profiles/published/'+self.binding['profileId']+'/'+value['profileDigest'],
                headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
            try:
                with self.opener.open(request,timeout=10) as publication:
                    current=publication.read(262145)
                    if len(current)>262144:raise ValueError('INVALID_PUBLICATION')
                    workflows=json.loads(current).get('workflows',[])
            except urllib.error.HTTPError as failure:
                if failure.code in (403,404):raise ValueError('FOREIGN_WORKFLOW') from None
                raise
            if not any(item.get('id')==value['workflowId'] and item.get('versionId')==value.get('workflowVersionId') for item in workflows):
                raise ValueError('FOREIGN_WORKFLOW')
            self.binding['profileDigest']=value['profileDigest']
            return value


class FolderAgent:
    def __init__(self, settings, exchange, device, results, journal, control):
        self.settings,self.exchange,self.device,self.results,self.control=settings,exchange,device,results,control
        self.owner=(int(os.environ['OUTPUT_UID']),int(os.environ['OUTPUT_GID'])) if 'OUTPUT_UID' in os.environ else None
        self.folders=settings['fileAgent']
        for path in (exchange,device,results,journal):
            if path.is_symlink():
                raise ValueError('DIRECTORY_LINK')
            path.mkdir(mode=0o700,parents=True,exist_ok=True)
        for name in ('inbox','processed','output'):
            path=exchange/self.folders[name]
            if path.is_symlink():
                raise ValueError('DIRECTORY_LINK')
            path.mkdir(mode=0o700,exist_ok=True)
        (device/'inbox').mkdir(mode=0o700,exist_ok=True)
        database=journal/'delivery.sqlite'
        self.db=sqlite3.connect(database)
        tables=self.db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        version=self.db.execute('PRAGMA user_version').fetchone()[0]
        if tables and version!=1:
            self.db.close()
            raise ValueError('UNSUPPORTED_JOURNAL')
        if not tables:
            self.db.execute('CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,digest TEXT NOT NULL,source TEXT UNIQUE NOT NULL,state TEXT NOT NULL,run TEXT,output TEXT)')
            self.db.execute('PRAGMA user_version=1')
            self.db.commit()
        database.chmod(0o600)
        self.db.row_factory=sqlite3.Row
        self.refused=set()

    def ingest(self):
        for path in sorted((self.exchange/self.folders['inbox']).iterdir()):
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,95}\.txt',path.name):
                continue
            try:
                self.stage(path)
            except (ValueError,OSError):
                if path.name not in self.refused and len(self.refused)<256:
                    self.refused.add(path.name)
                    print(json.dumps(dict(event='FOLDER_INPUT_REFUSED',input=path.name)),flush=True)

    def stage(self,path):
        if path.is_symlink() or time.time()-path.stat().st_mtime<1:
            return
        data=read_regular(path,MAX_INPUT)
        data.decode('utf-8')
        if not data or b'\0' in data:
            raise ValueError('INVALID_TEXT')
        checksum=digest(data)
        identity=digest((path.name+'\0'+checksum).encode())
        row=self.db.execute('SELECT state FROM jobs WHERE id=?',(identity,)).fetchone()
        if row is not None and row['state']!='STAGED':
            return
        source='inbox/'+identity+'.txt'
        if row is None:
            with self.db:
                self.db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,NULL,NULL)',(identity,path.name,checksum,source,'STAGED'))
        # Journal precedes the only trigger-visible filename. Recovery uses that same name.
        write_once(self.device/source,data)
        with self.db:
            self.db.execute("UPDATE jobs SET state='WAITING' WHERE id=?",(identity,))

    def project(self, worker):
        if not isinstance(worker,dict) or worker.get('state')!='SUCCEEDED':
            return
        run=worker.get('runId')
        if not isinstance(run,str) or not re.fullmatch(r'run-[a-f0-9-]{36}',run):
            return
        candidate=worker.get('result')
        if not isinstance(candidate,dict):
            return
        previous=self.db.execute('SELECT state FROM jobs WHERE run=?',(run,)).fetchone()
        if previous is not None and previous['state']=='COMPLETE':
            return
        saved=self.control.get(run)
        result=saved.get('outcome',{}).get('result') if isinstance(saved.get('outcome'),dict) else None
        if (saved.get('runId')!=run or saved.get('state')!='SUCCEEDED' or result!=worker.get('result')
                or not isinstance(saved.get('message'),str) or not isinstance(result,dict)):
            return
        # Only Control's admitted message selects the journal entry. Model output
        # never chooses a source file and need not echo an internal device path.
        row=self.db.execute('SELECT * FROM jobs WHERE source=?',(saved['message'],)).fetchone()
        if row is None or row['state'] not in ('WAITING','PROJECTING'):
            return
        if row['run'] is not None and row['run']!=run:
            return
        # The input and all output paths are fixed by custody/configuration, never by a model.
        source=self.exchange/self.folders['inbox']/row['name']
        destination=self.exchange/self.folders['processed']/row['name']
        # Recover after an archive committed but before the journal transaction.
        actual=source if source.exists() or not self.folders['archive'] else destination
        data=read_regular(actual,MAX_INPUT)
        if digest(data)!=row['digest']:
            raise ValueError('INPUT_CHANGED')
        if self.folders['projection']=='summary':
            text=result.get('summary')
            if not isinstance(text,str) or not text.strip() or len(text.encode())>16384:
                raise ValueError('INVALID_SUMMARY')
            output=(text.rstrip()+'\n').encode()
            suffix='.summary.txt'
        else:
            output=(json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode()
            suffix='.result.json'
        name=Path(row['name']).stem+'-'+row['id'][:12]+suffix
        with self.db:
            self.db.execute("UPDATE jobs SET state='PROJECTING',run=?,output=? WHERE id=?",(run,name,row['id']))
        write_once(self.exchange/self.folders['output']/name,output,self.owner)
        if self.folders['archive']:
            write_once(destination,data,self.owner)
            # A changed source is retained, never removed as though it were the processed input.
            if source.exists():
                if digest(read_regular(source,MAX_INPUT))!=row['digest']:
                    raise ValueError('INPUT_CHANGED')
                source.unlink()
        snapshot=self.device/row['source']
        if snapshot.exists():
            if digest(read_regular(snapshot,MAX_INPUT))!=row['digest']:
                raise ValueError('SNAPSHOT_CHANGED')
            snapshot.unlink()
        with self.db:
            self.db.execute("UPDATE jobs SET state='COMPLETE',run=?,output=? WHERE id=?",(run,name,row['id']))
        print(json.dumps(dict(event='FOLDER_DELIVERY_COMPLETE',agentId=self.folders['agentId'],runId=run,
            input=row['name'],output=self.folders['output']+'/'+name,archived=self.folders['archive'])),flush=True)

    def tick(self):
        self.ingest()
        for path in sorted(self.results.glob('run-*.json')):
            try:
                self.project(json.loads(read_regular(path,49152)))
            except (ValueError,FileNotFoundError):
                # Partial worker files and changed inputs do not authorize archival or overwrite.
                continue


if __name__=='__main__':
    while True:
        try:
            agent=FolderAgent(configuration.load('/configuration'),Path('/exchange'),Path('/device-files'),
                Path('/results'),Path('/journal'),ControlResults(os.environ['CONTROL_ORIGIN'],Path('/custody')))
            while True:
                agent.tick()
                time.sleep(2)
        except Exception:
            print('{"event":"FOLDER_DELIVERY_UNAVAILABLE"}',flush=True)
            time.sleep(10)
