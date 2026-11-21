"""Local file inference for registered snapshots. Use Spring for business database logging."""
import argparse,hashlib,importlib.util,json,sys
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--model',required=True);p.add_argument('--ticker',required=True);p.add_argument('--role',choices=['selected','challenger','baseline'],default='selected');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    root=a.root.resolve();manifest=json.loads((root/'release.json').read_text());row=next((r for r in manifest['inputs'] if r['model_id']==a.model and r['ticker']==a.ticker),None)
    if row is None:raise ValueError('NO_REGISTERED_SNAPSHOT_FOR_MODEL_AND_TICKER')
    sys.dont_write_bytecode=True
    spec=importlib.util.spec_from_file_location('sealed_release_runtime',root/'release_server.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);engine=module.Release(root)
    request=dict(schema='local_ai.registered.input.v1',package_id=manifest['package_id'],manifest_sha256=hashlib.sha256((root/'release.json').read_bytes()).hexdigest(),model_id=a.model,role=a.role,ticker=a.ticker,input_sha256=row['input_sha256'],input=json.loads(row['input_payload']))
    result=engine.run(request);a.out.mkdir(parents=True,exist_ok=False)
    for name,value in [('request.json',request),('result.json',result),('receipt.json',dict(status='PASS',mode='LOCAL_FILE_INFERENCE',business_DB_writes=0,production_eligible=False))]:(a.out/name).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
    print(str(a.out/'result.json'))

if __name__=='__main__':main()
