"""Frozen read-only database snapshot -> shared Java features -> bounded walk-forward train."""
import argparse,hashlib,json,os,platform,shutil,subprocess,sys,traceback
from datetime import date,datetime,timezone
from pathlib import Path
import numpy as np
import pandas as pd
import sklearn,scipy
from threadpoolctl import threadpool_limits
from estimators import MARKET,RISK
from train import train_one,make_experiment,dump,sha,now

HERE=Path(__file__).resolve().parent
def command(args,log):
 with log.open('w',encoding='utf-8') as stream:subprocess.run([str(a) for a in args],stdout=stream,stderr=subprocess.STDOUT,check=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
def java(root,args,name):command(['java','-Xmx1500m','-cp',root/'build',*args],root/(name+'.log'))
def snapshot(root,backend,config,jdbc,prior=None):
 build=root/'build';build.mkdir();source=root/'source'
 for name in ['LocalAiCsv','QuarterlyMarketFeatures','RankMarketFeatures']:
  shutil.copy2(backend/f'src/main/java/com/hethongdata/taichinh/service/localai/{name}.java',source/'java'/f'{name}.java')
 command(['javac','-d',build,*sorted((source/'java').glob('*.java'))],root/'compile.log')
 if prior:
  proof={k.replace('\\','/'):v for k,v in json.loads((prior/'models/h3/training/plan.json').read_text())['data_sha256'].items()};dest=root/'data/snapshot';dest.mkdir(parents=True)
  for name in ['01_securities.csv','01_sources.csv','02_prices.csv','03_indices.csv','snapshot_time.txt']:
   p=prior/'data/snapshot'/name
   if sha(p)!=proof['snapshot/'+name]:raise ValueError('PRIOR_SNAPSHOT_SHA_MISMATCH')
   shutil.copy2(p,dest/name)
  for name in ['11_financial_eligibility.csv','14_actual_counts.csv','16_key_quality.csv']:shutil.copy2(prior/'audit-before'/name,dest/name)
  dump(root/'replay_provenance.json',dict(mode='EXPLICIT_FROZEN_REPLAY',original_snapshot_time=(dest/'snapshot_time.txt').read_text().strip(),audit_time=(prior/'audit-before/snapshot_time.txt').read_text().strip(),audit_sha256={name:sha(dest/name) for name in ['11_financial_eligibility.csv','14_actual_counts.csv','16_key_quality.csv']},current_db_not_refreshed=True,reason='Fresh DB attempt SQLSTATE08001 and TCP timeout on2026-10-21; explicit replay, never silent fallback'))
  (root/'snapshot.log').write_text('READ_ONLY_EXPORT_COMPLETED FROZEN_REPLAY_VERIFIED\n',encoding='utf-8')
 else:command(['java','-cp',str(build)+os.pathsep+str(jdbc),'CanonicalReadOnly',config,source/'queries',root/'data/snapshot'],root/'snapshot.log')
 if 'READ_ONLY_EXPORT_COMPLETED' not in (root/'snapshot.log').read_text():raise ValueError('INCOMPLETE_EXPORT')
 dump(root/'snapshot_manifest.json',dict(completed_at=now(),snapshot_time=(root/'data/snapshot/snapshot_time.txt').read_text().strip(),files={p.name:sha(p) for p in (root/'data/snapshot').iterdir()},read_only=True))
def features(root,business_date):
 for h in [1,2,3,4]:java(root,['ReturnDataset',root/'data/snapshot',root/('data/base-features' if h==1 else f'data/h{h}'),business_date,str(h)],f'features-h{h}')
 for name in ['EnrichFeatures','LongFeatures','RiskDataset','VolatilityFeatures','RegimeFeatures']:
  java(root,[name,root,*([business_date] if name=='RiskDataset' else [])],name)
def prepare_models(root,as_of):
 data=root/'data';market=pd.read_csv(data/'regime-features/samples.csv');risk=pd.read_csv(data/'regime-risk/samples.csv');regime=json.loads((data/'regime-features/feature_names.json').read_text());tasks={}
 for model_id,kind,h in [*( (f'direction_h{h}','h3',h) for h in [1,2,3,4]),('return_h1','return',1),('quantile_h1','quantile',1),('volatility_h1','volatility',1),('drawdown_h1','drawdown',1)]:
  fs=(RISK if kind in ['volatility','drawdown'] else MARKET)+regime;d=(risk if kind in ['volatility','drawdown'] else market).copy()
  if kind=='h3' and h>1:
   labels=pd.read_csv(data/f'h{h}/samples.csv').set_index('sample_id');cols=['target_period','target_end','label_available_at','actual_return','label_status','label_origin_close','target_close','label_origin_source_id','target_source_id','label_source_hash']
   for c in cols:d[c]=d.sample_id.map(labels[c])
  d['label']=(d.actual_return>0).astype(float) if kind=='h3' else d.actual_return if kind in ['return','quantile'] else d['actual_volatility_annualized' if kind=='volatility' else 'actual_drawdown']
  if kind=='h3':d.loc[d.actual_return.isna(),'label']=np.nan
  folder=root/'models'/model_id;folder.mkdir(parents=True);d.to_csv(folder/'dataset.csv',index=False)
  contract=dict(schema=f'local_ai.suite.{model_id}.v1',task=kind,horizon_quarters=h,feature_version='quarterly-risk-regime-java-v1' if kind in ['volatility','drawdown'] else 'market-regime-java-v1',features=fs)
  dump(folder/'feature_contract.json',contract);tasks[model_id]=make_experiment(kind,as_of)
 return tasks
def readiness(root):
 data=root/'data/snapshot';f=pd.read_csv(data/'11_financial_eligibility.csv').iloc[0].to_dict();quality=pd.read_csv(data/'16_key_quality.csv');securities=pd.read_csv(data/'01_securities.csv');features=pd.read_csv(root/'data/regime-features/samples.csv');latest=features[features.cutoff==features.cutoff.max()]
 if (quality.duplicate_groups>0).any():raise ValueError('SOURCE_DUPLICATE_KEYS')
 report=dict(financial={k:None if pd.isna(v) else v.item() if hasattr(v,'item') else v for k,v in f.items()},financial_training_status='BLOCKED_SOURCE_METADATA' if f['strict_pit_items']==0 else 'REQUIRES_TARGET_COVERAGE_REVIEW',financial_targets=['NET_PROFIT_AFTER_TAX','PRETAX_PROFIT','TOTAL_ASSETS','OWNERS_EQUITY','LIABILITIES'],news_training_status='BLOCKED_NO_REVIEWED_LABELSET',macro_status='EXCLUDED_NO_VERIFIED_RELEASE_VINTAGES',universe=len(securities),latest_eligible=len(latest),excluded_symbols=sorted(set(securities.ticker)-set(latest.ticker)),limitations=['Current-vintage price adjustments and present25-symbol universe; not certified historical PIT universe','Research basket not official index; source/date provenance retained','No automatic provider calls or invented metadata; source corrections must go through Spring ingestion validation and builder'])
 dump(root/'data_readiness.json',report)
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--backend',type=Path,default=HERE.parents[1]);p.add_argument('--config',type=Path);p.add_argument('--jdbc',type=Path,required=True);p.add_argument('--snapshot-from',type=Path);p.add_argument('--business-date',default=date.today().isoformat());a=p.parse_args();root=a.out.resolve();root.mkdir(parents=True,exist_ok=False);backend=a.backend.resolve();config=a.config or backend/'application-local.properties'
 try:
  shutil.copytree(HERE,root/'source',ignore=shutil.ignore_patterns('__pycache__'));dump(root/'status.json',dict(status='RUNNING',started_at=now()))
  # The latest completed quarter is the decision cutoff; partial-quarter data is not a completed origin.
  business=pd.Timestamp(a.business_date);as_of=str((business.to_period('Q')-1).end_time.date())
  snapshot(root,backend,config,a.jdbc.resolve(),a.snapshot_from.resolve() if a.snapshot_from else None);features(root,a.business_date);readiness(root);tasks=prepare_models(root,as_of)
  plan=dict(created_at=now(),business_date=a.business_date,fit_as_of=as_of,tasks=tasks,source_sha256={str(p.relative_to(root/'source')):sha(p) for p in (root/'source').rglob('*') if p.is_file()},snapshot_sha256=sha(root/'snapshot_manifest.json'),production_eligible=False,replace_llm=False,runtime=dict(python=platform.python_version(),numpy=np.__version__,pandas=pd.__version__,sklearn=sklearn.__version__,scipy=scipy.__version__))
  dump(root/'suite_plan_before_training.json',plan);summaries=[]
  with threadpool_limits(limits=1):
   for model_id,experiment in tasks.items():summaries.append(train_one(root/'models'/model_id,experiment))
  from ensemble import main as train_ensemble
  with threadpool_limits(limits=1):summaries.append(train_ensemble(root))
  dump(root/'suite_summary.json',summaries);dump(root/'status.json',dict(status='TRAINED_AWAITING_VERIFICATION',completed_at=now(),production_eligible=False));print('SUITE_TRAINING_COMPLETED',flush=True)
 except Exception as e:
  dump(root/'status.json',dict(status='FAILED',at=now(),error_type=type(e).__name__,message=str(e)));raise
if __name__=='__main__':main()
