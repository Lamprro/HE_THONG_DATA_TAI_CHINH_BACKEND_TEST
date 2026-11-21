"""Real Spring -> trusted pickle -> PostgreSQL. Negative cases run only in isolation."""
import argparse,concurrent.futures,hashlib,json,os,secrets,socket,subprocess,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path

def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0));return s.getsockname()[1]

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--public',action='store_true');a=p.parse_args()
    root=a.root.resolve();repo=Path.cwd();manifest=json.loads((root/'release.json').read_text());digest=hashlib.sha256((root/'release.json').read_bytes()).hexdigest()
    rank_only=len(manifest['models'])==1 and manifest['models'][0]['kind']=='rank'
    if rank_only and a.public:
        # Controlled gate: allow public acceptance only when isolated acceptance previously passed with matching manifest SHA
        isolate_proof = root / 'acceptance' / 'acceptance_result.json'
        if not isolate_proof.exists():
            raise ValueError('RANK_ACCEPTANCE_ISOLATED_ONLY')
        receipt = json.loads(isolate_proof.read_text())
        if receipt.get('status') != 'PASS' or receipt.get('manifest_sha256') != digest:
            raise ValueError('RANK_ACCEPTANCE_ISOLATED_REQUIRED')
    isolated=json.loads((root/'registry-isolate.json').read_text());schema=None if a.public else isolated['schema']
    if not a.public:
        import re
        assert re.fullmatch('local_ai_suite_e2e_[a-f0-9]{32}',schema) and isolated['manifest_sha256']==digest
    else:
        registered=json.loads((root/'registry-apply.json').read_text());assert registered['status']=='PASS' and registered['manifest_sha256']==digest
    out=root/('business-run' if a.public else 'acceptance');out.mkdir(exist_ok=True)
    def save(name,x):(out/name).write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
    model_port,app_port=free_port(),free_port();token,admin=secrets.token_hex(32),secrets.token_hex(32)
    env=os.environ.copy();env.update(LOCAL_AI_SUITE_TOKEN=token,FINANCIAL_ADMIN_API_TOKEN=admin,FINANCIAL_LOCAL_AI_SUITE_API_TOKEN=token)
    cp=str(repo/'target/classes')+os.pathsep+(repo/'target/local-ai-runtime/classpath.txt').read_text().strip()
    spring=['java','-cp',cp,'com.hethongdata.taichinh.FinancialDataApplication',f'--server.port={app_port}','--server.address=127.0.0.1','--spring.config.additional-location=file:application-local.properties','--spring.jpa.hibernate.ddl-auto=none','--financial.local-ai-suite.enabled=true',f'--financial.local-ai-suite.base-url=http://127.0.0.1:{model_port}','--financial.local-ai.enabled=false','--financial.llm.enabled=false','--financial.llm.scheduler.enabled=false','--financial.news-recovery.scheduler.enabled=false','--financial.ingestion.scheduler.enabled=false','--financial.validation.scheduler.enabled=false','--financial.security-job-reconciliation.scheduler.enabled=false','--financial.master-data.catalog.seed-enabled=false','--financial.ingestion.catalog.seed-enabled=false','--financial.validation.catalog.seed-enabled=false','--financial.ingestion.retry-budget.initialize-enabled=false','--financial.documents.cloudinary.enabled=false']
    if schema:spring.append('--spring.datasource.hikari.connection-init-sql=SET search_path TO '+schema+', public')
    prefix='/api/admin/local-ai/suite';processes=[];streams=[];checks=[];results=[]
    def request(path,data=None,authorized=True,model=False,timeout=90):
        headers={'Content-Type':'application/json'}
        if authorized:headers['Authorization']='Bearer '+(token if model else admin)
        url=f'http://127.0.0.1:{model_port if model else app_port}'+path
        req=urllib.request.Request(url,data=None if data is None else json.dumps(data).encode(),headers=headers)
        with urllib.request.urlopen(req,timeout=timeout) as response:return json.load(response)
    def payload(row,role='selected'):
        return dict(packageId=manifest['package_id'],modelId=row['model_id'],ticker=row['ticker'],role=role,requestId=str(uuid.uuid4()))
    def execute(body):
        result=request(prefix+'/execute',body)
        if result['status']!='SUCCEEDED':raise AssertionError('EXECUTION_FAILED '+json.dumps(result))
        response=result['response'];assert response['manifest_sha256']==digest and response['input_sha256'] and response['production_eligible'] is False and response['replace_llm'] is False
        audit=request(prefix+'/runs/'+result['runId']);assert audit['http_status']==200 and audit['checks']['passed'] and audit['status']=='SUCCEEDED'
        results.append(dict(request=body,result=result,audit=audit));return result
    try:
        for name,cmd in [('python',[sys.executable,str(root/'release_server.py'),'--root',str(root),'--port',str(model_port)]),('spring',spring)]:
            stream=(out/(name+'.log')).open('wb');streams.append(stream);processes.append(subprocess.Popen(cmd,cwd=repo,env=env,stdout=stream,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)))
        for _ in range(240):
            if any(x.poll() is not None for x in processes):raise RuntimeError('PROCESS_EXITED; inspect local logs')
            try:request(prefix+'/packages',timeout=2);request('/health',model=True,timeout=2);break
            except Exception:time.sleep(.5)
        else:raise RuntimeError('STARTUP_TIMEOUT')
        print('REAL_SPRING_PYTHON_DB_READY',flush=True)
        inputs=request(prefix+'/packages/'+manifest['package_id']+'/inputs');assert len(inputs)==len(manifest['inputs'])
        if not rank_only:assert len(inputs)==218
        if not a.public:
            for model,path in [(False,prefix+'/packages'),(True,'/v1/predict')]:
                try:request(path,{} if model else None,authorized=False,model=model)
                except urllib.error.HTTPError as e:assert e.code==(401 if model else 403)
                else:raise AssertionError('AUTH_NOT_ENFORCED')
            checks.append('spring_and_python_authentication')
        for i,row in enumerate(manifest['inputs']):
            execute(payload(row))
            if (i+1)%24==0:print('SELECTED_PREDICTIONS',i+1,flush=True)
        checks.append('all_registered_snapshots_selected_predictions')
        if rank_only:
            issued=json.loads((root/'rank/issued.json').read_text())
            expected={r['ticker']:r for r in issued['results']}
            for item in results:
                actual=item['result']['response']['result']
                reference=expected[actual['ticker']]
                for key in ('score','rank_percentile','bucket','probability_status'):
                    assert actual[key]==reference[key]
            checks.append('single_symbol_spring_calls_match_whole_cohort_scores')
        if not a.public:
            if not rank_only:
                for spec in manifest['models']:
                    row=next(r for r in manifest['inputs'] if r['model_id']==spec['id'] and r['ticker']=='FPT')
                    for role in ['challenger','baseline']:execute(payload(row,role))
                checks.append('all_11_models_all_3_roles')
            replay=request(prefix+'/execute',results[0]['request']);assert replay['status']=='CACHED'
            cached=request(prefix+'/runs/'+replay['runId']);assert cached['http_status'] is None and cached['cached_from']==results[0]['result']['runId']
            conflict=dict(results[0]['request'],role='baseline');rejected=request(prefix+'/execute',conflict);assert rejected['status']=='INPUT_REJECTED'
            missing=dict(payload(manifest['inputs'][0]),ticker='ZZINVALID');bad=request(prefix+'/execute',missing);assert bad['status']=='INPUT_REJECTED'
            for item in [rejected,bad]:assert request(prefix+'/runs/'+item['runId'])['http_status'] is None
            body=payload(manifest['inputs'][0])
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:race=list(pool.map(lambda _:request(prefix+'/execute',body),range(2)))
            assert sum(x['status']=='SUCCEEDED' for x in race)==1 and all(x['status'] in ['SUCCEEDED','CACHED','INPUT_REJECTED'] for x in race)
            checks.append('idempotent_replay_conflict_missing_snapshot_and_concurrent_claim')
            original=results[0]['audit']['request_json'];tampered=json.loads(json.dumps(original));tampered['input_sha256']='0'*64
            try:request('/v1/predict',tampered,model=True)
            except urllib.error.HTTPError as e:assert e.code==400
            else:raise AssertionError('TAMPER_NOT_REJECTED')
            direct=request('/v1/predict',original,model=True);assert direct['result']==results[0]['result']['response']['result']
            checks.append('feature_hash_tamper_rejected_and_direct_python_spring_result_parity')
            save('negative_and_replay.json',dict(cached=replay,conflict=rejected,missing=bad,concurrent=race))
        save('predictions_and_audits.json',results)
        history=request(prefix+'/packages/'+manifest['package_id']+'/runs');save('run_history.json',history)
        assert not any(x['status']=='RUNNING' for x in history)
        receipt=dict(status='PASS',manifest_sha256=digest,package_id=manifest['package_id'],isolated_schema=not a.public,database_schema=schema or 'public',provider_mock=False,spring_http=True,selected_predictions=len(inputs),additional_role_predictions=0 if a.public or rank_only else 22,concurrent_real_predictions=0 if a.public else 1,model_tasks=len(manifest['models']),pickle_files=0 if rank_only else 34,joblib_files=1 if rank_only else 0,production_eligible=False,replace_llm=False,checks=checks,processes_stopped_after_trial=True)
        save('acceptance_result.json',receipt);print(json.dumps(receipt),flush=True)
    except Exception:
        save('partial_predictions_and_audits.json',results);raise
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait()
        for stream in streams:stream.close()

if __name__=='__main__':main()
