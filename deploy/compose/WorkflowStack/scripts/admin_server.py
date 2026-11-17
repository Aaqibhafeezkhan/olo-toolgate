# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Loopback-only privileged operator UI; never mount Docker access into Control."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
import urllib.request
from urllib.parse import urlsplit

import configuration
import lifecycle

PORT = 48190
ORIGIN = f'http://127.0.0.1:{PORT}'
DEFAULT_IMAGE = 'ololab/olo-toolgate-quickstart:dev'


def linked(path):
    return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()


def private(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if linked(path) or linked(path.parent):
        raise ValueError('Linked operator custody')
    temporary = path.with_name(path.name + '.next')
    with open(temporary, 'w', encoding='utf-8', opener=lambda p, f: os.open(p, f, 0o600)) as stream:
        stream.write(value)
    os.replace(temporary, path)


class Controller:
    def __init__(self, root):
        self.root = root.resolve()
        self.home = self.root / 'state' / 'administrator'
        for path in (self.root / 'state', self.home):
            if linked(path):
                raise ValueError('Linked operator custody')
        self.home.mkdir(mode=0o700, parents=True, exist_ok=True)
        if os.name == 'nt':
            # Protect the whole custody tree, including temporary uploads and image exports.
            result = subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',
                "$ErrorActionPreference='Stop';$user=[System.Security.Principal.WindowsIdentity]::GetCurrent().User;"
                "$acl=New-Object System.Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false);"
                "foreach($sid in @($user,(New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')))){"
                "$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))};"
                "[System.IO.Directory]::SetAccessControl($env:WORKFLOW_ADMIN_DIRECTORY,$acl)"],
                env={**{key:value for key,value in os.environ.items() if key.casefold()!='psmodulepath'},
                     'WORKFLOW_ADMIN_DIRECTORY':str(self.home)}, capture_output=True, timeout=15)
            if result.returncode:
                raise ValueError('Cannot protect local administrator custody')
        if linked(self.root / 'configurations'):
            raise ValueError('Linked configuration directory')
        (self.root / 'configurations').mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.busy = False
        self.sessions = {}
        self.attempts = []
        account = self.home / 'account.json'
        if not account.exists():
            password, salt = secrets.token_urlsafe(32), secrets.token_hex(16)
            private(self.home / 'credentials/admin/username.txt', 'admin\n')
            private(self.home / 'credentials/admin/password.txt', password + '\n')
            private(account, json.dumps(dict(salt=salt, digest=self.password(password, salt))))
        self.account = json.loads(account.read_text(encoding='utf-8'))
        self.state = json.loads((self.home / 'status.json').read_text()) if (self.home / 'status.json').exists() else dict(active=None, state='stopped')

    @staticmethod
    def password(password, salt):
        return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 600000).hex()

    def login(self, document):
        now = time.monotonic()
        self.attempts = [value for value in self.attempts if now - value < 60]
        if len(self.attempts) >= 10:
            raise PermissionError('Too many login attempts; wait one minute')
        self.attempts.append(now)
        password = document.get('password', '')
        if (document.get('username') != 'admin' or not isinstance(password, str) or len(password) > 512
                or not secrets.compare_digest(self.password(password, self.account['salt']), self.account['digest'])):
            raise PermissionError('Invalid administrator credentials')
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        self.sessions = {key: value for key, value in self.sessions.items() if value[1] > now}
        self.sessions[token] = (csrf, now + 3600)
        return token, csrf

    def selected(self, name):
        if not isinstance(name, str) or not configuration.NAME.fullmatch(name) or name in ('blank', 'administrator'):
            raise ValueError('Select a configuration directory name')
        folder = self.root / 'configurations' / name
        if linked(folder) or not folder.is_dir():
            raise ValueError('Configuration not found')
        return configuration.load(folder)

    def status(self):
        profiles = []
        for folder in sorted((self.root / 'configurations').iterdir()):
            if (folder.is_dir() and not linked(folder) and configuration.NAME.fullmatch(folder.name)
                    and (folder / 'workflow-profile.json').is_file()):
                try:
                    settings = self.selected(folder.name)
                    profiles.append(dict(name=folder.name, workflow=settings['workflowId'], valid=True))
                except (ValueError, OSError, KeyError):
                    profiles.append(dict(name=folder.name, valid=False))
        visible={key:value for key,value in self.state.items() if key in
                 ('active','state','action','error','console','credentials','image','inbox','output')}
        return dict(**visible, busy=self.busy, configurations=profiles)

    def command(self, *args):
        # Child output stays in private custody, never in HTTP errors or the browser.
        with open(self.home / 'operation.log', 'ab') as log:
            subprocess.run([sys.executable, str(self.root / 'manage.py'), *args], cwd=self.root,
                           stdout=log, stderr=log, check=True, timeout=2400)

    def save(self, **values):
        self.state.update(values)
        private(self.home / 'status.json', json.dumps(self.state))

    def preserve_admin(self, scope):
        """Preserve only the existing local admin password verifier, never its database or tokens."""
        container=f'toolgate-workflow-{scope}-quickstart-1'
        result=subprocess.run(['docker','exec',container,'cat','/data/identity.json'],
                              check=True,capture_output=True,timeout=15)
        if len(result.stdout)>16384 or not isinstance(json.loads(result.stdout),dict):
            raise ValueError('Cannot preserve the current administrator login')
        private(self.home/'reset-admin/identity.json',result.stdout.decode('utf-8'))
        source=self.root/'state'/scope/'credentials/admin'
        for filename in ('username.txt','password.txt'):
            target=self.home/'reset-admin'/filename
            if (source/filename).is_file():
                private(target,(source/filename).read_text())
            elif target.exists():
                target.unlink()

    def blank(self):
        directory = self.root / 'state' / 'blank'
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        image = self.state.get('image', DEFAULT_IMAGE)
        if subprocess.run(['docker','image','inspect',image],capture_output=True).returncode:
            subprocess.run(['docker','pull',image],check=True,capture_output=True,timeout=600)
        document = dict(services=dict(quickstart=dict(image=image,
            ports=['0.0.0.0:48091:8080', '127.0.0.1:48451:8443'],
            environment=dict(TOOLGATE_DISABLE_ADMIN_PASSWORD='false', TOOLGATE_QUICKSTART_DATABASE_MODE='sqlite',
                             TOOLGATE_QUICKSTART_ADMIN_ONLY='true',
                             TOOLGATE_TRIGGERS_CONTROL_CORE_ENABLED='true',
                             TOOLGATE_CONTROL_PROFILE_PATH='/bootstrap/profile.json'),
            depends_on={'bootstrap-init':{'condition':'service_completed_successfully'}},
            volumes=['blank-data:/data', 'blank-bootstrap:/bootstrap:ro'],
            read_only=True, tmpfs=['/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532'],
            cap_drop=['ALL'], security_opt=['no-new-privileges:true'], restart='unless-stopped')),
            volumes={name: dict(labels={lifecycle.ROOT_LABEL: self.root.as_posix()})
                     for name in ('blank-data','blank-bootstrap')},
            networks=dict(default=dict(labels={lifecycle.ROOT_LABEL: self.root.as_posix()})))
        document['services']['bootstrap-init'] = dict(image=image,user='0:0',network_mode='none',
            entrypoint=['/opt/quickstart-python/bin/python','/deployment/blank_bootstrap.py'],
            volumes=['blank-bootstrap:/bootstrap',str(self.root/'scripts')+':/deployment:ro'],
            read_only=True,cap_drop=['ALL'],security_opt=['no-new-privileges:true'])
        preserved=self.home/'reset-admin/identity.json'
        if preserved.is_file():
            document['services']['credential-init']=dict(image=image,user='0:0',network_mode='none',
                entrypoint=['/opt/quickstart-python/bin/python','/deployment/reset_admin.py'],
                volumes=['blank-data:/data',str(self.root/'scripts')+':/deployment:ro',str(preserved)+':/input/identity.json:ro'],
                read_only=True,cap_drop=['ALL'],cap_add=['CHOWN','DAC_OVERRIDE','FOWNER'],security_opt=['no-new-privileges:true'])
            document['services']['quickstart']['depends_on']['credential-init']={'condition':'service_completed_successfully'}
            for filename in ('username.txt','password.txt'):
                source=self.home/'reset-admin'/filename
                if source.is_file():private(directory/'credentials/admin'/filename,source.read_text())
        path = directory / 'compose.json'
        private(path, json.dumps(document))
        command = ['docker', 'compose', '--project-directory', str(self.root), '-p', 'toolgate-workflow-blank', '-f', str(path)]
        result = subprocess.run(command + ['up', '-d', '--wait', '--wait-timeout', '240'], capture_output=True, timeout=300)
        if result.returncode:
            with open(self.home/'operation.log','ab') as log:
                log.write(result.stdout+result.stderr)
            raise ValueError('Blank startup failed; inspect state/administrator/operation.log and blank service logs')
        # Complete first login and save console credentials separately from the operator account.
        credential = directory / 'credentials/admin/password.txt'
        if not credential.exists() and not preserved.is_file():
            result = subprocess.run(command + ['exec', '-T', 'quickstart', 'cat', '/data/bootstrap-password'],
                                    check=True, capture_output=True, text=True, timeout=15)
            password = secrets.token_urlsafe(32)
            request = urllib.request.Request('http://127.0.0.1:48091/api/quickstart/v1/login',
                json.dumps(dict(username='admin', password=result.stdout.strip(), newPassword=password)).encode(),
                {'Content-Type': 'application/json'})
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=30) as response:
                response.read()
            private(directory / 'credentials/admin/username.txt', 'admin\n')
            private(credential, password + '\n')
        self.save(active=None, state='ready', console='http://127.0.0.1:48091/console/',
                  credentials=str(directory / 'credentials/admin') if credential.is_file() else None, inbox=None, output=None, error=None)
        private(self.root / '.env', 'CONFIGURATION=\n')

    def submit(self, action, name=None, confirmation=None, upload=None):
        if action not in ('switch', 'reset'):
            raise ValueError('Unsupported administrator action')
        if action in ('switch',):
            self.selected(name)
        if action == 'reset' and confirmation != 'FACTORY RESET':
            raise ValueError('Enter the displayed destructive-action confirmation')
        with self.lock:
            if self.busy:
                raise ValueError('Another administrator action is running')
            self.busy = True
            self.save(state='working', action=action, error=None)
        self.executor.submit(self.perform, action, name, upload)

    def perform(self, action, name, upload):
        try:
            previous = self.state.get('active')
            if action=='reset':self.preserve_admin(previous or 'blank')
            # Also adopt deployments started before the portal existed, without deleting storage.
            owned = []
            for identity, labels in lifecycle.objects('container'):
                project = labels.get(lifecycle.PROJECT, '')
                owner = labels.get(lifecycle.ROOT_LABEL) or labels.get(lifecycle.DIRECTORY)
                if (project.startswith(lifecycle.PREFIX) and owner
                        and os.path.normcase(str(Path(owner).resolve())) == os.path.normcase(str(self.root))):
                    owned.append(identity)
            if owned:
                lifecycle.docker('container', 'stop', '--time', '30', *owned)
            self.save(active=None, console=None, credentials=None, inbox=None, output=None)
            if action == 'blank':
                self.blank()
            elif action == 'reset':
                if previous:
                    lifecycle.destroy(self.root, previous)
                if (self.root / 'state/blank').exists():
                    lifecycle.destroy(self.root, 'blank')
                self.blank()
            else:
                settings = self.selected(name)
                self.command('deploy', '--configuration', name)
                platform = settings['deployment']['platform']
                self.save(active=name, state='ready', image=platform['quickstartImage'], error=None,
                    console=f'http://127.0.0.1:{platform["httpPort"]}/console/',
                    credentials=str(self.root / 'state' / name / 'credentials/admin'),
                    inbox=str(self.root / 'data' / name / settings['fileAgent']['inbox']) if settings['fileAgent'] else None,
                    output=str(self.root / 'data' / name / settings['fileAgent']['output']) if settings['fileAgent'] else None)
                private(self.root / '.env', 'CONFIGURATION='+name+'\n')
        except Exception as failure:
            self.save(state='failed', error=str(failure) if isinstance(failure, ValueError) else
                      'Operation failed. Inspect state/administrator/operation.log and the selected service logs.')
        finally:
            if upload:
                upload.unlink(missing_ok=True)
            with self.lock:
                self.busy = False


class Handler(BaseHTTPRequestHandler):
    server_version = 'WorkflowStack'

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, *_):
        pass

    def send(self, status, value, cookie=None):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if cookie:
            self.send_header('Set-Cookie', f'workflow_stack={cookie}; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600')
        self.end_headers()
        self.wfile.write(data)

    def session(self, mutate=False):
        cookie = SimpleCookie(self.headers.get('Cookie', ''))
        token = cookie.get('workflow_stack')
        session = self.server.controller.sessions.get(token.value if token else '')
        if not session or session[1] < time.monotonic():
            raise PermissionError('Sign in to WorkflowStack administration')
        if mutate and not secrets.compare_digest(self.headers.get('X-WorkflowStack-CSRF', ''), session[0]):
            raise PermissionError('Refresh your administrator session')
        return session

    def proxy(self):
        """Same-origin console facade. Control still authenticates and authorizes every API call."""
        origin=self.server.controller.state.get('console')
        if not self.path.startswith(('/console/','/api/control/','/api/quickstart/','/health/')):
            self.send(404,dict(error='Not found'))
            return
        if self.headers.get('Host')!=f'127.0.0.1:{PORT}' or not origin:
            self.send(503,dict(error='Control is starting'))
            return
        target=urlsplit(origin)
        if target.scheme!='http' or target.hostname!='127.0.0.1' or not target.port:
            self.send(503,dict(error='Control origin unavailable'))
            return
        if self.command!='GET' and self.headers.get('Origin')!=ORIGIN:
            self.send(403,dict(error='Same-origin requests required'))
            return
        length=int(self.headers.get('Content-Length','0'))
        if not 0<=length<=2*1024*1024:
            self.send(413,dict(error='Request too large'))
            return
        skipped={'host','connection','transfer-encoding','content-length','origin','referer','x-workflowstack-csrf'}
        headers={key:value for key,value in self.headers.items() if key.lower() not in skipped}
        if self.headers.get('Cookie'):
            cookies=SimpleCookie(self.headers['Cookie'])
            if 'workflow_stack' in cookies:del cookies['workflow_stack']
            headers['Cookie']='; '.join(value.OutputString() for value in cookies.values())
        if self.headers.get('Origin'):headers['Origin']=f'http://127.0.0.1:{target.port}'
        if self.headers.get('Referer'):headers['Referer']=f'http://127.0.0.1:{target.port}/console/'
        connection=http.client.HTTPConnection('127.0.0.1',target.port,timeout=60)
        try:
            connection.request(self.command,self.path,body=self.rfile.read(length) if length else None,headers=headers)
            response=connection.getresponse()
            self.send_response(response.status)
            for key,value in response.getheaders():
                if key.lower() not in ('connection','transfer-encoding'):
                    if key.lower()=='set-cookie' and value.startswith('workflow_stack='):continue
                    self.send_header(key,value)
            self.end_headers()
            while data:=response.read(65536):self.wfile.write(data)
        except (OSError,http.client.HTTPException):
            # No retry of mutations, and no upstream bodies/credentials in errors.
            self.close_connection=True
        finally:connection.close()

    def do_GET(self):
        if self.headers.get('Host') != f'127.0.0.1:{PORT}':
            self.send(403, dict(error='Invalid host'))
            return
        try:
            if self.path.startswith(('/console/','/api/control/','/api/quickstart/','/health/')):
                self.proxy()
            elif self.path=='/':
                if self.server.controller.state.get('console'):
                    self.send_response(302);self.send_header('Location','/console/#workflows');self.end_headers()
                else:
                    data=b'<html><head><meta http-equiv="refresh" content="3"></head><body>Starting Control. <a href="/operator">Stack operator</a></body></html>'
                    self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            elif self.path == '/operator':
                data = (Path(__file__).with_name('admin.html')).read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
                self.end_headers()
                self.wfile.write(data)
            elif self.path == '/api/status':
                session = self.session()
                self.send(200, dict(**self.server.controller.status(), csrf=session[0]))
            else:
                self.send(404, dict(error='Not found'))
        except PermissionError as failure:
            self.send(401, dict(error=str(failure)))
        except (ValueError, OSError):
            self.send(400, dict(error='Request unavailable'))

    def do_POST(self):
        if self.path.startswith(('/api/control/','/api/quickstart/')):
            self.proxy()
            return
        if self.headers.get('Host') != f'127.0.0.1:{PORT}' or self.headers.get('Origin') != ORIGIN:
            self.send(403, dict(error='Same-origin requests required'))
            return
        try:
            length = int(self.headers.get('Content-Length', '-1'))
            if not 0 < length <= 8192:
                raise ValueError('Invalid request size')
            document = json.loads(self.rfile.read(length))
            if not isinstance(document, dict):
                raise ValueError('Use a JSON object')
            if self.path == '/api/login':
                token, csrf = self.server.controller.login(document)
                self.send(200, dict(csrf=csrf), token)
            elif self.path == '/api/action':
                self.session(mutate=True)
                self.server.controller.submit(document.get('action'), document.get('name'), document.get('confirmation'))
                self.send(202, dict(accepted=True))
            elif self.path == '/api/shutdown':
                self.session(mutate=True)
                if self.server.controller.busy:
                    raise ValueError('Wait for the current operation to complete')
                self.send(200, dict(stopped=True))
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            else:
                self.send(404, dict(error='Not found'))
        except PermissionError as failure:
            self.send(401, dict(error=str(failure)))
        except (ValueError, TypeError, KeyError):
            self.send(400, dict(error='Invalid request or administrator operation already running'))

    def do_PUT(self):self.proxy()
    def do_DELETE(self):self.proxy()
    def do_PATCH(self):self.proxy()


def serve(root, selection):
    controller = Controller(root)
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    server.controller = controller
    services = ['quickstart']
    if selection:
        settings = controller.selected(selection)
        services += ['registry','linux-device','workflow-model','workflow-worker','workflow-listener','identity']
        if settings['fileAgent']:
            services.append('folder-agent')
    scope = selection or 'blank'
    result = subprocess.run(['docker','container','inspect',
        *[f'toolgate-workflow-{scope}-{service}-1' for service in services]],capture_output=True,text=True,timeout=15)
    running = json.loads(result.stdout) if result.returncode == 0 else []
    ready = (controller.state.get('state') == 'ready' and controller.state.get('active') == selection
             and len(running) == len(services) and all(item['State']['Running']
             and item['State'].get('Health',{}).get('Status','healthy') == 'healthy' for item in running))
    if ready:
        pass  # Restarting only the administration UI does not restart healthy workflows.
    elif selection:
        controller.submit('switch', selection)
    else:
        controller.busy = True
        controller.executor.submit(controller.perform, 'blank', None, None)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        controller.executor.shutdown(wait=True)


def shutdown(root):
    """CLI teardown authenticates to the owned operator; never kill a guessed PID."""
    home = root / 'state/administrator'
    if not (home / 'account.json').is_file():
        return
    import http.cookiejar
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def post(path, body, csrf=None):
        request = urllib.request.Request(ORIGIN+path, json.dumps(body).encode(),
            {'Content-Type':'application/json','Origin':ORIGIN,
             **({'X-WorkflowStack-CSRF':csrf} if csrf else {})})
        with opener.open(request, timeout=5) as response:return json.load(response)
    try:
        result = post('/api/login', dict(username='admin',
            password=(home/'credentials/admin/password.txt').read_text().strip()))
        post('/api/shutdown', {}, result['csrf'])
        for _ in range(30):
            try:
                with opener.open(ORIGIN+'/operator',timeout=.3) as response:response.read(1)
            except OSError:
                return
            time.sleep(.1)
        raise ValueError('Administrator UI has not finished shutting down')
    except urllib.error.URLError as failure:
        if isinstance(failure, urllib.error.HTTPError):
            raise ValueError('Administrator UI refused shutdown; finish its current operation first') from None
