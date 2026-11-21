import argparse,hashlib,hmac,json,math,os,pickle,re
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import pandas as pd
from estimators import predict
class InputError(ValueError):pass
class Predictor:
 def __init__(self,root,role='selected'):
  root=Path(root);folder=root/'training';self.manifest=json.loads((folder/(role+'.manifest.json')).read_text());raw=(folder/(role+'.pkl')).read_bytes()
  if hashlib.sha256(raw).hexdigest()!=self.manifest['sha256']:raise ValueError('ARTIFACT_HASH_MISMATCH')
  self.obj=pickle.loads(raw);self.contract=json.loads((root/'feature_contract.json').read_text())
  for key in ['features','schema','feature_version']:
   if self.obj[key]!=self.contract[key] or self.obj[key]!=self.manifest[key]:raise ValueError('ARTIFACT_CONTRACT')
 def run(self,r):
  obj=self.obj
  if not isinstance(r,dict) or set(r)!={'schema','feature_version','ticker','cutoff','input_hash','horizons','features'}:raise InputError('FIELDS')
  if r['schema']!=obj['schema'] or r['feature_version']!=obj['feature_version']:raise InputError('SCHEMA')
  if not isinstance(r['ticker'],str) or not re.fullmatch('[A-Z0-9]{2,12}',r['ticker']):raise InputError('TICKER')
  if not isinstance(r['input_hash'],str) or not re.fullmatch('[0-9a-f]{64}',r['input_hash']):raise InputError('HASH')
  if r['cutoff']!=obj['fit_as_of']:raise InputError('FROZEN_CUTOFF')
  if r['horizons']!=[obj['horizon_quarters']] or type(r['horizons'][0])!=int:raise InputError('HORIZON')
  fs=r['features']
  if not isinstance(fs,dict) or set(fs)!=set(obj['features']):raise InputError('FEATURES')
  for k,v in fs.items():
   if v is not None and (type(v) not in [int,float] or not math.isfinite(v)):raise InputError('VALUE')
  required=obj.get('required_features') or (['return_1q','volatility_1q','price_to_ma50'] if obj['task'] in ['h3','quantile','return'] else ['lagged_drawdown','lagged_volatility','return_1q'])
  if any(fs[f] is None for f in required):raise InputError('MISSING_REQUIRED')
  for key in ['return_1q','return_2q','return_4q','return_8q','price_to_ma20','price_to_ma50','price_to_ma200','return_last5','return_last20']:
   if fs.get(key) is not None and fs[key]<=-1:raise InputError('RETURN_RANGE')
  for key in ['volatility_1q','downside_volatility_1q','log_avg_volume_1q','close_range_1q','lagged_volatility','lagged_drawdown','lagged_downside_rms','rolling5_volatility','rolling20_volatility','rolling120_volatility','rolling252_volatility','ewma97_volatility']:
   if fs.get(key) is not None and fs[key]<0:raise InputError('MAGNITUDE')
  if fs.get('positive_day_fraction') is not None and not 0<=fs['positive_day_fraction']<=1:raise InputError('FRACTION')
  if fs.get('max_drawdown_1q') is not None and not -1<=fs['max_drawdown_1q']<=0:raise InputError('DRAWDOWN')
  if fs.get('lagged_drawdown') is not None and fs['lagged_drawdown']>1:raise InputError('DRAWDOWN')
  for key in ['market_breadth_ma50','market_breadth_ma200','market_breadth_return1q','market_stress_fraction60','market_drawdown60']:
   if fs.get(key) is not None and not 0<=fs[key]<=1:raise InputError('MARKET_FRACTION')
  for key in ['market_vol20','market_vol60','market_vol120','market_downside20','market_vol_ratio','market_dispersion1q','market_daily_dispersion20']:
   if fs.get(key) is not None and fs[key]<0:raise InputError('MARKET_RISK')
  for key in ['market_return20','market_return60','market_return120']:
   if fs.get(key) is not None and fs[key]<=-1:raise InputError('MARKET_RETURN')
  if obj.get('stacking'):
   if not 0<=fs['child_direction']<=1 or fs['child_return']<-1 or fs['child_volatility']<0 or not 0<=fs['child_drawdown']<=1 or fs['base_momentum'] not in [0,1]:raise InputError('META_RANGE')
  else:
   states=[fs['market_state_'+s] for s in ['stress','bull','bear','neutral','unknown']]
   if any(v not in [0,1] for v in states) or sum(states)!=1:raise InputError('MARKET_STATE')
   if type(fs['market_peer_count']) not in [int,float] or fs['market_peer_count']<12 or fs['market_peer_count']%1:raise InputError('MARKET_PEERS')
   if type(fs['market_sessions120']) not in [int,float] or not 0<=fs['market_sessions120']<=120 or fs['market_sessions120']%1:raise InputError('MARKET_SESSIONS')
  x=pd.DataFrame([{f:float('nan') if fs[f] is None else fs[f] for f in obj['features']}]);p=predict(obj['model'],x)[0]
  result=dict(schema=obj['schema']+'.output',ticker=r['ticker'],cutoff=r['cutoff'],input_hash=r['input_hash'],horizon_quarters=obj['horizon_quarters'],target_period=str(pd.Period(r['cutoff'],freq='Q')+obj['horizon_quarters']),task=obj.get('model_id',obj['task']),config=obj['config']['id'],model_sha256=self.manifest['sha256'],production_eligible=False,replace_llm=False)
  if obj['task']=='h3':result.update(score_up=float(p),direction='UP' if p>=obj['threshold'] else 'NOT_UP',threshold=obj['threshold'],probability_calibrated=False,comparison_basis='TARGET_ENDPOINT_VERSUS_ORIGIN_ENDPOINT')
  elif obj['task']=='quantile':result.update(return_p10=float(p[0]),return_p50=float(p[1]),return_p90=float(p[2]),return_unit='FRACTION',quantile_levels=[.1,.5,.9],coverage_calibrated=False)
  elif obj['task']=='return':result.update(prediction=float(p),units='ADJUSTED_PRICE_RETURN_FRACTION')
  else:result.update(prediction=float(p),units='ANNUALIZED_LOG_RETURN_VOLATILITY' if obj['task']=='volatility' else 'MAX_CLOSE_DRAWDOWN_FRACTION',annualization=252 if obj['task']=='volatility' else None)
  return result
def main():
 a=argparse.ArgumentParser();a.add_argument('--root',type=Path,required=True);a.add_argument('--role',choices=['selected','challenger','baseline'],default='selected');a.add_argument('--port',type=int,default=8780);a.add_argument('--input',type=Path);a.add_argument('--output',type=Path);args=a.parse_args();engine=Predictor(args.root,args.role)
 if args.input:
  result=engine.run(json.loads(args.input.read_text(encoding='utf-8')));text=json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)
  if args.output:args.output.write_text(text,encoding='utf-8')
  else:print(text)
  return
 token=os.environ.get('LOCAL_AI_RESEARCH_TOKEN','')
 if len(token)<32:raise ValueError('LOCAL_AI_RESEARCH_TOKEN requires32characters')
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def send(self,code,obj):
   b=json.dumps(obj,allow_nan=False).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
  def do_POST(self):
   if self.path!='/predict':return self.send(404,dict(error='NOT_FOUND'))
   if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):return self.send(401,dict(error='UNAUTHORIZED'))
   try:
    n=int(self.headers.get('Content-Length','0'))
    if not 0<n<=65536:raise InputError('SIZE')
    result=engine.run(json.loads(self.rfile.read(n)))
   except (ValueError,TypeError,IndexError):return self.send(400,dict(error='INVALID_INPUT'))
   self.send(200,result)
 ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
if __name__=='__main__':main()
