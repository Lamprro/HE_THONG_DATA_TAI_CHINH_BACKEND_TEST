from pathlib import Path
import sys
import json
import numpy as np
import pandas as pd
root=Path(sys.argv[1]).resolve();(root/'reports').mkdir(exist_ok=True);d=pd.read_csv(root/'data/regime-features/samples.csv');raw=pd.read_csv(root/'data/snapshot/02_prices.csv');sources=pd.read_csv(root/'data/snapshot/01_sources.csv').set_index('id');raw['provider']=raw.data_source_id.map(sources.code);calendar=raw.groupby('trading_date').ticker.nunique();calendar=calendar[calendar>=12].index;pos={x:i for i,x in enumerate(calendar)};lookup={key:x.sort_values('trading_date') for key,x in raw.groupby(['ticker','provider'])};errors=[]
for cutoff,origins in d.groupby('cutoff'):
 rs={}
 for _,r in origins.iterrows():
  prices=lookup[(r.ticker,r.provider)];prices=prices[prices.trading_date<=cutoff].tail(253).copy();ret=prices.adjusted_close.pct_change();indices=prices.trading_date.map(pos);valid=indices.diff().eq(1);rs[r.ticker]=pd.Series(ret[valid].to_numpy(),index=prices.loc[valid,'trading_date'])
 for _,r in origins.iterrows():
  peers=origins[origins.ticker!=r.ticker];matrix=pd.concat([rs[t].rename(t) for t in peers.ticker],axis=1).sort_index();counts=matrix.count(axis=1);basket=matrix.mean(axis=1)[counts>=12];cp=np.array([pos[x] for x in basket.index]);gaps=np.flatnonzero(np.diff(cp)!=1)
  if len(gaps):basket=basket.iloc[gaps[-1]+1:]
  assert basket.index[-1]==calendar[calendar<=cutoff][-1]
  expected=[np.prod(1+basket.iloc[-20:].to_numpy())-1,np.prod(1+basket.iloc[-60:].to_numpy())-1,np.log1p(basket.iloc[-60:]).std(ddof=1)*np.sqrt(252)]
  actual=[r.market_return20,r.market_return60,r.market_vol60];np.testing.assert_allclose(expected,actual,rtol=0,atol=1e-12);errors.append(float(np.max(np.abs(np.array(expected)-actual))))
  breadth=peers.price_to_ma50.dropna();np.testing.assert_allclose(float((breadth>0).mean()),r.market_breadth_ma50,rtol=0,atol=1e-14);assert r.market_peer_count==len(peers)
receipt=dict(status='PASS',independent_origins=len(errors),independent_formulae=['market_return20','market_return60','market_vol60','market_breadth_ma50','market_peer_count'],max_error=max(errors),source='same-provider adjusted price paths and available peers at each cutoff; target symbol excluded',provider_mock=False)
(root/'reports/independent_market_verification.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(json.dumps(receipt))
