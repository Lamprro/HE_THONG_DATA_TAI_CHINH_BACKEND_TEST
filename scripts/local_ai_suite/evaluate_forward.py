"""Evaluate issued forecasts only after target maturity, using verified Java datasets of a later run."""
import argparse,json
from datetime import datetime,timezone
from pathlib import Path
import pandas as pd
from train import dump,sha,now

def main(issued,forecast,observed,out):
 payload=json.loads(issued.read_text());assert payload['schema']=='local_ai.suite.batch.output.v1';plan=json.loads((observed/'suite_plan_before_training.json').read_text());snapshot=json.loads((observed/'snapshot_manifest.json').read_text())
 for name,digest in snapshot['files'].items():
  if sha(observed/'data/snapshot'/name)!=digest:raise ValueError('OBSERVED_SNAPSHOT_INTEGRITY')
 if payload['package_sha256']!=sha(forecast/'runtime_package/registry.json'):raise ValueError('FORECAST_PACKAGE_IDENTITY')
 today=pd.Timestamp.now(tz='Asia/Ho_Chi_Minh').date();observed_day=min(pd.Timestamp(plan['business_date']).date(),today);issued_at=pd.Timestamp(payload['issued_at']).tz_convert('Asia/Ho_Chi_Minh');rows=[]
 if issued_at>pd.Timestamp.now(tz='Asia/Ho_Chi_Minh'):raise ValueError('FUTURE_ISSUANCE')
 for record in payload['results']:
  ticker=record['ticker']
  for model_id,pred in record['predictions'].items():
   expected=json.loads((forecast/'models'/model_id/'training'/(payload['role']+'.manifest.json')).read_text())
   if pred['model_sha256']!=expected['sha256']:raise ValueError('FORECAST_ARTIFACT_IDENTITY')
   period=pd.Period(pred['target_period'],freq='Q');end=period.end_time.date();window_start=(pd.Period(pred['cutoff'],freq='Q')+1).start_time
   # H2-H4 measure the cumulative return from the SAME origin. Issuance before
   # the target quarter starts is insufficient: the earlier quarters are part of the horizon.
   base=dict(ticker=ticker,model=model_id,cutoff=pred['cutoff'],target_period=pred['target_period'],target_end=str(end),forecast_window_start=str(window_start.date()),artifact_sha256=pred['model_sha256'],issued_at=payload['issued_at'],forecast_kind='FULL_HORIZON_FORWARD' if issued_at.tz_localize(None)<=window_start else 'PARTIAL_HORIZON_RESEARCH')
   if end>observed_day:rows.append(base|dict(status='PENDING_TARGET_MATURITY'));continue
   if issued_at.date()>end:rows.append(base|dict(status='RETROSPECTIVE_ISSUANCE_EXCLUDED'));continue
   d=pd.read_csv(observed/'models'/model_id/'dataset.csv');match=d[(d.ticker==ticker)&(d.cutoff==pred['cutoff'])&(d.target_period==pred['target_period'])]
   if len(match)!=1 or pd.isna(match.iloc[0].label):rows.append(base|dict(status='INSUFFICIENT_REALIZED_DATA'));continue
   r=match.iloc[0];original=pd.read_csv(forecast/'models'/model_id/'dataset.csv');original=original[(original.ticker==ticker)&(original.cutoff==pred['cutoff'])]
   if len(original)!=1 or original.iloc[0].provider!=r.provider:rows.append(base|dict(status='SOURCE_PROVIDER_CHANGED'));continue
   if r.input_hash!=pred['input_hash']:base['origin_revised_since_issuance']=True
   kind=json.loads((observed/'models'/model_id/'feature_contract.json').read_text())['task'];row=base|dict(status='REALIZED_CURRENT_VINTAGE_RESEARCH',actual=float(r.label),label_available_at=r.label_available_at,observed_snapshot=snapshot['snapshot_time'])
   if kind=='h3':row['correct']=(pred['direction']=='UP')==bool(r.label)
   elif kind=='quantile':row['coverage']=pred['return_p10']<=r.label<=pred['return_p90'];row['pinball_loss']=sum(max(q*(r.label-pred[k]),(q-1)*(r.label-pred[k])) for q,k in zip([.1,.5,.9],['return_p10','return_p50','return_p90']))/3
   else:row['absolute_error']=abs(pred['prediction']-r.label)
   rows.append(row)
 out.mkdir(parents=True,exist_ok=False);pd.DataFrame(rows).to_csv(out/'outcomes.csv',index=False);dump(out/'receipt.json',dict(created_at=now(),issued_sha256=sha(issued),observed_snapshot_manifest_sha256=sha(observed/'snapshot_manifest.json'),status_counts=pd.Series([r['status'] for r in rows]).value_counts().to_dict(),rows=len(rows),business_DB_writes=0,production_eligible=False));print('FORWARD_EVALUATION_COMPLETED rows='+str(len(rows)))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--issued',type=Path,required=True);p.add_argument('--forecast-run',type=Path,required=True);p.add_argument('--observed-run',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();main(a.issued,a.forecast_run,a.observed_run,a.out)
