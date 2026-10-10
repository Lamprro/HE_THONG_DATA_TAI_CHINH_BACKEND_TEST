"""Live acceptance through the existing Spring Boot forecast API; no substitute model/fetcher/DB writer."""
import argparse
import datetime
import json
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from zoneinfo import ZoneInfo
from concurrent.futures import ThreadPoolExecutor

p = argparse.ArgumentParser()
p.add_argument('--base-url', default='http://127.0.0.1:8182')
p.add_argument('--config', default='../application-local.properties')
p.add_argument('--symbol', default='FPT')
p.add_argument('--as-of', default=datetime.datetime.now(ZoneInfo('Asia/Ho_Chi_Minh')).date().isoformat())
p.add_argument('--price-only', action='store_true')
p.add_argument('--preview-only', action='store_true')
p.add_argument('--revalidate', action='store_true', help='Revalidate the stored output without calling the provider')
p.add_argument('--extended-checks', action='store_true', help='Check horizons, cutoff, invalid requests and concurrent cache through the same API')
a = p.parse_args()
config = dict(line.strip().split('=', 1) for line in Path(a.config).read_text(encoding='utf-8').splitlines()
              if '=' in line and not line.lstrip().startswith('#'))
token = config.get('financial.admin.api-token', '')
if len(token) < 32 or token.startswith('${'):
    raise SystemExit('A configured private admin credential is required')

def api(path, body=None, authenticated=True):
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(a.base_url.rstrip('/') + '/api/admin/forecasts' + path, data=data,
                  headers=headers)
    with urlopen(req, timeout=240) as r:
        return json.load(r, parse_float=Decimal)

evidence = {'checked_at': datetime.datetime.now(ZoneInfo('Asia/Ho_Chi_Minh')).isoformat(),
            'mode': 'real Spring Boot API and configured Gemini provider; no mock'}
evidence['configuration'] = api('/configuration')
assert evidence['configuration']['providerConfigured'], 'Real provider is not configured'
evidence['seed'] = ({'status': 'SKIPPED', 'reason': 'Read-only preview uses the existing template'}
                    if a.preview_only else api('/template/seed', {}))
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
if evidence['execution'].get('runId'):
    evidence['run'] = api('/runs/' + evidence['execution']['runId'])
    save()
assert evidence['execution']['status'] in ('SUCCESS', 'CACHED'), evidence['execution']
if a.revalidate:
    original_attempt_count = len(evidence['run']['attempts'])
    evidence['revalidation'] = api('/runs/' + evidence['execution']['runId'] + '/revalidate', {})
    save()
    assert evidence['revalidation']['status'] == 'SUCCESS', evidence['revalidation']
run = api('/runs/' + evidence['execution']['runId'])
if a.revalidate:
    assert len(run['attempts']) == original_attempt_count, 'Revalidation must not call the provider'
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
round_id = run['validationRoundId']
assert round_id, 'A published forecast must identify its current validation round'
current_validation = [v for v in run['validations'] if v['validation_round_id'] == round_id]
assert len(current_validation) == 7 and all(v['result_status'] == 'PASS' for v in current_validation)
assert {v['rule_code'] for v in current_validation} == {
    'FORECAST_SCHEMA', 'FORECAST_SOURCE', 'FORECAST_EVIDENCE', 'FORECAST_SCENARIOS',
    'FORECAST_COVERAGE', 'FORECAST_SNAPSHOT', 'FORECAST_PROMPT'}
evidence['current_validation_round'] = round_id
if a.extended_checks:
    extended = {}
    evidence['extended_checks'] = extended
    as_of = datetime.date.fromisoformat(a.as_of)
    quarter_end_month = ((as_of.month - 1) // 3 + 1) * 3
    extended['horizons'] = []
    for horizon in range(1, 9):
        preview = api('/preview', dict(body, horizonQuarters=horizon))
        month_index = as_of.year * 12 + quarter_end_month - 1 + 3 * horizon
        next_month_index = month_index + 1
        next_month = datetime.date(next_month_index // 12, next_month_index % 12 + 1, 1)
        expected_date = next_month - datetime.timedelta(days=1)
        assert preview['forecastPeriodEnd'] == expected_date.isoformat()
        assert preview['eligible'], preview['issues']
        extended['horizons'].append({'horizon': horizon, 'forecast_period_end': preview['forecastPeriodEnd']})
    extended['historical_cutoffs'] = []
    for cutoff in ('2025-12-31', '2026-03-31', '2026-06-30', '2026-09-30'):
        historical_body = dict(body, asOfDate=cutoff)
        preview = api('/preview', historical_body)
        for point in preview['input']['points']:
            assert point['periodEnd'] <= cutoff, 'An observation from after the cutoff was included'
            available = datetime.datetime.fromisoformat(point['availableAt'])
            end_of_cutoff = datetime.datetime.fromisoformat(cutoff).replace(tzinfo=ZoneInfo('Asia/Ho_Chi_Minh')) + datetime.timedelta(days=1)
            assert available < end_of_cutoff, 'Data acquired after the cutoff was included'
        extended['historical_cutoffs'].append({'as_of': cutoff, 'eligible': preview['eligible'],
                                             'coverage': preview['coverage'], 'issues': preview['issues']})
        if cutoff == '2025-12-31' and not preview['eligible']:
            stopped = api('/execute', historical_body)
            assert stopped['status'] == 'SKIPPED' and stopped['runId'] is None
            extended['ineligible_execute'] = stopped
    extended['invalid_requests'] = []
    future_date = (datetime.datetime.now(ZoneInfo('Asia/Ho_Chi_Minh')).date() + datetime.timedelta(days=1)).isoformat()
    for label, invalid_body, authenticated, expected in (
            ('missing_admin_credential', body, False, 403),
            ('zero_horizon', dict(body, horizonQuarters=0), True, 400),
            ('out_of_range_horizon', dict(body, horizonQuarters=9), True, 400),
            ('future_cutoff', dict(body, asOfDate=future_date), True, 400)):
        try:
            api('/preview', invalid_body, authenticated=authenticated)
            raise AssertionError(label + ' was unexpectedly accepted')
        except HTTPError as error:
            assert error.code == expected, (label, error.code)
            extended['invalid_requests'].append({'case': label, 'http_status': error.code})
    # Identical already-cached input only; no generation bypass or parallel model tasks.
    with ThreadPoolExecutor(max_workers=4) as pool:
        cached = list(pool.map(lambda unused: api('/execute', body), range(8)))
    assert all(r['status'] == 'CACHED' and r['runId'] == run['id'] and r['resultId'] == result['id'] for r in cached)
    after_cache = api('/runs/' + run['id'])
    assert len(after_cache['attempts']) == len(run['attempts'])
    extended['concurrent_cache'] = {'requests': len(cached), 'status': 'CACHED', 'extra_provider_attempts': 0}
save()
price = next(c for c in data['calculated_values'] if c['metric_code'] == 'STOCK_PRICE')
print(json.dumps({'symbol': a.symbol.upper(), 'status': evidence['execution']['status'],
                  'result_id': result['id'], 'run_id': run['id'], 'forecast_period_end': data['forecast_period_end'],
                  'macro_used': data['macro_used'], 'price': price,
                  'validation_pass': len(current_validation), 'validation_round_id': round_id,
                  'validation_history_count': len(run['validations']), 'replay': evidence['replay']['status'],
                  'extended_checks': bool(a.extended_checks),
                  'evidence_file': str(outfile)}, default=str, ensure_ascii=False, indent=2))
