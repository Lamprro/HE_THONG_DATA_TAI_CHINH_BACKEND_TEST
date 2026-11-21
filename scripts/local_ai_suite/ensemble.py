"""Temporal stacking of fixed H1 child recipes, with a separate nested outer evaluation."""
import argparse,json,pickle
from pathlib import Path
import numpy as np
import pandas as pd
from threadpoolctl import threadpool_limits
from estimators import fit,predict,CORE6,CONTEXT,MARKET
from train import train_one,make_experiment,load,sha,dump,now

META=['child_direction','child_return','child_volatility','child_drawdown']
CHILDREN={
 'child_direction':dict(kind='h3',dataset='direction_h1',recipe=dict(id='FIXED_H1_LR15',family='LR',features=MARKET,C=.1)),
 'child_return':dict(kind='return',dataset='return_h1',recipe=dict(id='FIXED_RETURN_MEDIAN_QR',family='QR',features=CORE6+CONTEXT,alpha=.01,window=20)),
 'child_volatility':dict(kind='volatility',dataset='volatility_h1',recipe=dict(id='FIXED_VOL_QR',family='QR_SHRUNK',features=['lagged_volatility','rolling120_volatility']+CONTEXT,alpha=.001,weight=.5)),
 'child_drawdown':dict(kind='drawdown',dataset='drawdown_h1',recipe=dict(id='FIXED_DD_QR',family='QR_SHRUNK',features=['lagged_drawdown','lagged_downside_rms']+CONTEXT,alpha=.001,weight=.5)),
}
def main(root):
 folder=root/'models/ensemble_h1';folder.mkdir(exist_ok=False);datasets={k:load(root/'models'/c['dataset'])[0] for k,c in CHILDREN.items()};as_of=json.loads((root/'suite_plan_before_training.json').read_text())['fit_as_of'];experiment=make_experiment('h3',as_of)
 experiment['configs']=[dict(id='RATE',family='RATE',baseline=True),dict(id='BASE_H1_LR15',family='COLUMN',column='child_direction',baseline=True),dict(id='MOM1',family='COLUMN',column='base_momentum',baseline=True),*[dict(id=f'META_LR_C{c}',family='LR',features=META,C=c,window=20) for c in [.03,.3]]]
 dump(folder/'stacking_plan_before_fit.json',dict(created_at=now(),children=CHILDREN,meta=experiment,rule='Every child fit excludes target_end >= origin; meta fits only earlier matured child OOF rows; outer selection uses prior meta OOF only',historical_exposure='All periods exposed; not an untouched future test',dataset_sha256={k:sha(root/'models'/c['dataset']/'dataset.csv') for k,c in CHILDREN.items()},source_sha256=sha(Path(__file__))))
 common=set.intersection(*[set(d.sample_id) for d in datasets.values()]);base=datasets['child_direction'];rows=[];audit=[]
 for cutoff,x in base[base.sample_id.isin(common)].groupby('cutoff',sort=True):
  known={k:d[d.label.notna()&(d.target_end<cutoff)] for k,d in datasets.items()}
  if any(len(t)<80 or t.cutoff.nunique()<6 for t in known.values()):continue
  z=x.copy()
  for key,c in CHILDREN.items():
   t=known[key];model=fit(c['recipe'],t,c['kind']);own=datasets[key].set_index('sample_id').loc[x.sample_id];z[key]=predict(model,own)
   audit.append(dict(cutoff=cutoff,child=key,rows=len(t),max_train_label_end=t.target_end.max()))
  z['base_momentum']=(z.return_1q>0).astype(float);z['input_hash']=[__import__('hashlib').sha256((r.input_hash+'|temporal_stack_v1|'+str([r[k] for k in META])).encode()).hexdigest() for _,r in z.iterrows()];rows.append(z)
 d=pd.concat(rows,ignore_index=True);d.to_csv(folder/'dataset.csv',index=False);pd.DataFrame(audit).to_csv(folder/'child_maturity_audit.csv',index=False)
 contract=dict(schema='local_ai.suite.ensemble_h1.v1',task='h3',horizon_quarters=1,feature_version='temporal-stack-h1-v1',features=META+['base_momentum'],required_features=META+['base_momentum'],stacking=True)
 dump(folder/'feature_contract.json',contract);summary=train_one(folder,experiment)
 # Final child objects are used for a NEW request only. They never generate the OOF training table.
 final={};source_contracts={}
 for key,c in CHILDREN.items():
  t=datasets[key];t=t[t.label.notna()&(t.target_end<as_of)];final[key]=fit(c['recipe'],t,c['kind']);source_contracts[key]=json.loads((root/'models'/c['dataset']/'feature_contract.json').read_text())
 artifact=folder/'training/children.pkl';artifact.write_bytes(pickle.dumps(dict(models=final,contracts=source_contracts,recipes=CHILDREN,fit_as_of=as_of),protocol=5));dump(folder/'training/children.manifest.json',dict(sha256=sha(artifact),fit_as_of=as_of,production_eligible=False))
 summary['children_sha256']=sha(artifact);dump(folder/'training/summary.json',summary);return summary
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 with threadpool_limits(limits=1):main(a.root.resolve())
