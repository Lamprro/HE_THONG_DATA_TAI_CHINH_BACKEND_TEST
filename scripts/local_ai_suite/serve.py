"""Loopback research batch API over immutable frozen features; SQLite audit is NOT the business DB."""
import argparse,hashlib,hmac,json,os,pickle,re,sqlite3,time,uuid
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import pandas as pd
from estimators import predict
from model_runtime import Predictor,InputError

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':'),allow_nan=False)
def now():return datetime.now(timezone.utc).isoformat()
class Suite:
 def __init__(self,root,role='selected',ledger=None):
  self.root=Path(root);self.role=role;self.ledger=Path(ledger) if ledger else None
  registry=json.loads((self.root/'registry.json').read_text());self.registry=registry
  # Files are trusted project artifacts. A checksum is an integrity check, not a sandbox for untrusted pickle.
  for name,digest in registry['runtime_files'].items():
   p=(self.root/name).resolve()
   if not p.is_relative_to(self.root.resolve()) or sha(p)!=digest:raise ValueError('PACKAGE_INTEGRITY')
  self.package_hash=sha(self.root/'registry.json');self.engines={};self.tables={}
  for model_id in registry['models']:
   folder=self.root/'models'/model_id;self.engines[model_id]=Predictor(folder,role)
   self.tables[model_id]=pd.read_csv(folder/'latest_features.csv').set_index('ticker')
  self.children=None
  if 'ensemble_h1' in self.engines:
   self.children=pickle.loads((self.root/'models/ensemble_h1/training/children.pkl').read_bytes())
  if self.ledger:
   self.ledger.parent.mkdir(parents=True,exist_ok=True)
   with self.connect() as db:db.executescript('''
CREATE TABLE IF NOT EXISTS local_research_runs(run_id TEXT PRIMARY KEY,request_id TEXT,input_hash TEXT,status TEXT,created_at TEXT,finished_at TEXT,error_code TEXT,latency_ms REAL,response_json TEXT);
CREATE INDEX IF NOT EXISTS research_request ON local_research_runs(request_id);
CREATE TABLE IF NOT EXISTS local_research_cache(request_id TEXT PRIMARY KEY,input_hash TEXT NOT NULL,response_json TEXT NOT NULL);
''')
 def connect(self):
  db=sqlite3.connect(self.ledger,timeout=30);db.execute('PRAGMA busy_timeout=30000');return db
 def validate(self,r):
  if not isinstance(r,dict) or set(r)!={'schema','request_id','cutoff','tickers'} or r['schema']!='local_ai.suite.batch.v1':raise InputError('BATCH_SCHEMA')
  try:
   if str(uuid.UUID(r['request_id']))!=r['request_id']:raise ValueError()
  except (ValueError,TypeError,AttributeError):raise InputError('REQUEST_ID')
  if r['cutoff']!=self.registry['fit_as_of']:raise InputError('FROZEN_CUTOFF')
  tickers=r['tickers']
  if not isinstance(tickers,list) or not 1<=len(tickers)<=100 or any(not isinstance(t,str) or not re.fullmatch('[A-Z0-9]{2,12}',t) for t in tickers) or len(set(tickers))!=len(tickers):raise InputError('TICKERS')
  for ticker in tickers:
   if any(ticker not in table.index for table in self.tables.values()):raise InputError('INCOMPLETE_MODEL_COVERAGE_'+ticker)
 def infer(self,r):
  results=[]
  for ticker in r['tickers']:
   record=dict(ticker=ticker,predictions={})
   for model_id,engine in self.engines.items():
    row=self.tables[model_id].loc[ticker];obj=engine.obj
    if model_id=='ensemble_h1':
     derived={}
     for key,entry in self.children['recipes'].items():
      source=self.tables[entry['dataset']].loc[[ticker]];derived[key]=float(predict(self.children['models'][key],source)[0])
     derived['base_momentum']=float(self.tables['direction_h1'].loc[ticker,'return_1q']>0)
     # The final children must reproduce the latest OOF pipeline at this frozen origin.
     for k,v in derived.items():
      if abs(v-float(row[k]))>1e-12:raise ValueError('STACK_CHILD_PARITY')
    fs={f:None if pd.isna(row[f]) else float(row[f]) for f in obj['features']}
    req=dict(schema=obj['schema'],feature_version=obj['feature_version'],ticker=ticker,cutoff=r['cutoff'],input_hash=row.input_hash,horizons=[obj['horizon_quarters']],features=fs)
    record['predictions'][model_id]=engine.run(req)
   results.append(record)
  return dict(schema='local_ai.suite.batch.output.v1',request_id=r['request_id'],cutoff=r['cutoff'],issued_at=now(),execution_mode='FROZEN_SNAPSHOT_RESEARCH',issued_after_origin=True,snapshot_time=self.registry['snapshot_time'],package_sha256=self.package_hash,role=self.role,production_eligible=False,replace_llm=False,results=results)
 def run(self,r):
  start=time.perf_counter();started_at=now();run_id=str(uuid.uuid4());request_id=r.get('request_id') if isinstance(r,dict) and isinstance(r.get('request_id'),str) else None;digest=None;response=None;status='INPUT_REJECTED';error=None
  # BEGIN IMMEDIATE serializes the idempotency key across HTTP worker threads.
  db=self.connect() if self.ledger else None
  try:
   if db:
    db.execute('INSERT INTO local_research_runs VALUES(?,?,?,?,?,?,?,?,?)',(run_id,request_id,None,'RUNNING',started_at,None,None,None,None));db.commit();db.execute('BEGIN IMMEDIATE')
   self.validate(r);digest=hashlib.sha256((canonical(r)+'|'+self.package_hash+'|'+self.role).encode()).hexdigest()
   if db:
    prior=db.execute('SELECT input_hash,response_json FROM local_research_cache WHERE request_id=?',(request_id,)).fetchone()
    if prior:
     if prior[0]!=digest:raise InputError('IDEMPOTENCY_CONFLICT')
     response=json.loads(prior[1]);status='CACHED'
   if response is None:
    status='PROVIDER_FAILED';response=self.infer(r);status='SUCCEEDED'
    if db:db.execute('INSERT INTO local_research_cache VALUES(?,?,?)',(request_id,digest,canonical(response)))
   return response
  except InputError as e:status='INPUT_REJECTED';error=str(e);raise
  except Exception:status='PROVIDER_FAILED';error='INFERENCE_FAILED';raise
  finally:
   if db:
    db.execute('UPDATE local_research_runs SET input_hash=?,status=?,finished_at=?,error_code=?,latency_ms=?,response_json=? WHERE run_id=?',(digest,status,now(),error,(time.perf_counter()-start)*1000,canonical(response) if response else None,run_id));db.commit();db.close()

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--role',choices=['selected','challenger','baseline'],default='selected');p.add_argument('--ledger',type=Path);p.add_argument('--input',type=Path);p.add_argument('--output',type=Path);p.add_argument('--port',type=int,default=8788);a=p.parse_args();engine=Suite(a.root,a.role,a.ledger)
 if a.input:
  result=engine.run(json.loads(a.input.read_text(encoding='utf-8')));out=json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)
  if a.output:a.output.write_text(out,encoding='utf-8')
  else:print(out)
  return
 token=os.environ.get('LOCAL_AI_RESEARCH_TOKEN','')
 if len(token)<32:raise ValueError('LOCAL_AI_RESEARCH_TOKEN_MIN32')
 class Handler(BaseHTTPRequestHandler):
  def setup(self):super().setup();self.connection.settimeout(15)
  def log_message(self,*args):pass
  def send(self,code,x):
   b=canonical(x).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
  def do_POST(self):
   if self.path!='/predict':return self.send(404,dict(error='NOT_FOUND'))
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):return self.send(401,dict(error='UNAUTHORIZED'))
   try:
    if self.headers.get('Transfer-Encoding'):raise InputError('TRANSFER_ENCODING')
    size=int(self.headers.get('Content-Length','0'))
    if not 0<size<=65536:raise InputError('SIZE')
    raw=self.rfile.read(size)
    if len(raw)!=size:raise InputError('BODY')
    r=json.loads(raw,parse_constant=lambda _:(_ for _ in ()).throw(InputError('JSON_NUMBER')))
    result=engine.run(r)
   except (ValueError,TypeError,UnicodeError) as e:return self.send(400,dict(error=str(e) if isinstance(e,InputError) else 'INVALID_JSON'))
   except Exception:return self.send(500,dict(error='INFERENCE_FAILED'))
   self.send(200,result)
 http=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);http.timeout=15
 try:http.serve_forever()
 finally:http.server_close()
if __name__=='__main__':main()
