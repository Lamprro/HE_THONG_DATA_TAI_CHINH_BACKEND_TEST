import argparse,hashlib,json,pickle
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
import pandas as pd
from threadpoolctl import threadpool_limits
from estimators import configs,fit,predict,history,MARKET,RISK,QLEVELS

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
def threshold(c,stream,cutoff):
 if c['policy']=='FIXED':return .5,dict(rows=0,max_label_end=None)
 known=stream[stream.target_end<cutoff];known=known[known.target_period.isin(sorted(known.target_period.unique())[-8:])];receipt=dict(rows=len(known),max_label_end=known.target_end.max() if len(known) else None)
 if min(int((known.label==i).sum()) for i in [0,1])<20:return .5,receipt
 ranks=[]
 for th in np.round(np.arange(.35,.6501,.025),3):
  r0=float((known.loc[known.label==0,'prediction']<th).mean());r1=float((known.loc[known.label==1,'prediction']>=th).mean());eligible=min(r0,r1)>=.45 and abs(r0-r1)<=.15
  ranks.append((eligible,(r0+r1)/2 if eligible else min(r0,r1),min(r0,r1),-abs(th-.5),float(th)))
 return max(ranks)[-1],receipt
def score(x,m,c,task,stream=None):
 z=x[['sample_id','ticker','cutoff','target_period','target_end','label','input_hash']].copy();p=predict(m,x)
 if task=='quantile':
  for i,k in enumerate(['p10','p50','p90']):z[k]=p[:,i]
 else:z['prediction']=p
 if task=='h3':
  th,receipt=threshold(c,stream,x.cutoff.iloc[0]);z['threshold']=th;z['direction']=(z.prediction>=th).astype(int);z['calibration_rows']=receipt['rows'];z['max_calibration_label_end']=receipt['max_label_end']
 z['config']=c['id'];return z
def metrics(z,task):
 result=dict(n=len(z),quarters=int(z.target_period.nunique()))
 if task=='h3':
  neg=z[z.label==0];pos=z[z.label==1];r0=float((neg.direction==0).mean());r1=float((pos.direction==1).mean());return result|dict(balanced_accuracy=(r0+r1)/2,recall_not_up=r0,recall_up=r1,accuracy=float((z.label==z.direction).mean()),worst_recall=min(r0,r1),recall_gap=abs(r0-r1),predicted_up_fraction=float(z.direction.mean()),confusion_matrix=[[int((neg.direction==0).sum()),int((neg.direction==1).sum())],[int((pos.direction==0).sum()),int((pos.direction==1).sum())]])
 if task=='quantile':
  loss=np.column_stack([np.maximum(q*(z.label-z[k]),(q-1)*(z.label-z[k])) for q,k in zip(QLEVELS,['p10','p50','p90'])]).mean(axis=1);result |= dict(coverage=float(((z.label>=z.p10)&(z.label<=z.p90)).mean()),width=float((z.p90-z.p10).mean()),median_mae=float(abs(z.label-z.p50).mean()),individual_quarter_fraction=float((z.groupby('cutoff').p90.std().fillna(0)>1e-6).mean()))
 else:loss=abs(z.label-z.prediction).to_numpy();result |= dict(mae=float(loss.mean()),rmse=float(np.sqrt(np.mean((z.label-z.prediction)**2))),bias=float((z.prediction-z.label).mean()))
 w=z[['cutoff']].copy();w['loss']=loss;return result|dict(equal_quarter_loss=float(w.groupby('cutoff').loss.mean().mean()),pooled_loss=float(loss.mean()))
def choose(dev,cs,task):
 scores={c['id']:metrics(dev[dev.config==c['id']],task) for c in cs};baselines=[c['id'] for c in cs if c.get('baseline')];candidates=[c['id'] for c in cs if not c.get('baseline')]
 if task=='h3':
  baseline=max(baselines,key=lambda k:(scores[k]['balanced_accuracy'],k));eligible=[k for k in candidates if scores[k]['worst_recall']>=.45 and scores[k]['recall_gap']<=.15];challenger=max(eligible or candidates,key=lambda k:(scores[k]['balanced_accuracy'] if eligible else scores[k]['worst_recall'],scores[k]['worst_recall'],k));gain=scores[challenger]['balanced_accuracy']-scores[baseline]['balanced_accuracy'];gate=gain>=.02 and scores[challenger]['worst_recall']>=.5
 else:
  baseline=min(baselines,key=lambda k:(scores[k]['equal_quarter_loss'],k));eligible=[k for k in candidates if task!='quantile' or scores[k]['individual_quarter_fraction']>=.8];assert eligible
  challenger=min(eligible,key=lambda k:(scores[k]['equal_quarter_loss'],k));gain=1-scores[challenger]['equal_quarter_loss']/scores[baseline]['equal_quarter_loss'];gate=gain>=.02
  if task=='quantile':gate=gate and .7<=scores[challenger]['coverage']<=.9
 def loss(key):
  z=dev[dev.config==key].copy();z['err']=(z.label!=z.direction).astype(float) if task=='h3' else np.column_stack([np.maximum(q*(z.label-z[k]),(q-1)*(z.label-z[k])) for q,k in zip(QLEVELS,['p10','p50','p90'])]).mean(axis=1) if task=='quantile' else abs(z.label-z.prediction);return z.groupby('cutoff').err.mean()
 a,b=loss(challenger),loss(baseline);wins=int((a<b-1e-12).sum());gate=bool(gate and wins>=len(a)/2)
 return dict(selected=challenger if gate else baseline,challenger=challenger,baseline=baseline,gate_pass=gate,development_gain=float(gain),quarter_wins=wins,quarters=len(a)),scores
