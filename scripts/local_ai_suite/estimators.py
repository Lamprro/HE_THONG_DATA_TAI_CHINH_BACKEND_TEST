"""Causal small tabular models. No ticker identity, future target or external I/O in prediction."""
import numpy as np
from sklearn.base import BaseEstimator,TransformerMixin
from sklearn.pipeline import Pipeline
from sklearn.impute import SimpleImputer
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression,QuantileRegressor,Ridge
from sklearn.svm import SVC
from sklearn.ensemble import ExtraTreesClassifier,HistGradientBoostingRegressor

MARKET=['return_1q','return_2q','return_4q','volatility_1q','downside_volatility_1q','log_avg_volume_1q','volume_growth_1q','price_to_ma20','price_to_ma50','price_to_ma200','max_drawdown_1q','close_range_1q','positive_day_fraction','return_last5','return_last20']
CORE=['return_1q','return_2q','return_4q','price_to_ma50','price_to_ma200','volatility_1q','max_drawdown_1q','volume_growth_1q']
LONG=CORE+['return_8q','prior_return_2q','acceleration_2q','trend_2q_minus_4q','risk_scaled_return_2q','risk_scaled_return_4q']
CORE6=['return_1q','return_2q','volatility_1q','price_to_ma50','max_drawdown_1q','log_avg_volume_1q']
HAR=['rolling5_volatility','rolling20_volatility','lagged_volatility','rolling120_volatility','rolling252_volatility']
RISK=HAR+['ewma97_volatility','lagged_drawdown','lagged_downside_rms','return_1q','max_drawdown_1q','volume_growth_1q']
QLEVELS=[.1,.5,.9]
def scale(x):return np.clip(x.volatility_1q.to_numpy(float)*np.sqrt(63),.03,1.)
class TrainClip(TransformerMixin,BaseEstimator):
 def fit(self,X,y=None):
  a=np.asarray(X,float);self.low_=np.array([np.nanquantile(c,.01) if np.isfinite(c).any() else -np.inf for c in a.T]);self.high_=np.array([np.nanquantile(c,.99) if np.isfinite(c).any() else np.inf for c in a.T]);return self
 def transform(self,X):return np.clip(np.asarray(X,float),self.low_,self.high_)
def pre(est):return Pipeline([('clip',TrainClip()),('imputer',SimpleImputer(strategy='median',add_indicator=True,keep_empty_features=True)),('scale',StandardScaler()),('model',est)])
def history(c,t):return t if not c.get('window') else t[t.cutoff.isin(sorted(t.cutoff.unique())[-c['window']:])]
def old_configs(task):
 if task=='h3':
  out=[dict(id='RATE',family='RATE',baseline=True),*[dict(id='MOM'+str(k),family='MOM',column='return_'+str(k)+'q',baseline=True) for k in [1,2,4]],dict(id='BASE_LR15',family='LR',features=MARKET,C=.1,baseline=True)]
  out += [dict(id=f'LR_CORE_C{c}',family='LR',features=CORE,C=c,window=20) for c in [.03,.3]]
  out += [dict(id=f'RBF_CORE_C{c}',family='SVC',features=CORE,C=c,window=20) for c in [.3,1,3]]
  out += [dict(id=f'ET_LONG_D{depth}',family='ET',features=LONG,depth=depth,window=20) for depth in [3,5]]
  out += [dict(id='DIVERSE_BLEND',family='BLEND',window=20,features=LONG)]
  return out
 if task=='quantile':
  return [dict(id='HIST_ALL',family='HIST',baseline=True),dict(id='HIST_W20',family='HIST',window=20,baseline=True),dict(id='SCALED_HIST_W20',family='HIST',window=20,scaled=True),*[dict(id=f'SCALED_QR_A{a}',family='QR',features=CORE6,alpha=a,window=20,scaled=True) for a in [.0001,.001,.01]],dict(id='QR_CORE_A0.001',family='QR',features=CORE6,alpha=.001,window=20),*[dict(id=f'QHGB_L{leaf}',family='QHGB',features=CORE6,leaves=leaf,window=20,scaled=True) for leaf in [3,7]]]
 persist='lagged_volatility' if task=='volatility' else 'lagged_drawdown'
 out=[dict(id='MEDIAN',family='MEDIAN',baseline=True),dict(id='MEAN',family='MEAN',baseline=True),dict(id='PERSIST',family='COLUMN',column=persist,baseline=True),dict(id='SHRINK',family='SHRINK',column=persist,baseline=True)]
 if task=='volatility':out += [dict(id='EWMA97',family='COLUMN',column='ewma97_volatility',baseline=True),dict(id='ROLL252',family='COLUMN',column='rolling252_volatility',baseline=True)]
 else:out.append(dict(id='ZERO',family='ZERO',baseline=True))
 out += [dict(id=f'QR_RISK_A{a}',family='QR',features=RISK,alpha=a,window=20) for a in [.0001,.001]]
 out += [dict(id=f'LOG_RIDGE_A{a}',family='RIDGE',features=RISK,alpha=a,window=20,log=True) for a in [10,100]]
 out += [dict(id='HGB_RISK',family='HGB',features=RISK,window=20)]
 return out
def previous_configs(task):
 if task=='h3':
  base=[c for c in old_configs(task) if c.get('baseline')]
  thin=['return_1q','return_4q','price_to_ma200','volatility_1q','peer_up_fraction_4q','hnxindex_return_4q']
  return base+[dict(id='PREV_LR_CORE_C0.3',family='LR',features=CORE,C=.3,window=20),*[dict(id=f'LR_REGIME_C{c}',family='LR',features=thin,C=c,window=None) for c in [.03,.3]],*[dict(id=f'ANCHOR_LR_W{w}',family='ANCHOR',weight=w,features=CORE,window=20) for w in [.5,.75]]]
 if task=='quantile':
  return [dict(id='HIST_ALL',family='HIST',baseline=True),dict(id='HIST_W20',family='HIST',window=20,baseline=True),dict(id='PREV_SCALED_W20',family='HIST',window=20,scaled=True),*[dict(id=f'SHRUNK_Q_W{w}',family='SHRUNK_Q',weight=w) for w in [.25,.5]],*[dict(id=f'WIDTH_Q_E{e}',family='WIDTH_Q',exponent=e) for e in [.25,.5]],*[dict(id=f'KERNEL_Q_B{b}',family='KERNEL_Q',bandwidth=b,weight=.5) for b in [.5,1.]]]
 base=[c for c in old_configs(task) if c.get('baseline')];columns=['lagged_volatility','rolling120_volatility'] if task=='volatility' else ['lagged_drawdown','lagged_downside_rms']
 return base+[*[dict(id=f'BIN_{column}_B{bins}',family='BIN_MEDIAN',column=column,bins=bins,prior_n=80) for column in ['lagged_volatility','lagged_drawdown'] for bins in [3,5]],*[dict(id=f'QR_SHRUNK_W{w}',family='QR_SHRUNK',features=columns,alpha=.001,weight=w) for w in [.25,.5]],dict(id='QR_TWO_EXP',family='QR',features=columns,alpha=.001),dict(id='QR_TWO_W20',family='QR',features=columns,alpha=.001,window=20)]

STATE=[f'market_state_{k}' for k in ['stress','bull','bear','neutral','unknown']]
CONTEXT=['market_return60','market_return120','market_vol_ratio','market_breadth_ma200','market_drawdown60','market_rebound']
def state_ids(x):return np.argmax(x[STATE].to_numpy(float),axis=1)
def configs(task):
 if task=='return':
  return [dict(id='ZERO',family='ZERO',baseline=True),dict(id='MEDIAN',family='MEDIAN',baseline=True),dict(id='MEAN',family='MEAN',baseline=True),dict(id='PERSIST',family='COLUMN',column='return_1q',baseline=True),*[dict(id=f'RIDGE_A{a}',family='RIDGE',features=CORE+CONTEXT,alpha=a,window=20) for a in [10,100]],*[dict(id=f'MEDIAN_QR_A{a}',family='QR',features=CORE6+CONTEXT,alpha=a,window=20) for a in [.001,.01]],dict(id='HGB_SMALL',family='HGB',features=CORE6+CONTEXT,window=20)]
 if task=='h3':
  base=[c for c in old_configs(task) if c.get('baseline')]
  return base+[dict(id='PREV_LR_CORE_C0.3',family='LR',features=CORE,C=.3,window=20),*[dict(id=f'LR_MARKET_C{v}',family='LR',features=CONTEXT,C=v,window=20) for v in [.03,.3]],*[dict(id=f'LR_STOCK_MARKET_C{v}',family='LR',features=CORE+CONTEXT,C=v,window=20) for v in [.03,.3]],dict(id='STATE_RATE',family='STATE',prior_n=80)]
 if task=='quantile':
  return [dict(id='HIST_ALL',family='HIST',baseline=True),dict(id='HIST_W20',family='HIST',window=20,baseline=True),dict(id='PREV_SHRUNK_Q',family='SHRUNK_Q',weight=.25),*[dict(id=f'KERNEL_MARKET_B{b}',family='CONTEXT_KERNEL',features=['volatility_1q']+CONTEXT,bandwidth=b,weight=.5) for b in [.5,1]],dict(id='KERNEL_RISK_ONLY',family='CONTEXT_KERNEL',features=['volatility_1q'],bandwidth=1.,weight=.5),dict(id='STATE_QUANTILE',family='STATE',prior_n=80),dict(id='QR_STOCK_MARKET',family='QR',features=CORE6+CONTEXT,alpha=.01,scaled=True,window=20)]
 base=[c for c in old_configs(task) if c.get('baseline')];prior=next(c for c in previous_configs(task) if c['id']==('QR_SHRUNK_W0.25' if task=='volatility' else 'BIN_lagged_volatility_B3'));prior=prior|dict(id='PREV_FIXED')
 stock=['lagged_volatility','rolling120_volatility'] if task=='volatility' else ['lagged_drawdown','lagged_downside_rms']
 return base+[prior,*[dict(id=f'QR_MARKET_W{w}',family='QR_SHRUNK',features=stock+CONTEXT,alpha=.001,weight=w) for w in [.25,.5]],*[dict(id=f'KERNEL_MARKET_B{b}',family='CONTEXT_KERNEL',features=stock+CONTEXT,bandwidth=b,weight=.5) for b in [.5,1]],dict(id='STATE_MEDIAN',family='STATE',prior_n=80)]
def fit(c,t,task):
 t=history(c,t);y=t.label.to_numpy(float);f=c['family'];m=dict(config=c,task=task,median=float(np.median(y)),mean=float(y.mean()),n=len(t))
 if f=='STATE':
  ids=state_ids(t);m['state_values']=[];m['global_q']=np.quantile(y,QLEVELS)
  for state in range(5):
   ys=y[ids==state];n=len(ys);weight=n/(n+c['prior_n']);value=float(ys.mean()) if task=='h3' and n else np.quantile(ys,QLEVELS) if task=='quantile' and n else float(np.median(ys)) if n else m['mean'] if task=='h3' else m['global_q'] if task=='quantile' else m['median'];prior=m['mean'] if task=='h3' else m['global_q'] if task=='quantile' else m['median'];m['state_values'].append(weight*value+(1-weight)*prior)
  if task=='quantile':m['normalized_q']=np.quantile(y/scale(t),QLEVELS)
  return m
 if f=='CONTEXT_KERNEL':
  transform=Pipeline([('clip',TrainClip()),('imputer',SimpleImputer(strategy='median',add_indicator=False,keep_empty_features=True)),('scaler',StandardScaler())]);m['embedding']=transform.fit_transform(t[c['features']]);m['transform']=transform;m['targets']=y;return m
 if f=='ANCHOR':
  m['child']=fit(dict(id='anchor_child',family='LR',features=CORE,C=.3,window=20),t,task);return m
 if f in ['SHRUNK_Q','WIDTH_Q','KERNEL_Q']:
  m['global_q']=np.quantile(y,QLEVELS);m['risk_median']=float(np.median(scale(t)))
  if f=='SHRUNK_Q':m['scaled_q']=np.quantile(y/scale(t),QLEVELS)
  if f=='KERNEL_Q':m['risk_log']=np.log(scale(t));m['targets']=y
  return m
 if f=='BIN_MEDIAN':
  values=t[c['column']].to_numpy(float);m['fallback']=float(np.nanmedian(values));values=np.nan_to_num(values,nan=m['fallback']);m['edges']=np.unique(np.quantile(values,np.arange(1,c['bins'])/c['bins']));binids=np.searchsorted(m['edges'],values,side='right');m['bin_values']=[]
  for b in range(len(m['edges'])+1):
   ys=y[binids==b];n=len(ys);m['bin_values'].append((n*np.median(ys)+c['prior_n']*m['median'])/(n+c['prior_n']) if n else m['median'])
  return m
 if f=='QR_SHRUNK':
  m['child']=fit(dict(id='qr_shrink_child',family='QR',features=c['features'],alpha=c['alpha']),t,task);return m
 if f in ['HIST','RATE','MEDIAN','MEAN','ZERO','COLUMN','SHRINK','MOM']:
  if f=='HIST':m['quantiles']=np.quantile(y/scale(t) if c.get('scaled') else y,QLEVELS)
  if f in ['COLUMN','SHRINK']:m['fallback']=float(t[c['column']].median()) if t[c['column']].notna().any() else m['median']
  return m
 if f=='BLEND':
  m['children']=[fit(dict(id='BLEND_LR',family='LR',features=CORE,C=.1,window=20),t,task),fit(dict(id='BLEND_SVC',family='SVC',features=CORE,C=1,window=20),t,task),fit(dict(id='BLEND_ET',family='ET',features=LONG,depth=3,window=20),t,task)];return m
 fs=c['features'];models=[];qs=QLEVELS if task=='quantile' else [.5]
 for q in qs:
  if f=='LR':est=LogisticRegression(C=c['C'],class_weight='balanced',max_iter=4000,random_state=42)
  elif f=='SVC':est=SVC(C=c['C'],gamma='scale',class_weight='balanced',kernel='rbf')
  elif f=='ET':est=ExtraTreesClassifier(n_estimators=120,max_depth=c['depth'],min_samples_leaf=25,max_features=.8,class_weight='balanced',n_jobs=1,random_state=42)
  elif f=='QR':est=QuantileRegressor(quantile=q,alpha=c['alpha'],solver='highs')
  elif f=='RIDGE':est=Ridge(alpha=c['alpha'],solver='svd')
  else:est=HistGradientBoostingRegressor(loss='quantile' if task=='quantile' else 'absolute_error',quantile=q if task=='quantile' else None,max_leaf_nodes=c.get('leaves',5),max_iter=100,min_samples_leaf=40,l2_regularization=10,early_stopping=False,random_state=42)
  target=y/scale(t) if c.get('scaled') else np.log(np.maximum(y,.005)) if c.get('log') else y
  model=pre(est)
  if c.get('quarter_weight'):
   # Correlated companies in the same quarter must not give a denser quarter
   # extra influence. Normalize mean weight to one to preserve penalty scale.
   weights=1/t.groupby('cutoff').cutoff.transform('size').to_numpy(float);weights=weights/weights.mean()
   model.fit(t[fs],target,model__sample_weight=weights)
  else:model.fit(t[fs],target)
  models.append(model)
 m['models']=models;return m
def predict(m,x):
 c=m['config'];task=m['task'];f=c['family'];n=len(x)
 if f=='STATE':
  p=np.asarray(m['state_values'])[state_ids(x)]
  if task=='quantile':p=.75*p+.25*m['normalized_q']*scale(x)[:,None]
 elif f=='CONTEXT_KERNEL':
  embed=m['transform'].transform(x[c['features']]);parts=[];order=np.argsort(m['targets']);ys=m['targets'][order]
  for row in embed:
   dist=((m['embedding']-row)**2).mean(axis=1);weights=np.exp(-.5*dist/c['bandwidth']**2);weights=weights/weights.sum() if weights.sum()>0 else np.full(len(weights),1/len(weights));weights=c['weight']*weights+(1-c['weight'])/len(weights);w=weights[order];cdf=np.cumsum(w)-.5*w;parts.append(np.interp(QLEVELS if task=='quantile' else [.5],cdf,ys))
  p=np.asarray(parts);p=p if task=='quantile' else .5*p[:,0]+.5*(.5*x.lagged_volatility.to_numpy()+.5*m['median'] if task=='volatility' else m['median'])
 elif f=='ANCHOR':p=c['weight']*predict(m['child'],x)+(1-c['weight'])*(x.return_1q.to_numpy()>0)
 elif f=='SHRUNK_Q':p=(1-c['weight'])*m['global_q']+c['weight']*m['scaled_q']*scale(x)[:,None]
 elif f=='WIDTH_Q':
  ratio=np.clip((scale(x)/m['risk_median'])**c['exponent'],.5,2);p=m['global_q'][1]+ratio[:,None]*(m['global_q']-m['global_q'][1])
 elif f=='KERNEL_Q':
  order=np.argsort(m['targets']);ys=m['targets'][order];parts=[]
  for risk in scale(x):
   weights=np.exp(-.5*((np.log(risk)-m['risk_log'])/c['bandwidth'])**2);weights=weights/weights.sum() if weights.sum()>0 else np.full(len(weights),1/len(weights));weights=c['weight']*weights+(1-c['weight'])/len(weights);w=weights[order];cdf=np.cumsum(w)-.5*w;parts.append(np.interp(QLEVELS,cdf,ys))
  p=np.asarray(parts)
 elif f=='BIN_MEDIAN':p=np.asarray(m['bin_values'])[np.searchsorted(m['edges'],x[c['column']].fillna(m['fallback']).to_numpy(),side='right')]
 elif f=='QR_SHRUNK':
  prior=.5*x.lagged_volatility.to_numpy()+.5*m['median'] if task=='volatility' else np.full(n,m['median']);p=c['weight']*predict(m['child'],x)+(1-c['weight'])*prior
 elif f=='BLEND':p=np.mean([predict(child,x) for child in m['children']],axis=0)
 elif f=='MOM':p=(x[c['column']].to_numpy()>0).astype(float)
 elif f in ['RATE','MEAN','MEDIAN','ZERO']:p=np.full(n,m['mean'] if f in ['RATE','MEAN'] else 0 if f=='ZERO' else m['median'])
 elif f in ['COLUMN','SHRINK']:
  p=x[c['column']].fillna(m['fallback']).to_numpy();p=.5*p+.5*m['median'] if f=='SHRINK' else p
 elif f=='HIST':p=np.tile(m['quantiles'],(n,1))
 elif task=='h3':
  est=m['models'][0];p=1/(1+np.exp(-np.clip(est.decision_function(x[c['features']]),-35,35))) if f=='SVC' else est.predict_proba(x[c['features']])[:,1]
 else:p=np.column_stack([est.predict(x[c['features']]) for est in m['models']]);p=p if task=='quantile' else p[:,0]
 if c.get('scaled'):p=p*scale(x)[:,None]
 if c.get('log'):p=np.exp(np.clip(p,-12,5))
 if task=='quantile':p=np.sort(np.maximum(p,-1),axis=1)
 elif task=='drawdown':p=np.clip(p,0,1)
 elif task=='volatility':p=np.maximum(p,0)
 elif task=='return':p=np.maximum(p,-1)
 if not np.isfinite(p).all():raise ValueError('NONFINITE_OUTPUT')
 return p
