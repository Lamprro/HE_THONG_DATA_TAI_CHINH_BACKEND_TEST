"""Seal all current research tasks plus their immutable feature inputs for Spring registration."""
import argparse,hashlib,json,shutil,uuid
from pathlib import Path
from datetime import datetime,timezone
import pandas as pd
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main(market,financial,out):
 if json.loads((market/'status.json').read_text())['status']!='VERIFIED_RESEARCH_PACKAGE':raise ValueError('MARKET_NOT_VERIFIED')
 if json.loads((financial/'verification/receipt.json').read_text())['status']!='PASS':raise ValueError('FINANCIAL_NOT_VERIFIED')
 out.mkdir(parents=True,exist_ok=False);shutil.copytree(market/'runtime_package',out/'market',ignore=shutil.ignore_patterns('__pycache__','*.pyc'));(out/'financial').mkdir();shutil.copytree(financial/'training',out/'financial/training',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
 source=Path(__file__).resolve().parent
 for name in ['predict_npat.py','train_npat.py']:shutil.copy2(source.parent/'local_ai_financial'/name,out/'financial'/name)
 shutil.copy2(source/'release_server.py',out/'release_server.py');models=[];inputs=[]
 for folder in sorted((out/'market/models').iterdir()):
  contract=json.loads((folder/'feature_contract.json').read_text());summary=json.loads((folder/'summary.json').read_text());artifacts={r:json.loads((folder/'training'/(r+'.manifest.json')).read_text()) for r in ['selected','challenger','baseline']};cutoff=artifacts['selected']['fit_as_of'];h=contract['horizon_quarters'];model_id=folder.name
  models.append(dict(id=model_id,kind=contract['task'],horizon=h,target_period=str(pd.Period(cutoff,freq='Q')+h),artifacts={r:dict(sha256=m['sha256']) for r,m in artifacts.items()},selection=summary['selection'],production_eligible=False))
  for _,row in pd.read_csv(folder/'latest_features.csv').iterrows():
   payload=dict(schema=contract['schema'],feature_version=contract['feature_version'],ticker=row.ticker,cutoff=cutoff,input_hash=row.input_hash,horizons=[h],features={f:None if pd.isna(row[f]) else float(row[f]) for f in contract['features']});encoded=canonical(payload);inputs.append(dict(model_id=model_id,ticker=row.ticker,data_as_of=cutoff,input_sha256=hashlib.sha256(encoded.encode()).hexdigest(),input_payload=encoded))
 for h in [1,2]:
  folder=out/'financial/training'/f'h{h}';s=json.loads((folder/'summary.json').read_text());request=json.loads((folder/'latest_input.json').read_text());artifacts={role:dict(sha256=json.loads((folder/(role+'.manifest.json')).read_text())['sha256']) for role in ['selected','challenger','baseline']};model_id=f'npat_h{h}';models.append(dict(id=model_id,kind='npat',horizon=h,target_period=request['target_period'],artifacts=artifacts,selection=s['selection'],production_eligible=False));encoded=canonical(request);inputs.append(dict(model_id=model_id,ticker='FPT',data_as_of=request['as_of'],input_sha256=hashlib.sha256(encoded.encode()).hexdigest(),input_payload=encoded,input_file=f'financial/training/h{h}/latest_input.json'))
 files={p.relative_to(out).as_posix():sha(p) for p in out.rglob('*') if p.is_file()};release=dict(schema='local_ai.registered.release.v1',package_id=str(uuid.uuid4()),created_at=datetime.now(timezone.utc).isoformat(),models=models,inputs=inputs,files=files,production_eligible=False,replace_llm=False,scope='Immutable verified DB snapshots; separate research route, never automatic LLM replacement',market_source=str(market),financial_source=str(financial));(out/'release.json').write_text(canonical(release),encoding='utf-8');print('RELEASE',release['package_id'],'MODELS',len(models),'SNAPSHOTS',len(inputs),'PKL',len(list(out.rglob('*.pkl'))))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--market',type=Path,required=True);p.add_argument('--financial',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();main(a.market.resolve(),a.financial.resolve(),a.out.resolve())
