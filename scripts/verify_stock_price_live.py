"""Live acceptance through the existing Spring Boot forecast API; no substitute model/fetcher/DB writer."""
import argparse
import datetime
import json
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

p = argparse.ArgumentParser()
p.add_argument('--base-url', default='http://127.0.0.1:8182')
p.add_argument('--config', default='../application-local.properties')
p.add_argument('--symbol', default='FPT')
p.add_argument('--as-of', default=datetime.datetime.now(ZoneInfo('Asia/Ho_Chi_Minh')).date().isoformat())
p.add_argument('--price-only', action='store_true')
p.add_argument('--preview-only', action='store_true')
a = p.parse_args()
config = dict(line.strip().split('=', 1) for line in Path(a.config).read_text(encoding='utf-8').splitlines()
              if '=' in line and not line.lstrip().startswith('#'))
token = config.get('financial.admin.api-token', '')
if len(token) < 32 or token.startswith('${'):
    raise SystemExit('A configured private admin credential is required')

def api(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = Request(a.base_url.rstrip('/') + '/api/admin/forecasts' + path, data=data,
                  headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urlopen(req, timeout=240) as r:
        return json.load(r, parse_float=Decimal)

evidence = {'checked_at': datetime.datetime.now(ZoneInfo('Asia/Ho_Chi_Minh')).isoformat(),
            'mode': 'real Spring Boot API and configured Gemini provider; no mock'}
evidence['configuration'] = api('/configuration')
assert evidence['configuration']['providerConfigured'], 'Real provider is not configured'
evidence['seed'] = api('/template/seed', {})
security = next(s for s in api('/securities?limit=500') if s['symbol'] == a.symbol.upper())
targets = ['STOCK_PRICE'] if a.price_only else ['NET_PROFIT_AFTER_TAX', 'PRETAX_PROFIT',
                                             'TOTAL_ASSETS', 'OWNERS_EQUITY', 'LIABILITIES', 'STOCK_PRICE']
body = {'securityId': security['securityId'], 'asOfDate': a.as_of, 'horizonQuarters': 1,
        'targets': targets, 'requireMacro': True}
evidence['request'] = body
evidence['preview'] = api('/preview', body)
assert evidence['preview']['eligible'], evidence['preview']['issues']
assert evidence['preview']['coverage']['macroObservations'] > 0
if a.preview_only:
    preview_path = Path('target/stock-price-release') / (a.symbol.upper() + '-preview.json')
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    evidence['mode'] = 'local preview only; no external provider call'
    preview_path.write_text(json.dumps(evidence, default=str, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'eligible': evidence['preview']['eligible'],
                      'coverage': evidence['preview']['coverage'],
                      'forecast_period_end': evidence['preview']['forecastPeriodEnd'],
                      'evidence_file': str(preview_path)}, default=str, ensure_ascii=False, indent=2))
    raise SystemExit(0)
evidence['execution'] = api('/execute', body)
outdir = Path('target/stock-price-release')
outdir.mkdir(parents=True, exist_ok=True)
outfile = outdir / (a.symbol.upper() + '-live.json')
def save():
    outfile.write_text(json.dumps(evidence, default=str, ensure_ascii=False, indent=2), encoding='utf-8')
save()
assert evidence['execution']['status'] in ('SUCCESS', 'CACHED'), evidence['execution']
run = api('/runs/' + evidence['execution']['runId'])
evidence['run'] = run
result = next(r for r in api('/securities/' + security['securityId'] + '/results')
              if r['id'] == evidence['execution']['resultId'])
evidence['result'] = result
data = result['data']
assert {f['metric_code'] for f in data['forecasts']} == set(targets)
points = {p['id']: p for p in evidence['preview']['input']['points']}
for f in data['forecasts']:
    base = Decimal(str(points[f['base_point_id']]['value']))
    calc = next(c for c in data['calculated_values'] if c['metric_code'] == f['metric_code'])
    for name in ('bear', 'base', 'bull'):
        row = f[name]
        assert f['base_point_id'] in row['evidence_ids'] and all(i in points for i in row['evidence_ids'])
        expected = base * (1 + Decimal(str(row['growth_percent'])) / 100)
        assert abs(expected - Decimal(str(calc[name]))) <= Decimal('0.00000001')
    if f['metric_code'] == 'STOCK_PRICE':
        assert calc['unit'] == 'VND_PER_SHARE' and all(Decimal(str(calc[n])) > 0 for n in ('bear','base','bull'))
evidence['replay'] = api('/execute', body)
assert evidence['replay']['status'] == 'CACHED' and evidence['replay']['resultId'] == result['id']
assert all(v['result_status'] == 'PASS' for v in run['validations']) and len(run['validations']) == 7
save()
price = next(c for c in data['calculated_values'] if c['metric_code'] == 'STOCK_PRICE')
print(json.dumps({'symbol': a.symbol.upper(), 'status': evidence['execution']['status'],
                  'result_id': result['id'], 'run_id': run['id'], 'forecast_period_end': data['forecast_period_end'],
                  'macro_used': data['macro_used'], 'price': price,
                  'validation_pass': len(run['validations']), 'replay': evidence['replay']['status'],
                  'evidence_file': str(outfile)}, default=str, ensure_ascii=False, indent=2))
