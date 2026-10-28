# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
# Test-only runtime fixture: fresh local volume; no customer data or production administration.
import os,sys,json,time,uuid,ssl,urllib.request,urllib.error,subprocess,shutil,base64,copy,socket,hashlib,datetime
from pathlib import Path
sys.path.insert(0,'/opt/quickstart')
import supervisor as s
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,ec,padding
os.umask(0o077)
postgres=os.environ.get('TOOLGATE_TEST_POSTGRESQL')=='true'
if postgres:
    deadline=time.monotonic()+90
    while not Path('/data/postgres-ready').exists():
        if time.monotonic()>deadline:raise TimeoutError('Shared database provisioning timeout')
        time.sleep(.2)
    s.atomic(Path('/data/platform-password'),os.environ['TOOLGATE_TEST_PLATFORM_PASSWORD'])
s.initialize(); env=s.configure()
root=Path('/data/shared-v2');root.mkdir(mode=0o700)
a=s.TENANT;b=str(uuid.uuid4());owners=[a,b]
hosts=['tenant-a.example.test','tenant-b.example.test','unknown.example.test']
origins=['https://'+host+':8443' for host in hosts]
manifest=dict(formatVersion=1,defaultTenantAlias='quickstart',tenants=[])
for index,tenant in enumerate(owners):
    alias=['quickstart','second'][index];own=root/alias;own.mkdir(mode=0o700);(own/'state').mkdir(mode=0o700)
    if index==0:packet,trust=Path('/data/installation-review.json'),Path('/data/installation-trust.json')
    else:packet,trust=s.prepare(own,tenant,s.ISSUER,s.key,s.atomic)
    shutil.copyfile(packet,own/'packet.json');shutil.copyfile(trust,own/'trust.json')
    manifest['tenants'].append(dict(alias=alias,name='Tenant '+str(index+1),hosts=[hosts[index]],administratorProvisioningRef=alias,endpoints=dict(serverId='server-'+str(index+1),controlUrl=origins[index],gatewayUrl=origins[index])))
s.atomic(root/'manifest.json',json.dumps(manifest))
custody=Path('/data/tenant-identity')
initial=json.loads((custody/'publication.json').read_text())['publications'][0]

def generate(tenant,purpose,kid):
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    reference=dict(purpose=purpose,keyId=kid,state='ACTIVE')
    if purpose=='DEVICE_CA':
        now=datetime.datetime.now(datetime.timezone.utc)
        name=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'Tenant device CA'),x509.NameAttribute(x509.NameOID.ORGANIZATIONAL_UNIT_NAME,tenant)])
        ca=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=365)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).sign(key,hashes.SHA256())
        reference.update(keyId=hashlib.sha256(ca.public_bytes(serialization.Encoding.DER)).hexdigest(),certificatePem=ca.public_bytes(serialization.Encoding.PEM).decode())
    directory=custody/tenant/purpose;directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    s.atomic(directory/(reference['keyId']+'.pk8.pem'),key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    return reference

other=dict(tenantId=b,generation=1,keys=[generate(b,'DISCOVERY','discovery-b1'),generate(b,'DEVICE_CA','unused')])
next_keys=[generate(a,'DISCOVERY','discovery-a2'),generate(a,'DEVICE_CA','unused')]
# Roll public listener roots out before any issuer activation; the application still owns authorization.
store=custody/'shared-trust.p12'
for item in [*initial['keys'],*other['keys'],*next_keys]:
    if item['purpose']!='DEVICE_CA':continue
    path=custody/(item['keyId']+'.crt');s.atomic(path,item['certificatePem'])
    subprocess.run(['keytool','-importcert','-noprompt','-alias',item['keyId'],'-file',str(path),'-keystore',str(store),'-storetype','PKCS12','-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
now=datetime.datetime.now(datetime.timezone.utc)
tls_ca=x509.load_pem_x509_certificate(Path('/data/keys/device-ca.crt').read_bytes())
tls_key=s.key('device-ca');server_key=s.key('server-tls')
leaf=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,hosts[0])])).issuer_name(tls_ca.subject).public_key(server_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=7)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.SubjectAlternativeName([x509.DNSName(host) for host in hosts]),False).add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),False).sign(tls_key,hashes.SHA256())
s.atomic(root/'server.crt',leaf.public_bytes(serialization.Encoding.PEM))
env.update(TOOLGATE_TENANCY_MODE='subdomain',TOOLGATE_INITIALIZE_MANIFEST=str(root/'manifest.json'),TOOLGATE_CONTROL_BUILDER_ENABLED='false',QUARKUS_HTTP_SSL_CERTIFICATE_FILES=str(root/'server.crt'),QUARKUS_HTTP_SSL_CERTIFICATE_TRUST_STORE_FILE=str(store),TOOLGATE_CONTROL_IDENTITY_PUBLICATION_PATH=str(root/'publication.json'))
operator_properties={
 'toolgate.tenancy.mode':'subdomain','toolgate.quickstart.enabled':'true','toolgate.quickstart.database':'/data/state/control.sqlite',
 'toolgate.control.vault.wrapping-key-ring-path':'/data/keys/vault-wrapping.json',
 'toolgate.control.identity.custody-root':str(custody),'toolgate.control.identity.publication-path':str(root/'publication.json'),
 'mp.jwt.verify.publickey.location':'/data/keys/identity-public.pem','quarkus.http.ssl.certificate.files':str(root/'server.crt'),
 'toolgate.control.bundle.private-key-path':'/data/keys/policy.pem','toolgate.control.fleet.private-key-path':'/data/keys/fleet.pem',
 'toolgate.control.package-release.private-key-path':'/data/keys/package-release.pem'}
if postgres:
    operator_properties.update({'toolgate.quickstart.enabled':'false','toolgate.control.development-mode':'true',
        'quarkus.datasource.jdbc.url':os.environ['QUARKUS_DATASOURCE_JDBC_URL'],'quarkus.datasource.username':'control_app',
        'quarkus.flyway.username':'control_migrator','toolgate.control.platform.username':'control_provisioner',
        'toolgate.control.platform.password-path':'/data/platform-password'})
operator_file=root/'operator.json';s.atomic(operator_file,json.dumps(dict(properties=operator_properties,developmentSqlite=not postgres,developmentLoopback=postgres)))

def metadata(statement,values=(),tenant=None):
    if postgres:
        import psycopg
        with psycopg.connect(host='127.0.0.1',dbname='control',user='control_provisioner',password=os.environ['TOOLGATE_TEST_PLATFORM_PASSWORD'],sslmode='disable') as connection:
            connection.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant or a,))
            return connection.execute(statement.replace('?','%s'),values).fetchone()[0]
    import sqlite3
    with sqlite3.connect('/data/state/control.sqlite') as connection:return connection.execute(statement,values).fetchone()[0]
def operator(command,target,*extra):
    result=subprocess.run(['java','-cp','/app/app/*:/app/lib/main/*:/app/lib/boot/*','io.ololabs.toolgate.control.adapter.TenantOperator',command,str(operator_file),str(target),*extra],capture_output=True,text=True,timeout=30)
    assert result.returncode==0,'Protected operator command failed: '+command
    return json.loads(result.stdout.strip().splitlines()[-1])
tls=ssl.create_default_context(cafile='/data/keys/device-ca.crt')
assert tls.check_hostname
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=tls))
def request(index,path,body=None,token=None,context=None,headers=None):
    values={'Content-Type':'application/json','Idempotency-Key':uuid.uuid4().hex};values.update(headers or {})
    if token:values['Authorization']='Bearer '+token
    url=origins[index]+path
    call=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),headers=values)
    transport=opener if context is None else urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
    try:
        with transport.open(call,timeout=10) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as r:return r.code,json.loads(r.read())
def admin(index):
    s.TENANT=owners[index];return s.jwt(s.GROUPS,'admin')
def ok(reply):
    assert reply[0] in (200,201),reply[0:1]+(reply[1].get('code') if isinstance(reply[1],dict) else None,)
    return reply[1]
gateways=[]
process=None
def stop():
    global process
    if process is not None:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        process=None
def start(generation,keys):
    global process
    stop();s.atomic(root/'publication.json',json.dumps(dict(formatVersion=1,publications=[dict(tenantId=a,generation=generation,keys=keys),other])))
    log=open(root/('control-'+str(generation)+'.log'),'wb')
    process=subprocess.Popen(['java','-jar','/app/quarkus-run.jar'],env=env,stdout=log,stderr=log);log.close()
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        assert process.poll() is None,'Control startup failed; private diagnostic log retained in fixture volume'
        try:
            with urllib.request.urlopen('http://127.0.0.1:9092/q/health/ready',timeout=2) as r:
                if r.status==200:return
        except (urllib.error.URLError,TimeoutError):pass
        time.sleep(.25)
    raise AssertionError('Control readiness timeout')
def discovery(index):
    envelope=ok(request(index,'/.well-known/olo-toolgate-client'))
    parts=envelope['jws'].split('.');decode=lambda value:base64.urlsafe_b64decode(value+'='*((-len(value))%4))
    header=json.loads(decode(parts[0]));payload=json.loads(decode(parts[1]))
    public=next(key for key in payload['discoveryKeys']['keys'] if key['kid']==header['kid'])
    key=rsa.RSAPublicNumbers(int.from_bytes(decode(public['e']),'big'),int.from_bytes(decode(public['n']),'big')).public_key()
    key.verify(decode(parts[2]),(parts[0]+'.'+parts[1]).encode(),padding.PKCS1v15(),hashes.SHA256())
    assert payload['tenantId']==owners[index] and payload['controlUrl']==origins[index] and payload['protocolVersion']==2
    return envelope,payload
def enroll(index,device):
    key=ec.generate_private_key(ec.SECP256R1())
    csr=x509.CertificateSigningRequestBuilder().subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,device)])).sign(key,hashes.SHA256())
    announcement=dict(deviceId=device,clientVersion=Path('/opt/quickstart/VERSION').read_text().strip(),platform='LINUX',csrPem=csr.public_bytes(serialization.Encoding.PEM).decode(),capabilities=[])
    challenge=ok(request(index,'/api/control/v1/endpoint/enrollments',announcement))
    review=ok(request(index,'/api/control/v1/endpoint/enrollments/review?code='+challenge['userCode'],token=admin(index)))
    ok(request(index,'/api/control/v1/endpoint/enrollments/decision',dict(userCode=review['userCode'],keyFingerprint=review['keyFingerprint'],choice='APPROVE'),admin(index)))
    issued=ok(request(index,'/api/control/v1/endpoint/enrollments/poll',dict(enrollmentId=challenge['enrollmentId'],deviceCode=challenge['deviceCode'])))['identity']
    own=root/(str(index)+'-'+device);own.mkdir(mode=0o700)
    cert=own/'client.crt';private=own/'client.key'
    s.atomic(cert,issued['certificatePem']);s.atomic(private,key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    context=ssl.create_default_context(cafile='/data/keys/device-ca.crt');context.load_cert_chain(cert,private)
    report=dict(sequence=1,report=dict(deviceId=device,clientVersion=Path('/opt/quickstart/VERSION').read_text().strip(),appliedRevision=0,packages=[]))
    ok(request(index,'/api/control/v1/endpoint/check-in',report,context=context))
    return issued,context,report
def start_gateways():
    original=json.loads(Path('/data/run/gateway.json').read_text())
    credentials=json.loads(Path('/data/run/credentials.json').read_text())
    for index in (0,1):
        config=copy.deepcopy(original)
        config.update(listen='127.0.0.1:'+str(18081+index),managementListen='127.0.0.1:'+str(19091+index),tenantHosts=[hosts[index]+':8443'],publicDiscovery=dict(controlOrigin=origins[index],gatewayOrigin=origins[index]))
        config['control'].update(tenantId=owners[index],url=origins[index],developmentLoopbackHttp=False,tokenPath='/data/nonexistent-service-jwt')
        credential=copy.deepcopy(credentials[0]);digest=hashlib.sha256(os.urandom(48)).hexdigest()
        credential['tokenSha256']=digest;credential['context'].update(tenantId=owners[index],credentialSha256=digest)
        settings=root/('gateway-'+str(index)+'.json');facts=root/('facts-'+str(index)+'.json')
        s.atomic(settings,json.dumps(config));s.atomic(facts,json.dumps([credential]))
        log=open(root/('gateway-'+str(index)+'.log'),'wb')
        child=subprocess.Popen(['/usr/local/bin/olo-toolgate-gateway'],env=dict(os.environ,TOOLGATE_GATEWAY_CONFIG=str(settings),TOOLGATE_GATEWAY_CREDENTIALS=str(facts)),stdout=log,stderr=log);log.close();gateways.append(child)
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            assert child.poll() is None,'Gateway startup failed; private fixture log retained'
            try:
                with socket.create_connection(('127.0.0.1',18081+index),timeout=1):break
            except OSError:time.sleep(.2)
        else:raise AssertionError('Gateway did not listen')

def gateway_request(index,path,headers=None):
    call=urllib.request.Request('http://127.0.0.1:'+str(18081+index)+path,headers={'Host':hosts[index]+':8443',**(headers or {})})
    try:
        with urllib.request.urlopen(call,timeout=10) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as r:return r.code,json.loads(r.read())

def check_gateways(generation):
    for index in (0,1):
        deadline=time.monotonic()+20
        while True:
            result=gateway_request(index,'/.well-known/olo-toolgate-client')
            if result[0]!=503:break
            assert time.monotonic()<deadline,'Gateway did not recover after Control restart'
            time.sleep(.25)
        reply=ok(result)
        encoded=reply['jws'].split('.')[1];payload=json.loads(base64.urlsafe_b64decode(encoded+'='*((-len(encoded))%4)))
        assert payload['tenantId']==owners[index] and payload['keySetVersion']==(generation if index==0 else 1)
        assert ok(gateway_request(index,'/api/public/v1/clients/configuration'))=={'serverUrl':origins[index]}
        assert gateway_request(index,'/.well-known/olo-toolgate-client',{'Toolgate-Tenant-ID':owners[1-index]})[0]==403
        assert gateway_request(index,'/.well-known/olo-toolgate-client',{'Host':'unknown.example.test:8443'})[0]==404

evidence=dict(owners=owners,origins=origins,a=[],b=[])
try:
    start(1,initial['keys']);start_gateways();check_gateways(1)
    for index in (0,1):
        envelope,payload=discovery(index);evidence[['a','b'][index]].append(envelope)
        assert request(index,'/api/control/v1/settings',token=admin(1-index))[0]==403
        assert request(index,'/.well-known/olo-toolgate-client',headers={'Toolgate-Tenant-ID':owners[1-index]})[0]==403
    old_a,ca,report_a=enroll(0,'same-device');identity_b,cb,report_b=enroll(1,'same-device')
    for index,context,report in [(1,ca,report_a),(0,cb,report_b)]:
        reply=request(index,'/api/control/v1/endpoint/check-in',report,context=context)
        assert reply[0]==403 and reply[1]['code']=='TENANT_MISMATCH',reply
    print('Two-tenant TLS/SNI, independent v2 signatures, repeated device IDs, optional UUID and foreign certificates passed.',flush=True)
    # Generation 2 announces replacements under the original signer.
    announced=copy.deepcopy(initial['keys'])+copy.deepcopy(next_keys)
    for item in announced[2:]:item['state']='READ_ONLY'
    start(2,announced);check_gateways(2)
    for index in (0,1):evidence[['a','b'][index]].append(discovery(index)[0])
    # Generation 3 activates the already announced signer/CA, retaining the previous CA read-only.
    rotated=copy.deepcopy(announced)
    for item in rotated[:2]:item['state']='READ_ONLY'
    for item in rotated[2:]:item['state']='ACTIVE'
    start(3,rotated);check_gateways(3)
    for index in (0,1):evidence[['a','b'][index]].append(discovery(index)[0])
    report_a['sequence']=2;ok(request(0,'/api/control/v1/endpoint/check-in',report_a,context=ca))
    new_a,new_ca,new_report=enroll(0,'rotated-device')
    assert new_a['issuerCertificatePem']!=old_a['issuerCertificatePem']
    # Generation 4 retires the old CA/signer. Listener roots intentionally still include the old CA.
    start(4,next_keys);check_gateways(4)
    for index in (0,1):evidence[['a','b'][index]].append(discovery(index)[0])
    report_a['sequence']=3
    assert request(0,'/api/control/v1/endpoint/check-in',report_a,context=ca)[0] in (401,403)
    new_report['sequence']=2;ok(request(0,'/api/control/v1/endpoint/check-in',new_report,context=new_ca))
    report_b['sequence']=2;ok(request(1,'/api/control/v1/endpoint/check-in',report_b,context=cb))
    # Protected platform lifecycle command; no public administration route or fixture SQL mutation.
    import sqlite3
    assert operator('suspend','quickstart',a)['status']=='SUSPENDED'
    unknown=request(2,'/.well-known/olo-toolgate-client')
    suspended=request(0,'/.well-known/olo-toolgate-client')
    assert unknown[0]==suspended[0]==404 and unknown[1]['code']==suspended[1]['code']=='TENANT_UNAVAILABLE'
    assert set(unknown[1])==set(suspended[1])
    assert gateway_request(0,'/.well-known/olo-toolgate-client')[0]==404
    assert gateway_request(1,'/.well-known/olo-toolgate-client')[0]==200
    assert operator('resume','quickstart',a)['status']=='ACTIVE'
    late=str(uuid.uuid4());own=root/'late';own.mkdir(mode=0o700);(own/'state').mkdir(mode=0o700)
    packet,trust=s.prepare(own,late,s.ISSUER,s.key,s.atomic);shutil.copyfile(packet,own/'packet.json');shutil.copyfile(trust,own/'trust.json')
    late_manifest=root/'late-manifest.json'
    s.atomic(late_manifest,json.dumps(dict(formatVersion=1,defaultTenantAlias='late',tenants=[dict(alias='late',name='Late tenant',hosts=[hosts[2]],administratorProvisioningRef='late',endpoints=dict(serverId='late-server',controlUrl=origins[2],gatewayUrl=origins[2]))])))
    assert operator('preview',late_manifest)[0]['action']=='INITIALIZE'
    assert metadata('SELECT count(*) FROM control_tenants')==2
    assert operator('provision',late_manifest)[0]['action']=='INITIALIZED'
    assert operator('provision',late_manifest)[0]['action']=='ALREADY_INITIALIZED'
    assert operator('inspect','late')['tenantId']==late
    publication=json.loads((root/'publication.json').read_text());publication['publications'].append(dict(tenantId=late,generation=1,keys=[generate(late,'DISCOVERY','late-1'),generate(late,'DEVICE_CA','unused')]))
    s.atomic(root/'publication.json',json.dumps(publication));assert operator('publish-identity',root/'publication.json')['published']
    s.TENANT=late;late_token=s.jwt(s.GROUPS,'admin')
    assert request(2,'/api/control/v1/settings',token=late_token)[0]==200
    assert request(2,'/api/control/v1/settings',token=admin(0))[0]==403
    assert request(2,'/.well-known/olo-toolgate-client')[0]==200
    before_b=operator('capacity','second');limits=root/'capacity.json'
    s.atomic(limits,json.dumps(dict(maxStoredBytes=536870912,maxStoredRows=100000,maxQueuedRows=128)))
    updated=operator('capacity','quickstart',a,str(limits))
    assert updated['maxStoredBytes']==536870912 and updated['maxQueuedRows']==128
    assert operator('capacity','second')==before_b
    wrong=subprocess.run(['java','-cp','/app/app/*:/app/lib/main/*:/app/lib/boot/*','io.ololabs.toolgate.control.adapter.TenantOperator','capacity',str(operator_file),'quickstart',b,str(limits)],capture_output=True,timeout=30)
    assert wrong.returncode!=0 and operator('capacity','quickstart')==updated
    assert metadata("SELECT count(*) FROM control_audit WHERE tenant_id=? AND operation='TENANT_CAPACITY_UPDATE'",(a,),a)==1
    print('Protected UUID-confirmed capacity update, audit and unchanged second-tenant limits passed.',flush=True)
    print('Protected operator preview, late one-time provisioning, identity publication, inspect and audited suspend/resume passed.',flush=True)
    evidence.update(oldIdentityA=old_a,newIdentityA=new_a,identityB=identity_b)
    s.atomic(Path('/data/evidence.json'),json.dumps(evidence))
    print('Live preannouncement, signer/CA activation, overlap, retirement and unaffected second-tenant certificate passed.',flush=True)
finally:
    stop()
    for child in gateways:
        child.terminate()
        try:child.wait(timeout=5)
        except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
