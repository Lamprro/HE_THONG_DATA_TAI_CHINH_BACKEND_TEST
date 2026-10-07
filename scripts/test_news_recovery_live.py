"""Local HTTP/DB orchestration only: no replacement fetcher, model stub or generated news.
Reads private configuration without printing credentials. Writes audit evidence under target/.
"""
import argparse, json, os, re, sys, datetime
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import psycopg2

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[1]
properties = dict(line.strip().split('=', 1) for line in (root/'application-local.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.lstrip().startswith('#'))
def value(key):
    raw = properties.get(key, '')
    match = re.fullmatch(r'\$\{([^:}]+)(?::([^}]*))?\}', raw)
    return os.getenv(match[1], match[2] or '') if match else raw
def connection():
    parsed = urlparse(value('spring.datasource.url').removeprefix('jdbc:'))
    return psycopg2.connect(host=parsed.hostname, port=parsed.port or 5432, dbname=parsed.path.lstrip('/'), user=value('spring.datasource.username'), password=value('spring.datasource.password'), connect_timeout=10)
def request(path, data=None, anonymous=False):
    headers = {} if anonymous else {'Authorization': 'Bearer '+value('financial.admin.api-token')}
    if data is not None: headers['Content-Type']='application/json'
    req = Request('http://127.0.0.1:8081'+path, data=None if data is None else json.dumps(data).encode(), headers=headers)
    try:
        with urlopen(req, timeout=600) as response: return {'http':response.status, 'body':json.load(response)}
    except HTTPError as error:
        return {'http':error.code, 'body':json.loads(error.read())}
def snapshot():
    with connection() as conn, conn.cursor() as cursor:
        queries = {
            'url_only': "SELECT id,canonical_url,title,length(content_text) FROM news_articles WHERE coalesce(length(content_text),0)<200 ORDER BY published_at DESC LIMIT 5",
            'recent_articles': "SELECT id,canonical_url,title,length(content_text) FROM news_articles WHERE length(content_text)>200 ORDER BY published_at DESC LIMIT 5",
            'recovery_runs': "SELECT id,news_article_id,status,model_name,http_status,error_message,response_metadata->>'review_state' AS review_state,validation_errors FROM llm_runs WHERE task_code='NEWS_CONTENT_RECOVERY' ORDER BY created_at DESC LIMIT 10",
            'recovery_attempts': "SELECT a.llm_run_id,a.attempt_no,a.model_name,a.http_status,a.error_category,a.latency_ms FROM llm_run_attempts a JOIN llm_runs r ON r.id=a.llm_run_id WHERE r.task_code='NEWS_CONTENT_RECOVERY' ORDER BY a.created_at",
            'recovery_validations': "SELECT v.llm_run_id,v.rule_code,v.result_status,v.message FROM validation_results v JOIN llm_runs r ON r.id=v.llm_run_id WHERE r.task_code='NEWS_CONTENT_RECOVERY' ORDER BY v.checked_at,v.rule_code",
        }
        result={}
        for name, query in queries.items():
            cursor.execute(query); result[name]=[dict(zip([c.name for c in cursor.description],row)) for row in cursor.fetchall()]
        return result

parser=argparse.ArgumentParser()
parser.add_argument('--migrate',action='store_true')
parser.add_argument('--snapshot',action='store_true')
parser.add_argument('--path')
parser.add_argument('--data')
parser.add_argument('--anonymous',action='store_true')
args=parser.parse_args()
result={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
if args.migrate:
    with connection() as conn, conn.cursor() as cursor:
        cursor.execute((root/'src/main/resources/db/manual/V20261004_06__news_recovery.sql').read_text())
    result['migration']='Applied additive recovery index and rule seeds; no table/column/drop'
if args.snapshot: result['snapshot']=snapshot()
if args.path: result['api']=request(args.path,None if args.data is None else json.loads(args.data),args.anonymous)
directory=root/'target/news-recovery-live';directory.mkdir(parents=True,exist_ok=True)
name=datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.json'
(directory/name).write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,default=str))
