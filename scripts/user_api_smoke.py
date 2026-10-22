"""Read-only HTTP acceptance against stored data; never fetches a provider or calls a model.
Example: python scripts/user_api_smoke.py --base http://127.0.0.1:8093 --report target/user-http.json
Requires a populated company, index, macro series and public news feed. No admin credential is used.
"""
import argparse
import datetime as dt
import json
import re
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8093')
    parser.add_argument('--symbol', default='FPT')
    parser.add_argument('--exchange', default='HOSE')
    parser.add_argument('--report', default='target/user-http.json')
    args = parser.parse_args()
    evidence = []

    def call(path, expected=200, method='GET', origin=None):
        headers = {'Accept': 'application/json'}
        if origin:
            headers['Origin'] = origin
        started = time.monotonic()
        try:
            with urlopen(Request(args.base.rstrip('/') + path, headers=headers, method=method), timeout=90) as response:
                status, body, response_headers = response.status, json.loads(response.read()), response.headers
        except HTTPError as error:
            status, body, response_headers = error.code, json.loads(error.read()), error.headers
        row = dict(path=path, method=method, http=status, elapsedMs=round((time.monotonic()-started)*1000))
        if 'items' in body:
            row.update(total=body['total'], returned=len(body['items']), hasNext=body['hasNext'])
        evidence.append(row)
        assert status in (expected if isinstance(expected, tuple) else (expected,)), (row, body)
        if status == 200 and 'meta' in body:
            assert body['meta']['timezone'] == 'Asia/Ho_Chi_Minh'
            assert body['meta']['numberEncoding'] == 'DECIMAL_STRING'
        if origin:
            assert response_headers.get('Access-Control-Allow-Origin') == origin
        return body

    symbol = args.symbol
    security = '/api/v1/securities/' + symbol
    exchange = '?exchange=' + args.exchange
    try:
        contract = call('/api/v1/openapi.json')
        assert contract['openapi'] == '3.1.0'
        assert all(path.startswith('/api/v1/') and set(verbs) == {'get'} for path, verbs in contract['paths'].items())
        call('/api/v1/securities?' + urlencode(dict(search=symbol, exchange=args.exchange, size=1)))
        profile = call(security + exchange, origin='http://localhost:5173')['data']
        assert profile['sharesOutstanding'] is None or isinstance(profile['sharesOutstanding'], str)
        call('/api/v1/companies')
        call('/api/v1/companies/' + profile['companyId'])
        call('/api/v1/market/overview' + exchange)
        call(security + '/quote' + exchange)
        prices = call(security + '/prices' + exchange + '&size=500')
        assert prices['items'] and all(row['interval'] == '1d' and row['volumeUnit'] == 'SHARES' for row in prices['items'])
        assert all(row['close'] is None or isinstance(row['close'], str) for row in prices['items'])
        if prices['hasNext']:
            call(security + '/prices' + exchange + '&size=500&page=1')
        indices = call('/api/v1/indices')['items']
        assert indices
        call('/api/v1/indices/' + indices[0]['code'] + '/prices')
        call('/api/v1/indices/' + indices[0]['code'] + '/members')
        macros = call('/api/v1/macro/series')['items']
        assert macros
        call('/api/v1/macro/series/' + macros[0]['id'] + '/observations')
        reports = call(security + '/statements' + exchange)['items']
        assert reports
        report = call(security + '/statements/' + reports[0]['id'] + exchange)['data']
        assert report['items']
        call(security + '/statements/' + report['id'] + '/lineage' + exchange)
        call(security + '/periods' + exchange)
        coverage = call(security + '/data-status' + exchange)['data']
        assert coverage['pointInTimeCertified'] is False
        call('/api/v1/market/filters')
        call('/api/v1/metrics')
        metrics = call(security + '/metrics' + exchange)['items']
        assert metrics
        items = call(security + '/statement-items' + exchange + '&type=' + report['statementType'])['items']
        assert items
        matrix = call(security + '/financials' + exchange + '&' + urlencode(dict(type=report['statementType'], period=report['periodType'], codes=report['items'][0]['code'], periodLimit=4)))['data']
        assert matrix['periods'] and matrix['series']
        metric = metrics[0]['code']
        call('/api/v1/market/screener?' + urlencode(dict(exchange=args.exchange, metric=metric, sort='METRIC_DESC')))
        compared = call('/api/v1/securities/compare?' + urlencode(dict(symbols=symbol, exchange=args.exchange, codes=metric)))['data']
        assert compared['securities'][0]['id'] == profile['id']
        call(security + '/foreign-trades' + exchange)
        call(security + '/reference-observations' + exchange)
        articles = call('/api/v1/news')['items']
        assert articles
        call('/api/v1/news/' + articles[0]['id'])
        call('/api/v1/news/' + articles[0]['id'] + '/analysis')
        call(security + '/forecasts' + exchange)
        availability = call('/api/v1/analysis/availability')['data']
        assert availability['executesModels'] is False
        assert availability['localAi']['publicPredictionReader'] == 'NOT_INTEGRATED'
        suite = availability['localAi']['registeredSuite']['data']
        models = call('/api/v1/local-ai/models?size=100')
        local_ai = call(security + '/local-ai' + exchange)['data']
        assert local_ai['publicResultsAvailable'] is False and local_ai['executionAvailable'] is False
        if suite['state'] == 'RESEARCH_ONLY':
            assert models['items'] and all(model['readiness'] == 'RESEARCH_ONLY' and model['publicResultsAvailable'] is False for model in models['items'])
        overview = call(security + '/overview' + exchange)['data']
        assert overview['profile']['data']['id'] == profile['id']
        assert overview['financialScenarioState'] in ('AVAILABLE', 'NO_ELIGIBLE_RESULT')
        for path, expected in [
            ('/api/v1/securities/ZZUNKNOWN', 404), ('/api/v1/securities?size=101', 400),
            (security + '/prices?from=wrong', 400), (security + '/prices?interval=5m', 400),
            ('/api/v1/news/' + str(uuid.uuid4()), 404), (security + '/metrics?origin=BAD', 400),
            (security + '/financials', 400), (security + '/financials?codes=TOTAL_ASSETS&periodLimit=41', 400),
            ('/api/v1/market/screener?min=1', 400), ('/api/v1/market/screener?sort=unsafe', 400),
            ('/api/v1/market/screener?metric=DOES_NOT_EXIST', 404),
            ('/api/v1/securities/compare?symbols=FPT&codes=', 400),
            (security + '/foreign-trades?from=2026-02-01&to=2026-01-01', 400),
            ('/api/v1/local-ai/models?packageId=wrong', 400),
            ('/api/v1/local-ai/models?size=101', 400),
            ('/api/v1/securities/ZZUNKNOWN/local-ai', 404),
            ('/api/admin/console/session', (403, 503))]:
            call(path, expected)
        call(security + '/overview', 405, 'POST')
        call('/api/v1/local-ai/models', 405, 'POST')
        visited = [urlsplit(row['path']).path for row in evidence if row['method'] == 'GET' and row['http'] == 200]
        missing = [path for path in contract['paths'] if not any(re.fullmatch(re.sub(r'\{[^}]+\}', '[^/]+', path), visit) for visit in visited)]
        assert not missing, missing
        print(json.dumps(dict(status='PASS', publicGetRoutes=len(contract['paths']), requests=len(evidence), maxElapsedMs=max(row['elapsedMs'] for row in evidence), modelCalls=False, adminCredentialUsed=False)))
    finally:
        Path(args.report).write_text(json.dumps(dict(recordedAt=dt.datetime.now(dt.timezone.utc).isoformat(), base=args.base, requests=evidence), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
