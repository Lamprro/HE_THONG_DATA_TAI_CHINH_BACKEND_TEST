"""Walk-forward training. A selected model is a development choice, never a release approval."""
import hashlib,json,pickle
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
import pandas as pd
from threadpoolctl import threadpool_limits
from estimators import configs,fit,predict,history
from training_helpers import choose,metrics,score,threshold,dump

ROLES=['selected','challenger','baseline']

def selection_rows(oof,development_start,first_retrospective_origin):
 # Hyperparameter selection is also a learned decision. Labels must already be
 # mature at the FIRST retrospective origin, including multi-quarter horizons.
 return oof[(oof.target_end>=development_start)&(oof.target_end<first_retrospective_origin)].copy()
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def now():return datetime.now(timezone.utc).isoformat()
def load(folder):
 contract=json.loads((folder/'feature_contract.json').read_text());d=pd.read_csv(folder/'dataset.csv')
 if d.sample_id.duplicated().any():raise ValueError('DUPLICATE_SAMPLE')
 if np.isinf(d[contract['features']].to_numpy(float)).any():raise ValueError('INFINITE_FEATURE')
 if not (d.loc[d.label.notna(),'label_available_at']<=d.loc[d.label.notna(),'target_end']).all():raise ValueError('LABEL_DATE')
 return d,contract

def train_one(folder,experiment):
 d,contract=load(folder);kind=contract['task'];out=folder/'training';out.mkdir(exist_ok=False)
 base=experiment['configs'];variants=[]
 for c in base:
  for policy in (['FIXED'] if c.get('baseline') else ['FIXED','PAST_OOF8']) if kind=='h3' else [None]:
   variants.append(c|dict(fit_id=c['id'],policy=policy,id=c['id']+'_'+policy) if policy else c)
 retrospective=d[d.label.notna()&(d.target_end>experiment['development_end'])&(d.target_end<=experiment['fit_as_of'])]
 if retrospective.empty:raise ValueError('NO_RETROSPECTIVE_ORIGIN')
 first_retrospective_origin=retrospective.cutoff.min()
 plan=dict(experiment,variants=variants,first_retrospective_origin=first_retrospective_origin,selection_label_end_exclusive=first_retrospective_origin,started_at=now(),dataset_sha256=sha(folder/'dataset.csv'),contract_sha256=sha(folder/'feature_contract.json'),production_eligible=False)
 dump(out/'plan.json',plan);m=d[d.label.notna()];dev_end=experiment['development_end'];pre=m[m.target_end<=dev_end];raw=[];audit=[]
 def fit_audited(c,t,stage,cutoff):
  h=history(c,t)
  if h.empty or not (h.target_end<cutoff).all():raise ValueError('FUTURE_TRAIN_LABEL')
  model=fit(c,t,kind);audit.append(dict(stage=stage,cutoff=cutoff,config=c['id'],rows=len(h),max_train_label_end=h.target_end.max()))
  return model
 for cutoff,x in pre.groupby('cutoff',sort=True):
  t=m[m.target_end<cutoff]
  if len(t)<80 or t.cutoff.nunique()<6:continue
  for c in base:
   model=fit_audited(c,t,'pre_development',cutoff);z=score(x,model,c|dict(policy='FIXED'),kind,pre.iloc[:0]);z['fit_id']=c['id'];raw.append(z)
 if not raw:raise ValueError('INSUFFICIENT_WALK_FORWARD_DATA')
 raw=pd.concat(raw,ignore_index=True);raw.to_csv(out/'pre2025_predictions.csv',index=False);parts=[]
 for c in variants:
  stream=raw[raw.fit_id==c.get('fit_id',c['id'])]
  for cutoff,x in stream.groupby('cutoff',sort=True):
   z=x.copy();z['config']=c['id']
   if kind=='h3':
    th,receipt=threshold(c,stream,cutoff);z['threshold']=th;z['direction']=(z.prediction>=th).astype(int);z['calibration_rows']=receipt['rows'];z['max_calibration_label_end']=receipt['max_label_end']
   parts.append(z)
 oof=pd.concat(parts,ignore_index=True);dev=selection_rows(oof,experiment['development_start'],first_retrospective_origin)
 if dev.empty:raise ValueError('NO_CAUSAL_SELECTION_ROWS')
 dev.to_csv(out/'development_predictions.csv',index=False)
 dump(out/'selection_time_audit.json',dict(first_retrospective_origin=first_retrospective_origin,max_selection_label_end=dev.target_end.max(),excluded_late_target_quarters=sorted(oof.loc[(oof.target_end>=first_retrospective_origin)&(oof.target_end>=experiment['development_start']),'target_period'].unique()),rule='Every label used to select a recipe strictly precedes the first retrospective origin'))
 selection,scores=choose(dev,variants,kind);dump(out/'selection_before_retrospective.json',selection|dict(locked_at=now()));pd.DataFrame([dict(config=k,**v) for k,v in scores.items()]).to_csv(out/'development_metrics.csv',index=False)
 print(folder.name+' SELECTION '+json.dumps(selection),flush=True)
 nested=[]
 for cutoff,x in dev.groupby('cutoff',sort=True):
  known=dev[dev.target_end<cutoff]
  if known.cutoff.nunique()<8:continue
  pick,_=choose(known,variants,kind)
  for role in ROLES:
   z=x[x.config==pick[role]].copy();z['role']=role;nested.append(z)
 if nested:pd.concat(nested).to_csv(out/'nested_predictions.csv',index=False)
 latest=d[d.cutoff==experiment['fit_as_of']]
 if latest.empty:raise ValueError('NO_ORIGIN_AT_REQUESTED_CUTOFF')
 ret=[];latest_parts=[]
 for role in ROLES:
  c=next(c for c in variants if c['id']==selection[role]);stream=raw[raw.fit_id==c.get('fit_id',c['id'])].copy()
  for cutoff,x in m[(m.target_end>dev_end)&(m.target_end<=experiment['fit_as_of'])].groupby('cutoff',sort=True):
   t=m[m.target_end<cutoff];model=fit_audited(c,t,'retrospective',cutoff);z=score(x,model,c,kind,stream);z['role']=role;ret.append(z);stream=pd.concat([stream,z],ignore_index=True)
  cutoff=experiment['fit_as_of'];t=m[m.target_end<cutoff];model=fit_audited(c,t,'final',cutoff);h=history(c,t);th,cal=threshold(c,stream,cutoff) if kind=='h3' else (None,None)
  obj=dict(task=kind,model_id=folder.name,config=c,model=model,features=contract['features'],schema=contract['schema'],feature_version=contract['feature_version'],fit_as_of=cutoff,horizon_quarters=contract['horizon_quarters'],threshold=th,calibration=cal,max_train_label_end=h.target_end.max(),required_features=contract.get('required_features'),stacking=contract.get('stacking',False),production_eligible=False,replace_llm=False)
  path=out/(role+'.pkl');path.write_bytes(pickle.dumps(obj,protocol=5));loaded=pickle.loads(path.read_bytes());np.testing.assert_allclose(predict(model,latest),predict(loaded['model'],latest),rtol=0,atol=1e-12)
  dump(out/(role+'.manifest.json'),{k:v for k,v in obj.items() if k!='model'}|dict(sha256=sha(path),created_at=now()))
  z=score(latest,model,c,kind,stream);z['role']=role;latest_parts.append(z)
 rp=pd.concat(ret,ignore_index=True);rp.to_csv(out/'retrospective_predictions.csv',index=False);pd.concat(latest_parts).to_csv(out/'latest_predictions.csv',index=False);pd.DataFrame(audit).to_csv(out/'fit_maturity_audit.csv',index=False)
 summary=dict(model_id=folder.name,kind=kind,horizon_quarters=contract['horizon_quarters'],origins=len(d),matured=len(m),latest_rows=len(latest),selection=selection,development={role:scores[selection[role]] for role in ROLES},retrospective={role:metrics(rp[rp.role==role],kind) for role in ROLES},nested={role:metrics(pd.concat(nested).query('role==@role'),kind) for role in ROLES} if nested else {},production_eligible=False,replace_llm=False)
 dump(out/'summary.json',summary);return summary

def make_experiment(kind,fit_as_of):
 return dict(configs=configs(kind),development_start='2020-01-01',development_end='2024-12-31',fit_as_of=fit_as_of,knowledge_rule='Training and OOF threshold labels strictly end before each cutoff; no random split',gates='Direction BA gain >=2pp and recalls >=50%; other equal-quarter loss gain >=2%; wins >=50%; quantile coverage70-90% and nonconstant >=80%quarters',historical_exposure='ALL historical periods already explored; retrospective is NOT an untouched test',release_policy='No automatic promotion or LLM routing; development selection is research only')
