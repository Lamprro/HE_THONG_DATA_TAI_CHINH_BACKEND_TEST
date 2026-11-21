from pathlib import Path
import json,shutil,subprocess,sys
import numpy as np
import pandas as pd
from estimators import MARKET,RISK
root=Path(sys.argv[1]).resolve();out=root/'verification_future';snap=out/'data/snapshot';snap.mkdir(parents=True,exist_ok=True)
for name in ['01_securities.csv','01_sources.csv','snapshot_time.txt']:shutil.copy2(root/'data/snapshot'/name,snap/name)
for name,cols in [('02_prices.csv',['open','high','low','close','adjusted_close']),('03_indices.csv',['open','high','low','close'])]:
 d=pd.read_csv(root/'data/snapshot'/name,dtype=str,keep_default_na=False);mask=d.trading_date>'2023-12-31';factor=1.2+(np.arange(mask.sum())%13)*.01
 for col in cols:d.loc[mask,col]=(d.loc[mask,col].astype(float).to_numpy()*factor).astype(str)
 d.to_csv(snap/name,index=False)
names=['LocalAiCsv.java','QuarterlyMarketFeatures.java','ReturnDataset.java','EnrichFeatures.java','LongFeatures.java','RiskDataset.java','VolatilityFeatures.java','RegimeFeatures.java'];build=out/'build';build.mkdir(exist_ok=True)
subprocess.run(['javac','-d',str(build),*[str(root/'source/java'/n) for n in names]],check=True,capture_output=True)
commands=[['ReturnDataset',str(snap),str(out/'data/base-features'),'2026-10-20','1'],['EnrichFeatures',str(out)],['LongFeatures',str(out)],['RiskDataset',str(out),'2026-10-20'],['VolatilityFeatures',str(out)],['RegimeFeatures',str(out)]];logs=[]
for command in commands:logs.append(subprocess.run(['java','-Xmx1500m','-cp',str(build),*command],check=True,capture_output=True,text=True).stdout)
(out/'java.log').write_text(''.join(logs));checks=[]
for name,fs in [('features',MARKET),('regime-features',json.loads((root/'models/direction_h3/feature_contract.json').read_text())['features']),('regime-risk',json.loads((root/'models/volatility_h1/feature_contract.json').read_text())['features'])]:
 before=pd.read_csv(root/'data'/name/'samples.csv');before=before[before.cutoff<='2023-12-31'].set_index('sample_id');after=pd.read_csv(out/'data'/name/'samples.csv').set_index('sample_id').loc[before.index]
 np.testing.assert_allclose(before[fs].to_numpy(),after[fs].to_numpy(),equal_nan=True,atol=0,rtol=0);assert before.input_hash.equals(after.input_hash);checks.append(dict(dataset=name,past_origins=len(before),features=len(fs),feature_values_equal=True,input_hashes_equal=True))
receipt=dict(status='PASS',mutation='all stock/index prices after2023-12-31 changed; Java actual source rebuild',checks=checks,db_writes=0)
(out/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(json.dumps(receipt))
