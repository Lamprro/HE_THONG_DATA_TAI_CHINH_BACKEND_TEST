"""Read-only localhost acceptance for consolidated admin routes. ADMIN_API_TOKEN stays in env."""
import json
import os
from urllib.request import Request, urlopen
from urllib.error import HTTPError

base = 'http://127.0.0.1:8094'
token = os.environ['ADMIN_API_TOKEN']
evidence = []

def call(path, method='GET', authenticated=True, expected=200):
    headers = {'Accept': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + token
    try:
        with urlopen(Request(base + path, headers=headers, method=method), timeout=60) as response:
            status, body = response.status, json.loads(response.read())
    except HTTPError as error:
        status, body = error.code, json.loads(error.read())
    assert status == expected, (path, status)
    evidence.append(dict(path=path, method=method, status=status))
    return body

runtime = call('/api/admin/console/runtime')
assert runtime['adminWorkers']['commandsEnabled'] is False
assert runtime['adminWorkers']['scheduleEnabled'] is False
catalog = call('/api/admin/console/catalog')
reads = ['/market-prices/FPT', '/market-indices', '/market-indices/VNINDEX/prices',
         '/market-indices/VNINDEX/memberships', '/macro/coverage', '/financial-data/quality/coverage']
for path in reads:
    call('/api/admin' + path)
writes = ['/market-indices/catalog/seed', '/macro/jobs/seed', '/financial-data/metrics/recalculate',
          '/financial-data/quality/review-legacy/{symbol}']
for path in writes:
    assert any(route['path'] == '/api/admin' + path and route['method'] == 'POST' for route in catalog)
    call('/api/admin' + path.replace('{symbol}', 'FPT'), method='POST', authenticated=False, expected=403)
print(json.dumps(dict(status='PASS', requests=evidence, nativeMutations=0, providerCalls=0)))
