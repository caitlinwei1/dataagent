#!/usr/bin/env python3
"""Data-permission eval, all through the gateway (login -> JWT -> gateway signature -> gbi-agent -> MySQL).

1. equivalence: the 276 gold / key SQLs of eval/nl2sql (44 multi-step questions have none) x 6 users. For each pair:
   - the gold SQL touches a table the user is not granted  -> must be blocked
   - otherwise                                              -> must run, and the rows must equal the same gold SQL
     run on a physically scoped copy of the database (gbi_scope_<user>: one view per table, restricted tables
     filtered). The copy uses MySQL views, not the JSqlParser rewrite, so it checks the rewrite independently.
     Where a filter was applied it also records whether the unfiltered result differs (so a match is not vacuous).
2. attacks: eval/data_permission/attacks.yaml (block / no_leak).
3. http: forged headers, tampered / logged-out tokens, direct calls that skip the gateway, function permissions.

Usage: python3 eval/data_permission/run_permission_eval.py --agent 5 [--gateway http://localhost:8080] [--limit N]
Writes eval/data_permission/results/<tag>/{summary.json,cases.jsonl}.
"""
import argparse
import base64
import collections
import json
import os
import re
import subprocess
import sys
import time

import requests
import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
USERS = ['admin', 'east_ops', 'sh_wh', 'finance', 'shop_ops', 'auditor']
PASSWORD = 'Gbi@2026'
BASE_DB = 'gbi_ecommerce'
MYSQL = os.environ.get('MYSQL_CONTAINER', 'data-agent-mysql-datasource')
S = requests.Session()
S.trust_env = False


def mysql(sql, db=BASE_DB):
    r = subprocess.run(['docker', 'exec', '-i', MYSQL, 'mysql', '--default-character-set=utf8mb4', '-uroot', '-proot',
                        '-B', db], input=sql, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip().splitlines()[-1] if r.stderr.strip() else 'mysql failed')
    lines = r.stdout.splitlines()
    if not lines:
        return [], []
    return lines[0].split('\t'), [l.split('\t') for l in lines[1:]]


def login(gw, user):
    r = S.post(gw + '/auth/login', json={'username': user, 'password': PASSWORD}, timeout=30)
    r.raise_for_status()
    return r.json()['accessToken']


def me(gw, token):
    return S.get(gw + '/auth/me', headers={'Authorization': 'Bearer ' + token}, timeout=30).json()


# ------------------------------------------------------------ physically scoped copies
def build_scope_db(user, scope, policies, tables):
    db = 'gbi_scope_' + user
    stmts = [f'DROP DATABASE IF EXISTS {db}', f'CREATE DATABASE {db}']
    for t in tables:
        p = policies.get(t)
        if p is None or scope['allRows']:
            stmts.append(f'CREATE VIEW {db}.{t} AS SELECT * FROM {BASE_DB}.{t}')
            continue
        vals = scope['dims'].get(p['dim'], [])
        vals = [v for v in vals if re.fullmatch(r'-?\d+', v)]
        cond = '1 = 0' if not vals else p['filter'].replace(':' + p['dim'], ', '.join(vals))
        cond = re.sub(r'\bFROM (\w+)', lambda m: f'FROM {BASE_DB}.{m.group(1)}', cond)
        stmts.append(f'CREATE VIEW {db}.{t} AS SELECT * FROM {BASE_DB}.{t} WHERE {cond}')
    mysql(';\n'.join(stmts) + ';')
    return db


def allowed(scope, table):
    if scope['allTables']:
        return True
    for p in scope['tablePatterns']:
        if (p.endswith('*') and table.startswith(p[:-1])) or table == p:
            return True
    return False


def tables_of(sql, all_tables):
    words = set(w.lower() for w in re.findall(r'[A-Za-z_][A-Za-z0-9_]*', sql))
    return sorted(t for t in all_tables if t in words)


# ------------------------------------------------------------ comparison
def norm(v):
    # the agent's ResultSetBuilder renders SQL NULL as '' while mysql -B prints NULL
    if v is None or v == 'NULL' or v == '':
        return None
    s = str(v).strip()
    if re.fullmatch(r'\d{4}-\d{2}-\d{2}[ T]00:00:00(\.0+)?', s):
        s = s[:10]
    try:
        f = float(s)
        return round(f, 4)
    except ValueError:
        return s


def same_rows(a, b):
    key = lambda r: tuple((0, x) if isinstance(x, (int, float)) else (1, str(x)) for x in r)
    return sorted(map(lambda r: tuple(norm(x) for x in r), a), key=key) == \
        sorted(map(lambda r: tuple(norm(x) for x in r), b), key=key)


def preview(gw, token, agent, sql):
    r = S.post(gw + '/api/data-permission/preview', headers={'Authorization': 'Bearer ' + token},
               json={'agentId': agent, 'sql': sql, 'execute': True}, timeout=300)
    if r.status_code != 200:
        return {'http': r.status_code, 'passed': False, 'message': r.text[:200]}
    return r.json()


def api_rows(res):
    cols = res.get('columns') or []
    return [[row.get(c) for c in cols] for row in (res.get('rows') or [])]


def judge(case, res, scope_db):
    """Returns (verdict, detail). verdict in ok_block / ok_match / leak_or_wrong / false_block / error."""
    if case['expect'] == 'block':
        return ('ok_block', res.get('message')) if not res.get('passed') else ('leak_or_wrong', 'not blocked')
    if not res.get('passed') or res.get('http'):
        # an attack that is stopped (e.g. by Druid's WallFilter on the datasource) has not leaked anything;
        # a legitimate gold query that is stopped is a false block
        detail = f"{res.get('check')}: {res.get('message')}"
        return ('ok_block', detail) if case['set'] == 'attack' else ('false_block', detail)
    if res.get('error'):
        return 'error', res['error'][:200]
    try:
        _, truth = mysql(case['sql'], scope_db)
    except RuntimeError as e:
        return 'error', 'truth: ' + str(e)[:200]
    if not same_rows(api_rows(res), truth):
        return 'leak_or_wrong', 'rows differ'
    if res.get('filteredTables'):
        # non-vacuous check: would the unfiltered query have returned something else?
        _, full = mysql(case['sql'])
        return 'ok_match', 'filter changed the result' if not same_rows(full, truth) else 'filter did not change the result'
    return 'ok_match', None


# ------------------------------------------------------------ http-level checks
def http_checks(gw, agent_url, tokens):
    out = []

    def add(name, ok, detail):
        out.append({'id': name, 'ok': bool(ok), 'detail': detail})

    east = tokens['east_ops']
    # 1 no token / garbage / wrong-key JWT
    add('H01 无令牌', S.get(gw + '/api/data-permission/me').status_code == 401, 'expect 401')
    add('H02 乱写令牌', S.get(gw + '/api/data-permission/me', headers={'Authorization': 'Bearer abc.def.ghi'}).status_code == 401, 'expect 401')
    h, p, sig = east.split('.')
    pay = json.loads(base64.urlsafe_b64decode(p + '=='))
    pay['user_id'] = 1
    p2 = base64.urlsafe_b64encode(json.dumps(pay).encode()).decode().rstrip('=')
    add('H03 篡改令牌载荷', S.get(gw + '/api/data-permission/me', headers={'Authorization': f'Bearer {h}.{p2}.{sig}'}).status_code == 401, 'user_id 2 -> 1, signature unchanged')
    # 2 forged identity headers through the gateway are stripped: still east_ops, still restricted
    forged = base64.urlsafe_b64encode(json.dumps({'userId': 1, 'userName': 'admin', 'permissions': ['*:*:*'],
                                                  'dataScope': {'allTables': True, 'allRows': True}}).encode()).decode()
    r = S.get(gw + '/api/data-permission/me', headers={'Authorization': 'Bearer ' + east, 'X-Gbi-Login': forged,
                                                       'X-Gbi-User-Id': '1', 'from-source': 'inner'}).json()
    add('H04 经网关伪造身份头', r.get('user') == 'east_ops' and not r['dataPermission']['allRows'], r.get('describe'))
    # 3 skip the gateway: forged headers straight to the agent
    r = S.get(agent_url + '/api/data-permission/me', headers={'X-Gbi-User-Key': 'x', 'X-Gbi-Login': forged,
                                                              'X-Gbi-Ts': str(int(time.time() * 1000)), 'X-Gbi-Sign': '0' * 64})
    add('H05 绕过网关直连 agent', r.status_code == 401, f'status {r.status_code}')
    r = S.post(agent_url + '/api/data-permission/preview', json={'agentId': 5, 'sql': 'SELECT COUNT(*) FROM trd_order', 'execute': True})
    add('H06 直连 agent 不带任何头', r.status_code == 401, f'status {r.status_code}')
    # 4 function permissions
    r = S.post(gw + '/openapi/v1/reports', headers={'Authorization': 'Bearer ' + tokens['sh_wh']}, json={'agentId': '5', 'question': 'x'})
    add('H07 无报告权限调报告接口', r.status_code == 403, f'status {r.status_code}')
    r = S.delete(gw + '/api/agent/5', headers={'Authorization': 'Bearer ' + east})
    add('H08 非管理员删智能体', r.status_code == 403, f'status {r.status_code}')
    r = S.get(gw + '/api/model-config/list', headers={'Authorization': 'Bearer ' + east})
    add('H09 非管理员读模型配置（含 API Key）', r.status_code == 403, f'status {r.status_code}')
    # 5 logout kills the token at once
    t = login(gw, 'shop_ops')
    S.delete(gw + '/auth/logout', headers={'Authorization': 'Bearer ' + t})
    add('H10 注销后旧令牌', S.get(gw + '/api/data-permission/me', headers={'Authorization': 'Bearer ' + t}).status_code == 401, 'expect 401')
    # 6 someone else's report / thread
    r = S.get(gw + '/openapi/v1/reports/not-mine-thread', headers={'Authorization': 'Bearer ' + east})
    add('H11 读别人的报告（未知 threadId）', r.status_code == 403, f'status {r.status_code}')
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--agent', type=int, default=5)
    ap.add_argument('--gateway', default='http://localhost:8080')
    ap.add_argument('--agent-url', default='http://localhost:8065')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--tag', default='full')
    a = ap.parse_args()
    out_dir = os.path.join(ROOT, 'eval/data_permission/results', a.tag)
    os.makedirs(out_dir, exist_ok=True)

    policies = yaml.safe_load(open(os.path.join(ROOT, 'gbi-agent/src/main/resources/data-permission/row-policies.yml')))['tables']
    all_tables = [t['name'] for t in json.load(open(os.path.join(ROOT, 'dataset/metadata/tables.json')))]
    tokens = {u: login(a.gateway, u) for u in USERS}
    scopes = {u: me(a.gateway, tokens[u])['dataScope'] for u in USERS}
    scope_dbs = {u: build_scope_db(u, scopes[u], policies, all_tables) for u in USERS}

    qs = [json.loads(l) for l in open(os.path.join(ROOT, 'eval/nl2sql/questions.jsonl'))]
    if a.limit:
        qs = qs[:a.limit]
    cases = []
    for q in qs:
        sql = q.get('gold_sql') or q.get('key_sql')
        if not sql:  # 44 multi-step questions are scored on the report, not on one SQL
            continue
        used = tables_of(sql, all_tables)
        for u in USERS:
            exp = 'block' if not all(allowed(scopes[u], t) for t in used) else 'no_leak'
            cases.append({'id': f"{q['id']}@{u}", 'set': 'gold', 'user': u, 'sql': sql, 'expect': exp,
                          'tables': used, 'restricted': [t for t in used if t in policies]})
    for c in yaml.safe_load(open(os.path.join(ROOT, 'eval/data_permission/attacks.yaml'))):
        cases.append(dict(c, set='attack'))

    lat = []
    with open(os.path.join(out_dir, 'cases.jsonl'), 'w') as f:
        for i, c in enumerate(cases):
            t0 = time.perf_counter()
            res = preview(a.gateway, tokens[c['user']], a.agent, c['sql'])
            lat.append(time.perf_counter() - t0)
            c['verdict'], c['detail'] = judge(c, res, scope_dbs[c['user']])
            c['filtered'] = res.get('filteredTables')
            c['rows'] = len(res.get('rows') or [])
            f.write(json.dumps(c, ensure_ascii=False) + '\n')
            if (i + 1) % 100 == 0:
                print(f'{i + 1}/{len(cases)}', file=sys.stderr)

    http = http_checks(a.gateway, a.agent_url, tokens)
    summary = {'cases': len(cases)}
    for s in ['gold', 'attack']:
        cs = [c for c in cases if c['set'] == s]
        v = collections.Counter(c['verdict'] for c in cs)
        should_block = [c for c in cs if c['expect'] == 'block']
        may_run = [c for c in cs if c['expect'] == 'no_leak']
        summary[s] = {
            'total': len(cs), 'verdicts': dict(v),
            'should_block': len(should_block), 'blocked': sum(c['verdict'] == 'ok_block' for c in should_block),
            'may_run': len(may_run), 'matched_scoped_truth': sum(c['verdict'] == 'ok_match' for c in may_run),
            'may_run_with_row_filter': sum(1 for c in may_run if c.get('filtered')),
            'row_filter_changed_result': sum(1 for c in may_run if c.get('detail') == 'filter changed the result'),
            'false_block': sum(c['verdict'] == 'false_block' for c in may_run),
            'leak_or_wrong': sum(c['verdict'] == 'leak_or_wrong' for c in cs),
        }
        by_user = collections.defaultdict(collections.Counter)
        for c in cs:
            by_user[c['user']][c['verdict']] += 1
        summary[s]['by_user'] = {u: dict(x) for u, x in by_user.items()}
    summary['http'] = {'total': len(http), 'ok': sum(h['ok'] for h in http), 'checks': http}
    lat.sort()
    summary['preview_latency_ms'] = {'p50': round(lat[len(lat) // 2] * 1000, 1), 'p95': round(lat[int(len(lat) * .95)] * 1000, 1)}
    json.dump(summary, open(os.path.join(out_dir, 'summary.json'), 'w'), ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in summary.items() if k != 'http'} | {'http': f"{summary['http']['ok']}/{summary['http']['total']}"}, ensure_ascii=False, indent=2))
    for h in http:
        if not h['ok']:
            print('HTTP FAIL', h)


if __name__ == '__main__':
    main()
