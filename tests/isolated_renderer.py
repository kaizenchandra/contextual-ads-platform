"""Real browser/HTTP/PostgreSQL/S3 test with disposable storage and database.
Requires built application/assets images, main Keycloak, and Playwright Chromium. A dedicated synthetic publisher HTTP server is created.
No production bypass endpoints. The reserved example.com fixture is inserted ONLY into a disposable DB.
"""
import base64,json,os,socket,struct,subprocess,tempfile,time,urllib.request,urllib.parse,uuid,zlib,threading,http.server
from pathlib import Path
root=Path(__file__).resolve().parents[1]
runid=uuid.uuid4().hex[:12];database='ads-render-db-'+runid;assets='ads-render-assets-'+runid;backend='ads-render-api-'+runid
network='contextual-ads-platform_default';names=[]
class PublisherFixture(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path!='/':self.send_error(404);return
  body=b'<!doctype html><html lang="en"><meta charset="utf-8"><title>Synthetic publisher</title><body><h1>Synthetic publisher integration fixture</h1></body></html>'
  self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
publisher_server=http.server.ThreadingHTTPServer(('127.0.0.1',0),PublisherFixture)
threading.Thread(target=publisher_server.serve_forever,daemon=True).start()
publisher_origin='http://localhost:'+str(publisher_server.server_port)
def docker(*args):return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.PIPE).strip()
def wait(url):
 for _ in range(60):
  try:
   with urllib.request.urlopen(url,timeout=2) as r:
    if r.status==200:return
  except Exception:time.sleep(1)
 raise RuntimeError('Fixture service did not become ready: '+url)
def token(user):
 data=urllib.parse.urlencode({'client_id':'contextads-test','grant_type':'password','username':user,'password':user+'-dev'}).encode()
 with urllib.request.urlopen(urllib.request.Request('http://localhost:8186/realms/contextads/protocol/openid-connect/token',data=data),timeout=10) as r:return json.load(r)['access_token']
def subject(token):
 payload=token.split('.')[1];value=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))['sub'];return str(uuid.UUID(value))
def png():
 def chunk(name,body):return struct.pack('>I',len(body))+name+body+struct.pack('>I',zlib.crc32(name+body)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',96,48,8,6,0,0,0))+chunk(b'IDAT',zlib.compress((b'\x00'+b'\x44\x88\x55\xff'*96)*48))+chunk(b'IEND',b'')
try:
 for name,image,extra in [(database,'postgres:17.6',['-e','POSTGRES_DB=ads','-e','POSTGRES_USER=ads','-e','POSTGRES_PASSWORD=fixture-only']), (assets,'contextual-ads-minio:local',['-e','MINIO_ROOT_USER=local-assets','-e','MINIO_ROOT_PASSWORD=local-assets-secret-change-me'])]:
  args=['run','-d','--name',name,'--network',network,*extra,image];
  if name==assets:args+=['server','/data']
  docker(*args);names.append(name)
 for container,probe in [(database,['pg_isready','-U','ads','-d','ads']),(assets,['curl','--fail','--silent','--max-time','2','http://localhost:9000/minio/health/ready'])]:
  for _ in range(60):
   try:docker('exec',container,*probe);break
   except subprocess.CalledProcessError:time.sleep(.5)
  else:raise RuntimeError('Fixture dependency did not become ready')
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 api='http://localhost:'+str(port)
 docker('run','-d','--name',backend,'--network',network,'-p','127.0.0.1:'+str(port)+':8080','-e','DATABASE_URL=jdbc:postgresql://'+database+':5432/ads','-e','DATABASE_PASSWORD=fixture-only','-e','ADS_ASSETS_ENDPOINT=http://'+assets+':9000','-e','ADS_RENDER_ORIGIN='+api,'-e','ADS_CONSOLE_ORIGIN='+publisher_origin,'-e','ADS_EVENTS_ENABLED=false','-e','OIDC_ISSUER=http://localhost:8186/realms/contextads','-e','OIDC_JWKS=http://contextual-ads-platform-keycloak-1:8080/realms/contextads/protocol/openid-connect/certs','contextual-ads-platform-backend');names.append(backend)
 wait(api+'/actuator/health');tokens={u:token(u) for u in ['advertiser','publisher','admin']}
 def call(path,data=None,user='advertiser',body=None,content_type='application/json'):
  request=urllib.request.Request(api+'/api/v1/'+path,headers={'Authorization':'Bearer '+tokens[user],'Content-Type':content_type},data=body if body is not None else json.dumps(data).encode() if data is not None else None)
  with urllib.request.urlopen(request,timeout=15) as r:return json.load(r)
 headline='<script>parent.attack=true</script> Java tools'
 campaign=call('campaigns',{'name':'Isolated synthetic browser campaign','headline':'Initial Java ad','category':'technology.java','destination':'https://example.org','budget':1000,'dailyBudget':1000,'bid':5})['id']
 boundary='ads'+uuid.uuid4().hex;image=png()+b'<script>parent.attack=true</script>'
 multipart=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n').encode()+image+('\r\n--'+boundary+'--\r\n').encode()
 asset=call('creatives/assets',body=multipart,content_type='multipart/form-data; boundary='+boundary)['id']
 creative=call('creatives',{'campaignId':campaign,'headline':headline,'description':'Synthetic image and literal text; no creative scripts.','destination':'https://example.org/advertiser','assetId':asset})['id']
 call('admin/creatives/'+creative+'/approve',{'reason':'Synthetic image pixels and destination reviewed'},'admin');call('campaigns/'+campaign+'/activate',{})
 website=str(uuid.uuid4());placement=str(uuid.uuid4());publisher=subject(tokens['publisher'])
 sql=f"INSERT INTO publisher_websites(id,owner_name,domain,verification_token,challenge_expires,verified_until) VALUES('{website}','{publisher}','publisher.example.com','isolated-test',now()+interval '1 day',now()+interval '1 day'); INSERT INTO domain_ownership(domain,website_id) VALUES('publisher.example.com','{website}'); INSERT INTO publisher_placements(id,website_id,name) VALUES('{placement}','{website}','isolated-browser');"
 docker('exec',database,'psql','-U','ads','-d','ads','-v','ON_ERROR_STOP=1','-c',sql)
 page=call('context/pages',{'websiteId':website,'url':'https://publisher.example.com/java','title':'Java practices','html':'Java spring jvm developer tools and code.'},'publisher')['id']
 with tempfile.TemporaryDirectory(prefix='ads-render-fixture-') as temp:
  fixture=Path(temp)/'fixture.json';fixture.write_text(json.dumps({'api':api,'advertiserToken':tokens['advertiser'],'publisherToken':tokens['publisher'],'adminToken':tokens['admin'],'pageId':page,'placementId':placement,'campaignId':campaign,'creativeId':creative,'headline':headline}));os.chmod(fixture,0o600)
  env=os.environ.copy();env['ISOLATION_FIXTURE']=str(fixture);env['TEST_WEB_URL']=publisher_origin
  subprocess.run(['npx','playwright','test','-c','isolation.config.ts','--reporter=line'],cwd=root/'frontend',env=env,check=True)
 print('PASS: disposable real serving, S3 image, sandbox isolation, signed viewability, redirect and revocation workflows')
finally:
 publisher_server.shutdown();publisher_server.server_close()
 for name in reversed(names):subprocess.run(['docker','rm','-fv',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
