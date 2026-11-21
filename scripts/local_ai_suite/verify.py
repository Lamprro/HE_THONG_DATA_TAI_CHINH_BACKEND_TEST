"""Independent raw-label, temporal, artifact, batch HTTP, idempotency and failure verification."""
import argparse,copy,json,os,pickle,shutil,socket,sqlite3,subprocess,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path
import numpy as np
import pandas as pd
from threadpoolctl import threadpool_limits
from estimators import fit,predict
from train import load,sha,dump,ROLES
from training_helpers import choose
from model_runtime import Predictor,InputError
from serve import Suite

def main(root):
 out=root/'verification';out.mkdir(exist_ok=False);checks=[]
 def check(name,ok=True):
  checks.append(dict(name=name,passed=bool(ok)))
  if not ok:raise AssertionError(name)
 plan=json.loads((root/'suite_plan_before_training.json').read_text())
 for name,digest in plan['source_sha256'].items():assert sha(root/'source'/name)==digest
 snapshot=json.loads((root/'snapshot_manifest.json').read_text())
 for name,digest in snapshot['files'].items():assert sha(root/'data/snapshot'/name)==digest
 check('Frozen_source_and_snapshot_integrity')
 raw=pd.read_csv(root/'data/snapshot/02_prices.csv').set_index('source_id');calendar=raw.reset_index().groupby('trading_date').ticker.nunique();calendar=calendar[calendar>=12].index;lineage=pd.read_csv(root/'data/risk-features/risk_lineage.csv').set_index('sample_id');model_ids=json.loads((root/'runtime_package/registry.json').read_text())['models'];forecast_checks=0;bad_inputs=0
 for model_id in model_ids:
  folder=root/'models'/model_id;d,contract=load(folder);kind=contract['task'];m=d[d.label.notna()];training=folder/'training';p=json.loads((training/'plan.json').read_text());check(model_id+'_frozen_dataset',p['dataset_sha256']==sha(folder/'dataset.csv') and p['contract_sha256']==sha(folder/'feature_contract.json'))
  if kind in ['h3','quantile','return']:
   for _,r in m.iterrows():
    a=raw.loc[r.label_origin_source_id];b=raw.loc[r.target_source_id];assert a.data_source_id==b.data_source_id and a.ticker==b.ticker==r.ticker;assert a.trading_date<=r.cutoff and r.cutoff<b.trading_date<=r.target_end;np.testing.assert_allclose(b.adjusted_close/a.adjusted_close-1,r.actual_return,rtol=0,atol=1e-13)
   if kind=='h3':assert ((m.actual_return>0).astype(int)==m.label).all()
  else:
   for _,r in m.iterrows():
    ids=[int(v) for v in lineage.loc[r.sample_id,'label_source_ids'].split(';')];path=raw.loc[ids];assert path.data_source_id.nunique()==1 and path.ticker.eq(r.ticker).all() and path.trading_date.is_monotonic_increasing;dates=path.trading_date.to_list();assert dates[0]<=r.cutoff and dates[1:]==[x for x in calendar if r.cutoff<x<=r.target_end];values=path.adjusted_close.to_numpy();expected=[np.std(np.diff(np.log(values)),ddof=1)*np.sqrt(252),np.max(1-values/np.maximum.accumulate(values))];np.testing.assert_allclose(expected,[r.actual_volatility_annualized,r.actual_drawdown],rtol=0,atol=1e-13)
  check(model_id+'_independent_all_matured_labels')
  audit=pd.read_csv(training/'fit_maturity_audit.csv');check(model_id+'_strict_train_maturity',(audit.max_train_label_end<audit.cutoff).all())
  dev=pd.read_csv(training/'development_predictions.csv');choice,_=choose(dev,p['variants'],kind);saved=json.loads((training/'selection_before_retrospective.json').read_text())
  for k,v in choice.items():
   if isinstance(v,float):np.testing.assert_allclose(v,saved[k],atol=1e-12,rtol=0)
   else:assert v==saved[k]
  check(model_id+'_selection_from_development_only')
  first_origin=m.loc[(m.target_end>p['development_end'])&(m.target_end<=p['fit_as_of']),'cutoff'].min()
  check(model_id+'_selection_labels_before_first_retrospective_origin',(dev.target_end<first_origin).all() and p['first_retrospective_origin']==first_origin)
  c=json.loads((training/'challenger.manifest.json').read_text())['config'];cut='2024-03-31';x=d[d.cutoff==cut];mut=m.copy();mut.loc[mut.target_end>=cut,'label']=0 if kind=='h3' else 99
  with threadpool_limits(limits=1):np.testing.assert_allclose(predict(fit(c,m[m.target_end<cut],kind),x),predict(fit(c,mut[mut.target_end<cut],kind),x),atol=1e-12,rtol=0)
  check(model_id+'_future_label_invariance')
  latest=d[d.cutoff==plan['fit_as_of']];saved_latest=pd.read_csv(training/'latest_predictions.csv')
  for role in ROLES:
   engine=Predictor(folder,role)
   for _,r in latest.iterrows():
    req=dict(schema=contract['schema'],feature_version=contract['feature_version'],ticker=r.ticker,cutoff=r.cutoff,input_hash=r.input_hash,horizons=[contract['horizon_quarters']],features={f:None if pd.isna(r[f]) else float(r[f]) for f in contract['features']});got=engine.run(req);expected=saved_latest[(saved_latest.role==role)&(saved_latest.ticker==r.ticker)].iloc[0];forecast_checks+=1
    if kind=='quantile':np.testing.assert_allclose([got['return_p10'],got['return_p50'],got['return_p90']],[expected.p10,expected.p50,expected.p90],atol=1e-12,rtol=0)
    else:np.testing.assert_allclose(got['score_up' if kind=='h3' else 'prediction'],expected.prediction,atol=1e-12,rtol=0)
    assert got['target_period']==str(pd.Period(req['cutoff'],freq='Q')+contract['horizon_quarters'])
   check(model_id+'_'+role+'_pkl_offline_parity')
  invalid=[]
  for k,v in [('schema','bad'),('ticker','?'),('input_hash','bad'),('cutoff','2099-03-31'),('horizons',[True]),('horizons',[1,3])]:
   bad=copy.deepcopy(req);bad[k]=v;invalid.append(bad)
  for v in [True,float('inf'),float('nan'),'1']:
   bad=copy.deepcopy(req);bad['features'][contract['features'][0]]=v;invalid.append(bad)
  bad=copy.deepcopy(req);bad['features']['future_label']=99;invalid.append(bad)
  for bad in invalid:
   try:engine.run(bad);raise AssertionError('Invalid accepted '+model_id)
   except InputError:bad_inputs+=1
  check(model_id+'_invalid_contract_rejected')
 if 'ensemble_h1' in model_ids:
  a=pd.read_csv(root/'models/ensemble_h1/child_maturity_audit.csv');check('Stack_all_child_predictions_OOF',(a.max_train_label_end<a.cutoff).all());nested=pd.read_csv(root/'models/ensemble_h1/training/nested_predictions.csv');check('Stack_nested_outer_evaluation_exists',len(nested)>0)
 # Actual package subprocess, actual loopback HTTP. The provider is the real fitted pickle.
 runtime=root/'runtime_package';request=json.loads((runtime/'example_request.json').read_text());tickers=pd.read_csv(runtime/'models/direction_h1/latest_features.csv').ticker.to_list();hashes={p.relative_to(runtime).as_posix():sha(p) for p in runtime.rglob('*.pkl')};http_calls=0;all_responses={}
 for role in ROLES:
  ledger=out/(role+'.sqlite');engine=Suite(runtime,role);token=os.urandom(32).hex();env=os.environ.copy();env['LOCAL_AI_RESEARCH_TOKEN']=token
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  proc=subprocess.Popen([sys.executable,str(runtime/'serve.py'),'--root',str(runtime),'--role',role,'--ledger',str(ledger),'--port',str(port)],env=env,stdout=subprocess.DEVNULL,stderr=(out/(role+'-http.log')).open('w'),creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  def call(body,auth=True):
   headers={'Content-Type':'application/json'}
   if auth:headers['Authorization']='Bearer '+token
   try:
    with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}/predict',data=json.dumps(body).encode(),headers=headers),timeout=25) as response:return response.status,json.load(response)
   except urllib.error.HTTPError as e:return e.code,json.load(e)
  try:
   ready=False
   for _ in range(100):
    try:ready=call(request,False)[0]==401;break
    except (OSError,urllib.error.URLError):time.sleep(.1)
   check(role+'_HTTP_requires_auth',ready);responses=[]
   for ticker in tickers:
    req=request|dict(tickers=[ticker],request_id=str(uuid.uuid4()));status,got=call(req);assert status==200,got;direct=engine.run(req)
    assert got['results']==direct['results'];responses.append(got);http_calls+=1
   check(role+'_HTTP_all_models_and_symbols_match',True)
   cached=call(req);assert cached==(200,got);check(role+'_idempotent_exact_response_cache')
   bad=req|dict(tickers=[tickers[0]]);assert bad['tickers']!=req['tickers'];check(role+'_idempotency_conflict',call(bad)[0]==400)
   for changes in [dict(cutoff='2099-03-31'),dict(tickers=['HPG']),dict(tickers=['FPT','FPT']),dict(request_id='invalid')]:check(role+'_HTTP_invalid_'+next(iter(changes)),call(request|changes)[0]==400)
   with sqlite3.connect(ledger) as db:
    statuses=dict(db.execute('SELECT status,count(*) FROM local_research_runs GROUP BY status').fetchall());check(role+'_ledger_success_cache_rejections',statuses==dict(SUCCEEDED=len(tickers),CACHED=1,INPUT_REJECTED=5));check(role+'_ledger_no_running',db.execute("SELECT count(*) FROM local_research_runs WHERE status='RUNNING'").fetchone()[0]==0)
   all_responses[role]=responses
  finally:
   proc.terminate()
   try:proc.wait(timeout=10)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
 check('Predict_does_not_train_or_mutate_pkl',hashes=={p.relative_to(runtime).as_posix():sha(p) for p in runtime.rglob('*.pkl')})
 tamper=out/'tampered';shutil.copytree(runtime,tamper);p=next(tamper.rglob('selected.pkl'));p.write_bytes(p.read_bytes()+b'bad')
 try:Suite(tamper);raise AssertionError('Tampered package accepted')
 except ValueError as e:check('Package_tamper_rejected',str(e)=='PACKAGE_INTEGRITY')
 dump(out/'http_predictions.json',all_responses);receipt=dict(status='PASS',completed_at=__import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat(),checks=checks,offline_pkl_predictions=forecast_checks,invalid_model_inputs=bad_inputs,real_HTTP_requests=http_calls,model_predictions_via_HTTP=http_calls*len(model_ids),provider_mock=False,new_Spring_integration=False,business_DB_writes=0,processes_stopped=True);dump(out/'receipt.json',receipt);print(json.dumps({k:v for k,v in receipt.items() if k!='checks'}));return receipt
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args();main(a.root.resolve())
