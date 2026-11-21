"""Compare paired forecasts by quarter, security and current known retrospective exposure."""
import argparse,json
from pathlib import Path
import numpy as np
import pandas as pd
from train import dump,sha,now
from training_helpers import metrics

def row_loss(z,kind):
 if kind=='h3':return (z.label!=z.direction).astype(float).to_numpy()
 if kind=='quantile':return np.column_stack([np.maximum(q*(z.label-z[k]),(q-1)*(z.label-z[k])) for q,k in zip([.1,.5,.9],['p10','p50','p90'])]).mean(axis=1)
 return abs(z.label-z.prediction).to_numpy()
def main(root):
 out=root/'reports';out.mkdir(exist_ok=True);summaries=json.loads((root/'suite_summary.json').read_text());rows=[];details=[];decisions=[];rng=np.random.default_rng(42)
 for s in summaries:
  model_id=s['model_id'];kind=s['kind'];folder=root/'models'/model_id;z=pd.read_csv(folder/'training/retrospective_predictions.csv');a=z[z.role=='challenger'].copy().set_index('sample_id');b=z[z.role=='baseline'].set_index('sample_id').loc[a.index];assert a.label.equals(b.label);a['loss']=row_loss(a,kind);b['loss']=row_loss(b,kind);quarters=sorted(a.cutoff.unique());boot=[]
  for _ in range(2000):
   qs=rng.choice(quarters,len(quarters),replace=True)
   if kind=='h3':
    aa=pd.concat([a[a.cutoff==q] for q in qs]);bb=pd.concat([b[b.cutoff==q] for q in qs]);boot.append(metrics(aa,kind)['balanced_accuracy']-metrics(bb,kind)['balanced_accuracy'])
   else:boot.append(np.mean([a.loc[a.cutoff==q,'loss'].mean()-b.loc[b.cutoff==q,'loss'].mean() for q in qs]))
  ci=np.quantile(boot,[.025,.975]).tolist();recent_a=s['retrospective']['challenger'];recent_b=s['retrospective']['baseline'];key='balanced_accuracy' if kind=='h3' else 'pooled_loss';advantage=recent_a[key]-recent_b[key] if kind=='h3' else recent_b['equal_quarter_loss']-recent_a['equal_quarter_loss'];significant=ci[0]>0 if kind=='h3' else ci[1]<0
  stage='SHADOW_CANDIDATE' if s['selection']['gate_pass'] and advantage>0 and model_id=='drawdown_h1' else 'RESEARCH_ONLY'
  reason='Promising paired development and retrospective risk error; CI contains0; forward and Spring target integration pending' if stage=='SHADOW_CANDIDATE' else 'Development gate failed' if not s['selection']['gate_pass'] else 'Retrospective weak, class imbalance or risk underestimation; no forward certification'
  rows.append(dict(model=model_id,horizon=s['horizon_quarters'],algorithm=s['selection']['challenger'],selected=s['selection']['selected'],baseline=s['selection']['baseline'],development_gain=s['selection']['development_gain'],development_gate=s['selection']['gate_pass'],retrospective_n=recent_a['n'],retrospective_quarters=recent_a['quarters'],metric=key,challenger=recent_a[key],baseline_value=recent_b[key],equal_quarter_challenger=recent_a.get('equal_quarter_loss'),equal_quarter_baseline=recent_b.get('equal_quarter_loss'),recall_not_up=recent_a.get('recall_not_up'),recall_up=recent_a.get('recall_up'),ci_low=ci[0],ci_high=ci[1],status=stage))
  for axis in ['target_period','ticker']:
   for name,g in a.groupby(axis):
    other=b.loc[g.index];r=dict(model=model_id,group_by=axis,group=name,n=len(g),challenger_loss=float(g.loss.mean()),baseline_loss=float(other.loss.mean()))
    if kind=='h3':
     # BA is undefined if a group has only one true class; preserve null.
     r['balanced_accuracy']=metrics(g,kind)['balanced_accuracy'] if g.label.nunique()==2 else None;r['recall_not_up']=float((g[g.label==0].direction==0).mean()) if (g.label==0).any() else None;r['recall_up']=float((g[g.label==1].direction==1).mean()) if (g.label==1).any() else None
    elif kind=='quantile':r['coverage']=float(((g.label>=g.p10)&(g.label<=g.p90)).mean())
    else:r['underestimation_fraction']=float((g.prediction<g.label).mean());r['underestimation_mae']=float(np.maximum(g.label-g.prediction,0).mean())
    details.append(r)
  decisions.append(dict(model=model_id,status=stage,reason=reason,ci_quarter_blocks=ci,ci_excludes_zero=significant,ci_not_selection_criterion=True,production_eligible=False,replace_llm=False,new_Spring_target_integration=False))
 pd.DataFrame(rows).to_csv(out/'model_comparison.csv',index=False);pd.DataFrame(details).to_csv(out/'errors_by_quarter_and_ticker.csv',index=False);dump(out/'release_decisions.json',decisions)
 # Exact matched-cohort comparison against the previous market-regime experiment.
 previous=root.parent/'market-regime-20261020';comparisons=[]
 for current,old in [('direction_h3','h3'),('quantile_h1','quantile'),('volatility_h1','volatility'),('drawdown_h1','drawdown')]:
  if not previous.exists():continue
  aa=pd.read_csv(root/'models'/current/'training/retrospective_predictions.csv').query("role=='challenger'").set_index('sample_id');bb=pd.read_csv(previous/'models'/old/'training/retrospective_predictions.csv').query("role=='challenger'").set_index('sample_id');common=aa.index.intersection(bb.index);a,b=aa.loc[common],bb.loc[common];kind='h3' if old=='h3' else old;label_same=np.isclose(a.label,b.label,atol=1e-12,rtol=0);paired=common[label_same];a,b=aa.loc[paired],bb.loc[paired]
  comparisons.append(dict(model=current,new_rows=len(aa),previous_rows=len(bb),common_identical_labels=len(paired),changed_labels=int((~label_same).sum()),new_only=len(aa.index.difference(bb.index)),old_only=len(bb.index.difference(aa.index)),new_metrics=metrics(a,kind),previous_metrics=metrics(b,kind),interpretation='Shared backend provider policy changes cohort/features; only these matched identical-label rows are directly paired'))
 dump(out/'paired_previous_comparison.json',comparisons)
 dump(out/'forward_evaluation_plan.json',dict(locked_at=now(),first_full_H1_target='2027Q1',first_origin='2026-12-31',requirements=['Freeze model/config before forecasting; actual issuance no later than horizon window start (next quarter after origin) for ALL H1-H4, not merely the target-quarter start','Store real call, artifact/source/input hashes and issuance time','Mature target and full same-provider path before scoring; no synthetic outcomes','Compare paired fixed baseline by quarter; report abstentions and bias/recall','This run uses exposed history; no claims of untouched future accuracy'],minimum_target_quarters_for_review=8,promotion='Explicit review only; never automatic based on development gate',production_eligible=False))
 verification=json.loads((root/'verification/receipt.json').read_text());market=json.loads((out/'independent_market_verification.json').read_text());future=json.loads((root/'verification_future/receipt.json').read_text());assert all(r['status']=='PASS' for r in [verification,market,future])
 dump(root/'status.json',dict(status='VERIFIED_RESEARCH_PACKAGE',completed_at=now(),model_count=len(summaries),pkl_files=len(list((root/'runtime_package').rglob('*.pkl'))),production_eligible=False,replace_llm=False,data_snapshot=(root/'data/snapshot/snapshot_time.txt').read_text().strip(),fresh_DB_refresh=False if (root/'replay_provenance.json').exists() else True))
 print(pd.DataFrame(rows)[['model','development_gain','challenger','baseline_value','status']].to_string(index=False))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args();main(a.root.resolve())
