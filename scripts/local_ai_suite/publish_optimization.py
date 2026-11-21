"""Deliver a new research experiment; never replace a registered business package."""
import argparse,json,hashlib,shutil,subprocess,sys,uuid
from datetime import datetime,timezone
from pathlib import Path
import pandas as pd

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def dump(p,x):Path(p).write_text(json.dumps(x,indent=2,ensure_ascii=False),encoding='utf-8')
def main(root,dest):
 if dest.exists():raise ValueError('DESTINATION_EXISTS')
 for name in ['control','candidate']:
  if json.loads((root/name/'status.json').read_text())['status']!='VERIFIED_RESEARCH_PACKAGE':raise ValueError('RUN_NOT_VERIFIED')
 qa=json.loads((root/'reports/document_verification.json').read_text());assert qa['status']=='PASS' and qa['docx_sha256']==sha(root/'reports'/qa['file'])
 dest.mkdir(parents=True);repo=Path.cwd();copied=[]
 def copy(src,rel):
  target=dest/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,target)
  if sha(src)!=sha(target):raise ValueError('COPY_SHA_MISMATCH')
  copied.append(rel.as_posix())
 def tree(src,rel,exts):
  for p in src.rglob('*'):
   if p.is_file() and '__pycache__' not in p.parts and p.suffix in exts:copy(p,rel/p.relative_to(src))
 runtime=root/'candidate/runtime_package';registry=json.loads((runtime/'registry.json').read_text())
 for name,digest in registry['runtime_files'].items():
  if sha(runtime/name)!=digest:raise ValueError('RUNTIME_INTEGRITY')
  copy(runtime/name,Path('runtime_package')/name)
 for name in ['registry.json','example_request.json','README.md']:copy(runtime/name,Path('runtime_package')/name)
 copy(repo/'docs/LOCAL_AI_OPTIMIZATION_20261023_V2.md',Path('README.md'))
 for p in (root/'reports').iterdir():
  if p.suffix in ['.docx','.json']:copy(p,Path('reports')/p.name)
 for p in root.iterdir():
  if p.is_file() and p.suffix in ['.json','.csv']:copy(p,Path('evidence')/p.name)
 for name in ['control','candidate']:
  for folder in ['models','data','source','reports','verification','verification_future']:
   tree(root/name/folder,Path('evidence')/name/folder,{'.csv','.json','.txt','.py','.java','.sql','.ps1','.md'})
  for p in (root/name).glob('*.json'):copy(p,Path('evidence')/name/p.name)
 for name in ['metadata-before','metadata-reconciled','metadata-after','metadata-queries']:tree(root/name,Path('metadata')/name,{'.csv','.txt','.sql'})
 tree(repo/'scripts/local_ai_suite',Path('source'),{'.py','.java','.ps1','.md','.sql','.json','.txt'})
 for name in ['scripts/FinancialMetadataReconcile.java','src/main/resources/db/manual/V20261023_02__financial_item_disclosure_review.sql','src/main/java/com/hethongdata/taichinh/service/financial/FinancialStatementMetadata.java','src/test/java/com/hethongdata/taichinh/service/financial/FinancialMetadataBoundaryTests.java']:
  copy(repo/name,Path('backend_source')/name)
 copy(root/'java-tests.log',Path('evidence/java-tests.log'))
 assert len(list(dest.rglob('*.pkl')))==28
 demo=dest/'demo';demo.mkdir();tickers=pd.read_csv(dest/'runtime_package/models/direction_h1/latest_features.csv').ticker.to_list();checks=[]
 for role in ['selected','challenger','baseline']:
  request=json.loads((dest/'runtime_package/example_request.json').read_text());request.update(request_id=str(uuid.uuid4()),tickers=tickers);inp=demo/(role+'-request.json');output=demo/(role+'-result.json');dump(inp,request)
  subprocess.run([sys.executable,'-B',str(dest/'runtime_package/serve.py'),'--root',str(dest/'runtime_package'),'--role',role,'--input',str(inp),'--output',str(output),'--ledger',str(demo/(role+'.sqlite'))],check=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  result=json.loads(output.read_text());assert len(result['results'])==24 and all(len(x['predictions'])==9 for x in result['results']);checks.append(dict(role=role,predictions=216))
 inventory={p.relative_to(dest).as_posix():sha(p) for p in dest.rglob('*') if p.is_file() and '__pycache__' not in p.parts};dump(dest/'delivery_manifest.json',inventory)
 receipt=dict(status='PASS',destination=str(dest),published_at=datetime.now(timezone.utc).isoformat(),copied_files=len(copied),inventory_files=len(inventory),pkl_files=28,destination_predictions=648,checks=checks,production_eligible=False,replace_llm=False,registered_business_package_replaced=False,delivery_manifest_sha256=sha(dest/'delivery_manifest.json'))
 dump(dest/'publication_receipt.json',receipt);dump(root/'publication_receipt.json',receipt);print(json.dumps(receipt))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);a=p.parse_args();main(a.root.resolve(),a.destination.resolve())
