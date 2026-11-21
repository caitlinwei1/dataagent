#!/usr/bin/env python3
"""Latency the security layer adds, measured on one cheap endpoint so the difference is the security work itself.

  C  direct to gbi-agent, open path /echo/ok                      (no auth at all)
  A  direct to gbi-agent, /api/data-permission/me, signed here    (agent-side check: HMAC + parse + permission rule)
  B  through gbi-gateway, /api/data-permission/me with a JWT      (A + JWT verify + Redis session lookup + signing + hop)

B - A is the gateway's cost, A - C the agent filter's. Needs GBI_GATEWAY_SIGN_SECRET to sign A.
Usage: python3 eval/data_permission/bench_gateway.py [-n 2000] [-c 16]
"""
import argparse
import base64
import concurrent.futures as cf
import hashlib
import hmac
import json
import os
import statistics
import time

import requests

GW, AGENT = 'http://localhost:8080', 'http://localhost:8065'


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def run(fn, n, conc):
    for _ in range(min(200, n)):
        fn()
    lat = []

    def one(_):
        t = time.perf_counter()
        fn()
        return (time.perf_counter() - t) * 1000

    t0 = time.perf_counter()
    if conc == 1:
        lat = [one(i) for i in range(n)]
    else:
        with cf.ThreadPoolExecutor(conc) as ex:
            lat = list(ex.map(one, range(n)))
    wall = time.perf_counter() - t0
    return {'p50': round(pct(lat, .5), 2), 'p95': round(pct(lat, .95), 2), 'p99': round(pct(lat, .99), 2),
            'mean': round(statistics.mean(lat), 2), 'rps': round(n / wall, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-n', type=int, default=2000)
    ap.add_argument('-c', type=int, default=16)
    ap.add_argument('--out', default=os.path.join(os.path.dirname(__file__), 'results', 'gateway_latency.json'))
    a = ap.parse_args()
    secret = os.environ['GBI_GATEWAY_SIGN_SECRET']
    s = requests.Session()
    s.trust_env = False
    token = s.post(GW + '/auth/login', json={'username': 'east_ops', 'password': 'Gbi@2026'}).json()['accessToken']
    login_json = s.get(GW + '/auth/me', headers={'Authorization': 'Bearer ' + token}).text
    login = base64.urlsafe_b64encode(login_json.encode()).decode().rstrip('=')
    path = '/api/data-permission/me'

    def c():
        assert s.get(AGENT + '/echo/ok').status_code == 200

    def a_():
        ts = str(int(time.time() * 1000))
        sig = hmac.new(secret.encode(), f'GET\n{path}\n{ts}\nbench\n{login}'.encode(), hashlib.sha256).hexdigest()
        r = s.get(AGENT + path, headers={'X-Gbi-User-Key': 'bench', 'X-Gbi-Login': login, 'X-Gbi-Ts': ts, 'X-Gbi-Sign': sig})
        assert r.status_code == 200, r.text

    def b():
        r = s.get(GW + path, headers={'Authorization': 'Bearer ' + token})
        assert r.status_code == 200, r.text

    out = {}
    for conc in (1, a.c):
        out[f'c{conc}'] = {'C_direct_open': run(c, a.n, conc), 'A_direct_signed': run(a_, a.n, conc),
                           'B_via_gateway': run(b, a.n, conc)}
        r = out[f'c{conc}']
        r['gateway_overhead_p50_ms'] = round(r['B_via_gateway']['p50'] - r['A_direct_signed']['p50'], 2)
        r['gateway_overhead_p99_ms'] = round(r['B_via_gateway']['p99'] - r['A_direct_signed']['p99'], 2)
        r['agent_filter_p50_ms'] = round(r['A_direct_signed']['p50'] - r['C_direct_open']['p50'], 2)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(out, open(a.out, 'w'), indent=2)
    print(json.dumps(out, indent=2))


if __name__ == '__main__':
    main()
