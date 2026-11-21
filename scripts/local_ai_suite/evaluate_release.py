"""Audit issued Spring forecasts against later verified snapshots; never invent outcomes."""
import argparse,hashlib,json
from pathlib import Path
import pandas as pd

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,x):Path(p).write_text(json.dumps(x,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')

def main(release,issued,market,financial,out):
    manifest=json.loads((release/'release.json').read_text());digest=sha(release/'release.json');specs={m['id']:m for m in manifest['models']}
    source=json.loads(issued.read_text());now=pd.Timestamp.now(tz='UTC');snapshot=json.loads((market/'snapshot_manifest.json').read_text())
    if json.loads((market/'status.json').read_text())['status']!='VERIFIED_RESEARCH_PACKAGE':raise ValueError('OBSERVED_MARKET_NOT_VERIFIED')
    for spec in manifest['models']:
        if spec['kind']=='npat':continue
        folder=market/'models'/spec['id'];plan=json.loads((folder/'training/plan.json').read_text())
        if sha(folder/'dataset.csv')!=plan['dataset_sha256']:raise ValueError('OBSERVED_DATASET_HASH_MISMATCH')
    for name,value in snapshot['files'].items():
        if sha(market/'data/snapshot'/name)!=value:raise ValueError('OBSERVED_MARKET_HASH_MISMATCH')
    market_asof=pd.Timestamp(snapshot['snapshot_time']).tz_convert('UTC')
    financial_asof=pd.Timestamp((financial/'data/snapshot_time.txt').read_text()).tz_convert('UTC')
    plan=json.loads((financial/'training/plan_before_fit.json').read_text());fact_file=financial/'data/financial_disclosure_facts.csv'
    if sha(fact_file)!=plan['data_sha256']:raise ValueError('OBSERVED_FINANCIAL_HASH_MISMATCH')
    facts=pd.read_csv(fact_file);facts['available']=pd.to_datetime(facts.available_at,utc=True)
    if max(market_asof,financial_asof)>now:raise ValueError('FUTURE_OBSERVED_SNAPSHOT')
    rows=[]
    for item in source:
        audit=item['audit'];r=item['result']['response'];model=r['model_id'];spec=specs[model];pred=r['result'];role=r['role']
        if audit['status']!='SUCCEEDED' or not audit['checks']['passed'] or r['manifest_sha256']!=digest or r['package_id']!=manifest['package_id'] or pred['model_sha256']!=spec['artifacts'][role]['sha256']:raise ValueError('ISSUED_FORECAST_IDENTITY')
        issued_at=pd.Timestamp(r['issued_at']).tz_convert('UTC');period=pd.Period(pred['target_period'],freq='Q');end=period.end_time.tz_localize('Asia/Ho_Chi_Minh').tz_convert('UTC')
        if issued_at>now:raise ValueError('FUTURE_ISSUANCE')
        row=dict(run_id=item['result']['runId'],model=model,ticker=r['ticker'],role=role,target_period=str(period),issued_at=r['issued_at'],forecast_timing=pred['forecast_timing'],artifact_sha256=pred['model_sha256'])
        observed=financial_asof if spec['kind']=='npat' else market_asof
        if issued_at>end:row['status']='AFTER_TARGET_END_EXCLUDED_FROM_FORWARD'
        elif end>observed:row['status']='PENDING_TARGET_MATURITY'
        elif spec['kind']=='npat':
            matches=facts[(facts.symbol==r['ticker'])&(facts.target_code=='NET_PROFIT_AFTER_TAX')&(facts.period_end==str(period.end_time.date()))&(facts.report_scope=='CONSOLIDATED')&(facts.period_basis=='STANDALONE_QUARTER')&(facts.currency=='VND')&(facts.unit_scale==1)&(facts.available<=observed)].sort_values(['available','source_sha256'])
            if matches.empty:row['status']='PENDING_PUBLISHED_FINANCIAL_FACT'
            else:
                first=matches.iloc[0]
                if first.available<=issued_at:row['status']='ACTUAL_ALREADY_AVAILABLE_AT_ISSUANCE_EXCLUDED'
                elif matches[matches.available==first.available].value.nunique()!=1:row['status']='CONFLICTING_FINANCIAL_VINTAGE'
                else:row.update(status='REALIZED_ARCHIVED_VINTAGE_RESEARCH',actual=float(first.value),absolute_error=abs(float(first.value)-pred['prediction_vnd']),source_fact_id=str(first.id),source_sha256=first.source_sha256,label_available_at=first.available.isoformat(),vintage_status=first.vintage_status)
        else:
            d=pd.read_csv(market/'models'/model/'dataset.csv');match=d[(d.ticker==r['ticker'])&(d.cutoff==pred['cutoff'])&(d.target_period==pred['target_period'])]
            if len(match)!=1 or pd.isna(match.iloc[0].label):row['status']='INSUFFICIENT_REALIZED_DATA'
            else:
                actual=match.iloc[0];original_path=release/'evidence/market/models'/model/'dataset.csv'
                if not original_path.exists():original_path=Path(manifest['market_source'])/'models'/model/'dataset.csv'
                original=pd.read_csv(original_path)
                origin=original[(original.ticker==r['ticker'])&(original.cutoff==pred['cutoff'])]
                if len(origin)!=1 or origin.iloc[0].provider!=actual.provider:row['status']='SOURCE_PROVIDER_CHANGED'
                elif (lambda t:t.tz_localize('Asia/Ho_Chi_Minh') if t.tzinfo is None else t)(pd.Timestamp(actual.label_available_at)).tz_convert('UTC')>observed:row['status']='PENDING_LABEL_AVAILABILITY'
                else:
                    y=float(actual.label);row.update(status='REALIZED_CURRENT_VINTAGE_RESEARCH',actual=y,provider=actual.provider,origin_revised_since_issuance=actual.input_hash!=pred['input_hash'])
                    if spec['kind']=='h3':row['correct']=(pred['direction']=='UP')==bool(y)
                    elif spec['kind']=='quantile':row.update(coverage=pred['return_p10']<=y<=pred['return_p90'],pinball_loss=sum(max(q*(y-pred[k]),(q-1)*(y-pred[k])) for q,k in zip([.1,.5,.9],['return_p10','return_p50','return_p90']))/3)
                    else:row['absolute_error']=abs(pred['prediction']-y)
        rows.append(row)
    out.mkdir(parents=True,exist_ok=False);pd.DataFrame(rows).to_csv(out/'outcomes.csv',index=False)
    receipt=dict(status='PASS',rows=len(rows),status_counts=pd.Series([r['status'] for r in rows]).value_counts().to_dict(),issued_file_sha256=sha(issued),release_sha256=digest,market_manifest_sha256=sha(market/'snapshot_manifest.json'),financial_data_sha256=sha(fact_file),evaluated_at=now.isoformat(),business_DB_writes=0,production_eligible=False,replace_llm=False)
    write(out/'receipt.json',receipt);print(json.dumps(receipt))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--release',type=Path,required=True);p.add_argument('--issued',type=Path,required=True);p.add_argument('--market',type=Path,required=True);p.add_argument('--financial',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();main(a.release,a.issued,a.market,a.financial,a.out)
