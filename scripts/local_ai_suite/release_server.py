"""Unified trusted runtime. Spring supplies the immutable registered DB feature snapshot."""
import argparse,hashlib,hmac,json,os,sys,time
from datetime import datetime,timezone
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
class Release:
 def __init__(self,root):
  self.root=Path(root).resolve();self.manifest=json.loads((self.root/'release.json').read_text(encoding='utf-8'));self.digest=sha(self.root/'release.json')
  for name,digest in self.manifest['files'].items():
   p=(self.root/name).resolve()
   if not p.is_relative_to(self.root) or sha(p)!=digest:raise ValueError('RELEASE_INTEGRITY')
  sys.path.insert(0,str(self.root/'market'));sys.path.insert(0,str(self.root/'financial'))
  market=[m for m in self.manifest['models'] if m['kind'] not in {'npat','rank'}]
  self.models={}
  if market:
   from model_runtime import Predictor
   self.models={(m['id'],role):Predictor(self.root/'market/models'/m['id'],role) for m in market for role in ['selected','challenger','baseline']}
  self.rank=None
  if any(m['kind']=='rank' for m in self.manifest['models']):
   from predict_rank import load_bundle,format_batch
   bundle,rows,identity=load_bundle(self.root/'rank')
   scored=format_batch(bundle,rows)
   self.rank=(scored,{item['ticker']:item for item in scored['results']},identity['model_sha256'])
   self.rank_risk=json.loads((self.root/'rank/risk.json').read_text(encoding='utf-8')) if 'rank/risk.json' in self.manifest['files'] else {}
  self.specs={m['id']:m for m in self.manifest['models']};self.inputs={(r['model_id'],r['ticker']):r for r in self.manifest['inputs']}
 def run(self,r):
  if not isinstance(r,dict) or set(r)!={'schema','package_id','manifest_sha256','model_id','role','ticker','input_sha256','input'} or r['schema']!='local_ai.registered.input.v1':raise ValueError('REQUEST_SCHEMA')
  if r['package_id']!=self.manifest['package_id'] or r['manifest_sha256']!=self.digest:raise ValueError('PACKAGE_IDENTITY')
  key=(r['model_id'],r['ticker']);spec=self.specs.get(r['model_id']);stored=self.inputs.get(key)
  if not spec or not stored or r['role'] not in ['selected','challenger','baseline']:raise ValueError('UNREGISTERED_INPUT')
  digest=hashlib.sha256(canonical(r['input']).encode()).hexdigest()
  if digest!=r['input_sha256'] or digest!=stored['input_sha256']:raise ValueError('FEATURE_SNAPSHOT_MISMATCH')
  if spec['kind']=='npat':
   from predict_npat import run
   # A validated immutable snapshot is used; no caller-supplied path or pickle is accepted.
   result=run(self.root/'financial/training'/f'h{spec["horizon"]}'/(r['role']+'.pkl'),self.root/stored['input_file'])
   result['feature_as_of']=result.pop('issued_at',r['input']['as_of'])
   result['input_hash']=r['input_sha256'];result['model_sha256']=spec['artifacts'][r['role']]['sha256']
  elif spec['kind']=='rank':
   if r['role']!='selected':raise ValueError('UNREGISTERED_ROLE')
   batch,results,artifact=self.rank
   result=dict(results[r['ticker']],schema=batch['schema'],cutoff=batch['cutoff'],
    feature_version=batch['feature_version'],universe_hash=batch['universe_hash'],universe_size=batch['universe_size'],
    model_sha256=artifact,target_period=spec['target_period'],
    volatility_h1=None,drawdown_h1=None,risk_status='UNAVAILABLE_FOR_THIS_SNAPSHOT',
    production_eligible=False,replace_llm=False)
   risk=self.rank_risk.get('rows',{}).get(r['ticker'])
   if risk and self.rank_risk['cutoff']==batch['cutoff'] and self.rank_risk['target_period']==spec['target_period']:
    result.update(volatility_h1=risk['volatility_h1']['value'],drawdown_h1=risk['drawdown_h1']['value'],
     risk_status='SEPARATE_RESEARCH_SNAPSHOT',risk_manifest_sha256=self.rank_risk['source_manifest_sha256'],
     risk_provenance=risk)
  else:result=self.models[(r['model_id'],r['role'])].run(r['input'])
  import pandas as pd
  issued=datetime.now(timezone.utc).isoformat();at=pd.Timestamp(issued).tz_convert('Asia/Ho_Chi_Minh');target=pd.Period(spec['target_period'],freq='Q');end=target.end_time.tz_localize('Asia/Ho_Chi_Minh')
  start=(target.start_time if spec['kind']=='npat' else (pd.Period(r['input']['cutoff'],freq='Q')+1).start_time).tz_localize('Asia/Ho_Chi_Minh')
  timing=('NOWCAST_AFTER_QUARTER_END' if spec['kind']=='npat' else 'RETROSPECTIVE_ISSUANCE') if at>end else ('PARTIAL_HORIZON_RESEARCH' if at>start else 'FULL_HORIZON_FORWARD')
  result['forecast_timing']=timing
  return dict(schema='local_ai.registered.output.v1',package_id=r['package_id'],manifest_sha256=self.digest,model_id=r['model_id'],role=r['role'],ticker=r['ticker'],input_sha256=digest,issued_at=issued,data_as_of=stored['data_as_of'],execution_mode='REGISTERED_SNAPSHOT_RESEARCH',result=result,production_eligible=False,replace_llm=False)
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--port',type=int,default=8794);a=p.parse_args();engine=Release(a.root);token=os.environ.get('LOCAL_AI_SUITE_TOKEN','')
 if len(token)<32:raise ValueError('TOKEN_MIN_32')
 class Handler(BaseHTTPRequestHandler):
  def setup(self):super().setup();self.connection.settimeout(20)
  def log_message(self,*args):pass
  def send(self,status,obj):
   data=canonical(obj).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
  def do_GET(self):
   if self.path!='/health':return self.send(404,{'error':'NOT_FOUND'})
   self.send(200,dict(status='READY',manifest_sha256=engine.digest,models=len(engine.specs),production_eligible=False))
  def do_POST(self):
   if self.path!='/v1/predict':return self.send(404,dict(error='NOT_FOUND'))
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):return self.send(401,dict(error='UNAUTHORIZED'))
   try:
    n=int(self.headers.get('Content-Length','0'))
    if self.headers.get('Transfer-Encoding') or not 0<n<=100000:raise ValueError('BODY_SIZE')
    raw=self.rfile.read(n)
    if len(raw)!=n:raise ValueError('INCOMPLETE_BODY')
    r=json.loads(raw,parse_constant=lambda x:(_ for _ in ()).throw(ValueError('NONFINITE_JSON')));reply=engine.run(r)
   except (ValueError,TypeError,KeyError):return self.send(400,dict(error='INVALID_REGISTERED_INPUT'))
   except Exception:return self.send(500,dict(error='INFERENCE_FAILED'))
   self.send(200,reply)
 http=ThreadingHTTPServer(('127.0.0.1',a.port),Handler)
 try:http.serve_forever()
 finally:http.server_close()
if __name__=='__main__':main()
