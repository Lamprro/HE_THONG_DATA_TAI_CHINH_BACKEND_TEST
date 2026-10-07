"""Read-only evidence for the onboarding and production-readiness reports. No provider generation."""
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json, datetime, psycopg2

root = Path(__file__).resolve().parents[1]
p = dict(x.strip().split('=', 1) for x in (root/'application-local.properties').read_text(encoding='utf-8').splitlines() if '=' in x and not x.lstrip().startswith('#'))
u = urlparse(p['spring.datasource.url'].removeprefix('jdbc:'))
connection = psycopg2.connect(host=u.hostname, port=u.port or 5432, dbname=u.path.lstrip('/'), user=p['spring.datasource.username'], password=p['spring.datasource.password'], options='-c default_transaction_read_only=on -c statement_timeout=20000')
result = {'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'database_mode': 'read_only'}
queries = {
 'tables': "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name",
 'news': "SELECT count(*) total,count(*) FILTER(WHERE length(content_text)>=200) body,count(*) FILTER(WHERE content_text IS NULL) no_body,count(*) FILTER(WHERE content_text LIKE '%Giá hiện tại Thay đổi Xem hồ sơ doanh nghiệp TIN MỚI%') dirty,count(*) FILTER(WHERE published_at IS NULL) missing_date FROM news_articles",
 'news_duplicates': "SELECT count(*) FROM (SELECT url_hash FROM news_articles GROUP BY url_hash HAVING count(*)>1) x",
 'versions': 'SELECT data_domain,status,count(*) FROM data_versions GROUP BY data_domain,status ORDER BY data_domain,status',
 'raw_types': 'SELECT entity_type,count(*) FROM raw_payloads GROUP BY entity_type ORDER BY entity_type',
 'jobs': 'SELECT dataset_type,is_active,count(*) FROM ingestion_jobs GROUP BY dataset_type,is_active ORDER BY dataset_type,is_active',
 'financial': 'SELECT statement_type,report_scope,currency,unit_scale,is_current,is_canonical,count(*) total,count(published_at) with_publication FROM financial_statements GROUP BY statement_type,report_scope,currency,unit_scale,is_current,is_canonical',
 'sample_url': 'SELECT canonical_url FROM news_articles WHERE length(content_text)>=200 ORDER BY published_at DESC LIMIT 1',
 'prices': 'SELECT interval_code,is_canonical,count(*),min(price_timestamp),max(price_timestamp) FROM market_prices GROUP BY interval_code,is_canonical',
 'counts': 'SELECT (SELECT count(*) FROM llm_runs) runs,(SELECT count(*) FROM llm_run_attempts) attempts,(SELECT count(*) FROM llm_results) results,(SELECT count(*) FROM macro_series) macro_series,(SELECT count(*) FROM macro_observations) macro_observations,(SELECT count(*) FROM index_prices) index_prices,(SELECT count(*) FROM security_index_memberships) memberships',
 'rules': 'SELECT data_domain,is_active,count(*) FROM validation_rules GROUP BY data_domain,is_active ORDER BY data_domain,is_active',
 'llm_results': "SELECT r.id,r.llm_run_id,r.task_code,r.source_domain,r.news_article_id,r.company_id,r.security_id,r.quality_status,r.result_json,r.validation_round_id,r.as_of_date FROM llm_results r WHERE is_current ORDER BY source_domain,task_code",
 'llm_runs': "SELECT id,task_code,status,model_name,http_status,input_tokens,output_tokens,error_message FROM llm_runs WHERE id IN (SELECT llm_run_id FROM llm_results WHERE is_current)",
 'current_validation': "SELECT r.task_code,r.llm_run_id,v.result_status,count(*) FROM llm_results r JOIN validation_results v ON v.llm_run_id=r.llm_run_id AND v.validation_round_id=r.validation_round_id WHERE r.is_current GROUP BY r.task_code,r.llm_run_id,v.result_status",
 'test_schemas': "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'forecast_test_%' OR schema_name LIKE 'llm_test_%'"
}
with connection.cursor() as cursor:
 for name,query in queries.items():
  try:
   cursor.execute(query)
   result[name] = [dict(zip([d.name for d in cursor.description], row)) for row in cursor.fetchall()]
  except Exception as e:
   connection.rollback(); result[name] = {'error':str(e).splitlines()[0]}
connection.close()
result['http'] = []
for path in ['/api/admin/forecasts/configuration','/api/admin/llm/templates','/api/admin/llm/gemini/configuration','/api/ingestion-jobs']:
 try:
  with urlopen('http://127.0.0.1:8080'+path,timeout=10) as response: status=response.status
 except HTTPError as e: status=e.code
 except Exception as e: status=type(e).__name__
 result['http'].append({'path':path,'anonymous_status':status})
result['schemas'] = {}
from urllib.parse import urlencode
python_base=p.get('financial.python-service.base-url','https://he-thong-data-tai-chinh-test.vercel.app').rstrip('/')
result['python_contract']=[]
for suffix in ['/api/v1/health','/api/v1/url-fetch?'+urlencode({'url':result['sample_url'][0]['canonical_url']})]:
 try:
  with urlopen(python_base+suffix,timeout=50) as response:
   body=json.load(response)
   result['python_contract'].append({'endpoint':suffix.split('?')[0],'http':response.status,'fields':sorted(body),'upstream_http':body.get('http_status'),'extraction_status':body.get('extraction_status'),'extraction_error':body.get('extraction_error'),'content_type':body.get('content_type'),'content_length':len(body.get('content_text') or ''),'version':body.get('version')})
 except HTTPError as e: result['python_contract'].append({'endpoint':suffix.split('?')[0],'http':e.code})
 except Exception as e: result['python_contract'].append({'endpoint':suffix.split('?')[0],'error':type(e).__name__})
for file in (root/'src/main/resources/llm').glob('*.json'):
 data=json.loads(file.read_text(encoding='utf-8'))
 result['schemas'][data['task_code']]={'version':data['version'],'response_schema':data['response_schema']}
out=root/'target/report-review'
out.mkdir(parents=True,exist_ok=True)
(out/'evidence.json').write_text(json.dumps(result,ensure_ascii=False,default=str,indent=2),encoding='utf-8')
for key in ['checked_at','news','news_duplicates','counts','http','test_schemas','python_contract']:
 print(key,json.dumps(result[key],ensure_ascii=False,default=str))
for key,value in result.items():
 if isinstance(value,dict) and 'error' in value: print('QUERY_ERROR',key,value['error'])
