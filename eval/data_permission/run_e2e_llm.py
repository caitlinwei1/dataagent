#!/usr/bin/env python3
"""End-to-end with the real model: the same questions asked by users with different permissions, through the gateway.
Records what each user's run recalled, generated, executed and returned, plus one cross-user thread takeover attempt.

Usage: python3 eval/data_permission/run_e2e_llm.py --agent 5
Writes eval/data_permission/results/e2e_llm.json
"""
import argparse
import json
import os
import re
import time
import uuid

import requests

GW = 'http://localhost:8080'
S = requests.Session()
S.trust_env = False
CASES = [
    ('east_ops', '2026年3月各店铺的GMV分别是多少？'),
    ('shop_ops', '2026年3月各店铺的GMV分别是多少？'),
    ('admin', '2026年3月各店铺的GMV分别是多少？'),
    ('sh_wh', '2026年3月各店铺的GMV分别是多少？'),
    ('sh_wh', '2026年3月各仓库的出库单数量是多少？'),
    ('east_ops', '2026年3月各仓库的出库单数量是多少？'),
]


def token(user):
    return S.post(GW + '/auth/login', json={'username': user, 'password': 'Gbi@2026'}).json()['accessToken']


def ask(tok, agent, q, thread):
    t0 = time.time()
    r = S.get(GW + '/api/stream/search', params={'agentId': agent, 'query': q, 'threadId': thread, 'nl2sqlOnly': 'false',
                                                 'humanFeedback': 'false'},
              headers={'Authorization': 'Bearer ' + tok}, stream=True, timeout=900)
    if r.status_code != 200:
        return {'http': r.status_code, 'body': r.text[:300]}
    chunks = []
    for line in r.iter_lines(decode_unicode=True):
        if line and line.startswith('data:'):
            try:
                chunks.append(json.loads(line[5:]))
            except ValueError:
                pass
    text = ''.join(c.get('text') or '' for c in chunks)
    sql_blocks = re.findall(r'```sql\s*(.*?)```', text, re.S)
    out = {'seconds': round(time.time() - t0, 1), 'nodes': sorted({c.get('nodeName') for c in chunks if c.get('nodeName')})}
    m = re.search(r'表名: ([^\n]*)', text)
    out['recalled_tables'] = m.group(1).strip() if m else None
    out['sql'] = [s.strip() for s in sql_blocks][-3:]
    out['permission_notes'] = sorted(set(re.findall(r'已按数据权限过滤：[^\n]*|当前用户无权查询表：[^\n，]*', text)))
    rs = re.findall(r'\{"resultSet".*?\}\}', text)
    out['result_sets'] = []
    for blob in re.findall(r'"data":(\[.*?\])', text):
        try:
            out['result_sets'].append(json.loads(blob))
        except ValueError:
            pass
    out['tail'] = text[-400:]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--agent', default='5')
    a = ap.parse_args()
    res = []
    threads = {}
    for user, q in CASES:
        th = f'e2e-{user}-{uuid.uuid4().hex[:8]}'
        threads.setdefault(user, th)
        r = ask(token(user), a.agent, q, th)
        r.update(user=user, question=q, thread=th)
        print(user, q, r.get('seconds'), r.get('permission_notes'), r.get('recalled_tables'))
        res.append(r)
    # east_ops tries to approve / continue sh_wh's thread
    r = S.get(GW + '/api/stream/search', params={'agentId': a.agent, 'query': 'x', 'threadId': threads['sh_wh'],
                                                 'humanFeedback': 'true', 'humanFeedbackContent': '同意'},
              headers={'Authorization': 'Bearer ' + token('east_ops')}, timeout=60)
    takeover = {'status': r.status_code, 'body': r.text[:200]}
    r = S.get(GW + '/api/stream/search', params={'agentId': a.agent, 'query': '再看看2月', 'threadId': threads['sh_wh']},
              headers={'Authorization': 'Bearer ' + token('east_ops')}, timeout=60)
    takeover_new = {'status': r.status_code, 'body': r.text[:200]}
    print('takeover', takeover, takeover_new)
    out = os.path.join(os.path.dirname(__file__), 'results', 'e2e_llm.json')
    json.dump({'runs': res, 'thread_takeover_resume': takeover, 'thread_takeover_new_turn': takeover_new}, open(out, 'w'),
              ensure_ascii=False, indent=2)


if __name__ == '__main__':
    main()
