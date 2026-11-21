"""One preregistered bounded expansion on the SAME verified fresh control snapshot."""
import argparse,json,shutil
from pathlib import Path
from threadpoolctl import threadpool_limits
from estimators import configs,CORE6
from train import train_one,make_experiment,sha,dump,now

THIN=['return_2q','price_to_ma200','volatility_1q','market_return120','market_breadth_ma200']
BALANCED=CORE6+['market_return120','market_breadth_ma200','market_beta60','market_corr60','market_drawdown60','market_shock']
def additions(kind):
 if kind=='h3':return [dict(id='LR_THIN_Q_C0.03',family='LR',features=THIN,C=.03,quarter_weight=True),dict(id='LR_BALANCED_Q_C0.03_W12',family='LR',features=BALANCED,C=.03,window=12,quarter_weight=True),dict(id='LR_BALANCED_Q_C0.3_W20',family='LR',features=BALANCED,C=.3,window=20,quarter_weight=True),dict(id='RBF_BALANCED_Q_C0.3',family='SVC',features=BALANCED,C=.3,window=20,quarter_weight=True),dict(id='ET_BALANCED_Q_D3',family='ET',features=BALANCED,depth=3,window=20,quarter_weight=True)]
 if kind=='quantile':return [dict(id=f'QR_THIN_Q_A{a}',family='QR',features=THIN,alpha=a,window=20,scaled=True,quarter_weight=True) for a in [.03,.1]]
 if kind=='return':return [dict(id='RIDGE_THIN_Q_A100',family='RIDGE',features=THIN,alpha=100,quarter_weight=True),dict(id='HGB_THIN_Q_L3',family='HGB',features=THIN,leaves=3,window=20,quarter_weight=True)]
 fs=['lagged_volatility','rolling120_volatility'] if kind=='volatility' else ['lagged_drawdown','lagged_downside_rms']
 return [dict(id=f'QR_THIN_Q_A{a}',family='QR',features=fs+['market_vol_ratio','market_drawdown60'],alpha=a,window=12,quarter_weight=True) for a in [.01,.1]]

def main(control,out):
 if json.loads((control/'status.json').read_text())['status']!='VERIFIED_RESEARCH_PACKAGE':raise ValueError('CONTROL_NOT_VERIFIED')
 out.mkdir(parents=True,exist_ok=False);shutil.copytree(Path(__file__).resolve().parent,out/'source',ignore=shutil.ignore_patterns('__pycache__'))
 for folder in ['data','build']:shutil.copytree(control/folder,out/folder)
 for name in ['LocalAiCsv.java','QuarterlyMarketFeatures.java']:shutil.copy2(control/'source/java'/name,out/'source/java'/name)
 for name in ['snapshot_manifest.json','data_readiness.json']:shutil.copy2(control/name,out/name)
 plan=json.loads((control/'suite_plan_before_training.json').read_text());tasks={}
 for model_id,old in plan['tasks'].items():
  target=out/'models'/model_id;target.mkdir(parents=True)
  for name in ['dataset.csv','feature_contract.json']:shutil.copy2(control/'models'/model_id/name,target/name)
  kind=json.loads((target/'feature_contract.json').read_text())['task'];exp=make_experiment(kind,plan['fit_as_of']);exp['configs']=configs(kind)+additions(kind);tasks[model_id]=exp
 plan.update(created_at=now(),tasks=tasks,experiment='bounded_causal_v2',control_run=str(control),control_plan_sha256=sha(control/'suite_plan_before_training.json'),source_sha256={str(p.relative_to(out/'source')):sha(p) for p in (out/'source').rglob('*') if p.is_file()},hypotheses=['Thin features reduce variance','Quarter weighting handles different company counts per time block','Shorter windows handle regime drift','Low capacity nonlinear functions model stock and market interactions'],policy='One locked expansion; no parameter changes after seeing retrospective results')
 dump(out/'suite_plan_before_training.json',plan);dump(out/'replay_provenance.json',dict(mode='CONTROLLED_SAME_FRESH_SNAPSHOT',control_run=str(control),snapshot_sha256=sha(control/'snapshot_manifest.json'),current_db_not_refreshed=False));dump(out/'status.json',dict(status='RUNNING'));summaries=[]
 with threadpool_limits(limits=1):
  for model_id,exp in tasks.items():summaries.append(train_one(out/'models'/model_id,exp))
  from ensemble import main as stack
  summaries.append(stack(out))
 dump(out/'suite_summary.json',summaries);dump(out/'status.json',dict(status='TRAINED_AWAITING_VERIFICATION',production_eligible=False));print('BOUNDED_OPTIMIZATION_TRAINED')

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--control',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();main(a.control.resolve(),a.out.resolve())
