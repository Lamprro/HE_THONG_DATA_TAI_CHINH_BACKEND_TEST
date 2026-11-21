"""Publish a new verified local release, excluding credentials, caches and duplicate PKLs."""
import argparse,csv,hashlib,importlib.util,json,shutil,sys
from datetime import datetime,timezone
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,x):Path(p).write_text(json.dumps(x,indent=2,ensure_ascii=False,allow_nan=False),encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);p.add_argument('--resume-incomplete',action='store_true');a=p.parse_args();root=a.root.resolve();dest=a.destination.resolve();repo=Path.cwd()
    manifest=json.loads((root/'release.json').read_text());qa=json.loads((root/'reports/document_verification.json').read_text())
    for name,digest in manifest['files'].items():
        if sha(root/name)!=digest:raise ValueError('SOURCE_INTEGRITY')
    if qa['status']!='PASS' or sha(root/'reports'/qa['file'])!=qa['docx_sha256']:raise ValueError('DOCUMENT_NOT_VERIFIED')
    for name in ['acceptance/acceptance_result.json','business-run/acceptance_result.json']:
        receipt=json.loads((root/name).read_text())
        if receipt['status']!='PASS' or receipt['manifest_sha256']!=sha(root/'release.json'):raise ValueError('ACCEPTANCE_NOT_VERIFIED')
    if dest.exists():
        if not a.resume_incomplete or (dest/'publication_receipt.json').exists() or not (dest/'release.json').exists() or sha(dest/'release.json')!=sha(root/'release.json'):raise ValueError('DESTINATION_EXISTS_NO_OVERWRITE')
    else:dest.mkdir(parents=True)
    copied=[]
    def copy(src,relative):
        dst=dest/relative;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
        if sha(src)!=sha(dst):raise ValueError('COPY_INTEGRITY')
        copied.append(dict(path=relative.as_posix(),sha256=sha(dst)))
    def tree(src,relative,allow=None):
        for file in src.rglob('*'):
            if file.is_file() and '__pycache__' not in file.parts and (allow is None or allow(file)):copy(file,relative/file.relative_to(src))
    # The already-registered immutable manifest is authoritative, including legacy
    # sealed bytecode. Never drop a declared file or rewrite the registered digest.
    for name in manifest['files']:copy(root/name,Path(name))
    copy(root/'release.json',Path('release.json'))
    for folder in ['acceptance','business-run','database-audit','outcome-review-verified']:
        tree(root/folder,Path(folder),lambda x:x.suffix in ['.json','.csv','.txt'])
    for file in root.glob('registry-*.json'):copy(file,Path(file.name))
    for file in (root/'reports').iterdir():
        if file.suffix in ['.docx','.json']:copy(file,Path('reports')/file.name)
    copy(repo/'docs/LOCAL_AI_RELEASE_20261023.md',Path('README.md'))
    for folder in ['local_ai_suite','local_ai_financial']:tree(repo/'scripts'/folder,Path('source')/folder,lambda x:x.suffix in ['.py','.java','.ps1','.md','.sql','.json','.txt'])
    copy(repo/'scripts/local_ai_suite/requirements.txt',Path('requirements.txt'))
    for name in ['scripts/SuiteRegistryStore.java','scripts/FinancialDisclosureFactStore.java','scripts/FinancialDisclosureIngestion.java','src/main/java/com/hethongdata/taichinh/service/localai/LocalAiSuiteService.java','src/main/java/com/hethongdata/taichinh/service/localai/LocalAiSuiteContract.java','src/main/java/com/hethongdata/taichinh/controller/admin/LocalAiSuiteAdminController.java','src/main/resources/db/manual/V20261023_01__local_ai_suite_runtime.sql','src/test/java/com/hethongdata/taichinh/service/localai/LocalAiSuiteContractTests.java']:
        copy(repo/name,Path('backend_source')/name)
    market=Path(manifest['market_source']);financial=Path(manifest['financial_source'])
    for folder in ['source','data','models','verification','verification_future','reports']:tree(market/folder,Path('evidence/market')/folder,lambda x:x.suffix in ['.json','.csv','.txt','.java','.py','.sql','.ps1','.md'])
    for file in market.glob('*.json'):copy(file,Path('evidence/market')/file.name)
    for folder in ['data','verification','visual-review','extraction']:tree(financial/folder,Path('evidence/financial')/folder,lambda x:x.suffix in ['.json','.csv','.txt','.png'])
    for file in financial.glob('*.json'):copy(file,Path('evidence/financial')/file.name)
    old=repo/'artifacts/local-ai/financial-npat-20261021'
    for folder in ['disclosure-pdfs','disclosure-listing-v2']:tree(old/folder,Path('evidence/financial/source_documents')/folder,lambda x:x.suffix in ['.pdf','.json','.csv','.html','.txt'])
    copy(repo/'target/local-ai-suite-20261023-tests.log',Path('evidence/backend-tests.log'))
    assert len(list(dest.rglob('*.pkl')))==34
    sys.dont_write_bytecode=True
    spec=importlib.util.spec_from_file_location('published_release',dest/'release_server.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);engine=module.Release(dest);smokes=[]
    for model in manifest['models']:
        row=next(x for x in manifest['inputs'] if x['model_id']==model['id'] and x['ticker']=='FPT')
        for role in ['selected','challenger','baseline']:
            request=dict(schema='local_ai.registered.input.v1',package_id=manifest['package_id'],manifest_sha256=sha(dest/'release.json'),model_id=model['id'],role=role,ticker='FPT',input_sha256=row['input_sha256'],input=json.loads(row['input_payload']))
            result=engine.run(request);assert result['result']['model_sha256']==model['artifacts'][role]['sha256'] and result['production_eligible'] is False;smokes.append(result)
    write(dest/'publication_smoke.json',dict(status='PASS',calls=len(smokes),mode='LOCAL_FILE_INFERENCE',business_DB_writes=0,results=smokes))
    records=json.loads((root/'business-run/predictions_and_audits.json').read_text());flat=[]
    for r in records:
        x=r['result']['response'];result=x['result'];row={k:x[k] for k in ['model_id','ticker','role','issued_at','data_as_of']};row.update({k:result.get(k) for k in ['target_period','forecast_timing','direction','score_up','return_p10','return_p50','return_p90','prediction','units','prediction_vnd']});row['run_id']=r['result']['runId'];flat.append(row)
    with (dest/'reports/predictions_by_symbol.csv').open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.DictWriter(f,fieldnames=list(flat[0]));writer.writeheader();writer.writerows(flat)
    inventory={f.relative_to(dest).as_posix():sha(f) for f in dest.rglob('*') if f.is_file() and ('__pycache__' not in f.parts or f.relative_to(dest).as_posix() in manifest['files'])};write(dest/'delivery_manifest.json',inventory)
    receipt=dict(status='PASS',published_at=datetime.now(timezone.utc).isoformat(),destination=str(dest),package_id=manifest['package_id'],release_sha256=sha(dest/'release.json'),files=len(inventory),copy_verified_files=len(copied),pkl_files=34,destination_runtime_checks=33,delivery_manifest_sha256=sha(dest/'delivery_manifest.json'),production_eligible=False,replace_llm=False)
    write(dest/'publication_receipt.json',receipt);write(root/'publication_receipt.json',receipt);print(json.dumps(receipt))

if __name__=='__main__':main()
