// PayStream load test (k6).  Run:
//   k6 run --summary-export=summary.json k6/load_test.js
//   k6 run -e BASE_URL=http://localhost:8080 -e PAIRS=50 k6/load_test.js
//
// Two scenarios, run one after the other:
//   1. create_throughput - closed-loop POST /api/payments with PAIRS virtual users,
//      each with its OWN payer wallet (so you measure the system, not one wallet's row lock).
//   2. saga_e2e          - 10 payments/s; after each POST, poll GET until the payment is
//      APPROVED/REJECTED and record the end-to-end saga time (HTTP -> Kafka -> fraud -> Kafka -> settle).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import encoding from 'k6/encoding';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PAIRS = parseInt(__ENV.PAIRS || '50');
const THROUGHPUT_DURATION = __ENV.THROUGHPUT_DURATION || '60s';
const SAGA_RATE = parseInt(__ENV.SAGA_RATE || '10');
const POLL_SECONDS = parseInt(__ENV.POLL_MS || '250') / 1000;   // polling too fast is itself load
const ONLY = __ENV.ONLY || 'both';                              // 'throughput' | 'saga' | 'both'

const sagaE2E = new Trend('saga_e2e_ms', true);
const sagaStuck = new Counter('saga_not_terminal_after_30s');

const allScenarios = {
  create_throughput: {
    executor: 'constant-vus',
    exec: 'createOnly',
    vus: PAIRS,
    duration: THROUGHPUT_DURATION,
  },
  saga_e2e: {
    executor: 'constant-arrival-rate',
    exec: 'sagaFlow',
    rate: SAGA_RATE,
    timeUnit: '1s',
    duration: '60s',
    preAllocatedVUs: 50,
    maxVUs: 300,
    startTime: ONLY === 'saga' ? '0s' : '120s', // after scenario 1, let its Kafka backlog drain first
  },
};
const scenarios = {};
if (ONLY !== 'saga') scenarios.create_throughput = allScenarios.create_throughput;
if (ONLY !== 'throughput') scenarios.saga_e2e = allScenarios.saga_e2e;

export const options = {
  setupTimeout: '300s',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios,
  // These thresholds always pass; they exist only so k6 prints per-scenario sub-metrics.
  thresholds: {
    'http_req_duration{scenario:create_throughput}': ['max>=0'],
    'http_reqs{scenario:create_throughput}': ['count>=0'],
    'http_req_failed{scenario:create_throughput}': ['rate>=0'],
    'http_req_failed{scenario:saga_e2e}': ['rate>=0'],
    'saga_e2e_ms': ['max>=0'],
  },
};

const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };
const auth = (t) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` } });

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function jwtSub(token) {
  return JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's')).sub;
}

export function setup() {
  const pairs = [];
  for (let i = 0; i < PAIRS; i++) {
    const email = `load-${Date.now()}-${i}@example.com`;
    const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify({ email, password: 'Password123!' }), JSON_HDR);
    if (reg.status !== 201) throw new Error(`register failed: ${reg.status} ${reg.body}`);
    const token = reg.json('accessToken');
    const payerId = jwtSub(token);
    const payeeId = uuid();

    const w1 = http.post(`${BASE}/api/wallets`, JSON.stringify({ userId: payerId, initialBalance: 1000000000, currency: 'BRL' }), auth(token));
    const w2 = http.post(`${BASE}/api/wallets`, JSON.stringify({ userId: payeeId, initialBalance: 0, currency: 'BRL' }), auth(token));
    if (w1.status !== 201 || w2.status !== 201) throw new Error(`wallet create failed: ${w1.status}/${w2.status}`);
    pairs.push({ token, payerId, payeeId });
  }
  return pairs;
}

function createPayment(p) {
  const body = {
    payeeId: p.payeeId,
    amount: 1,
    currency: 'BRL',
    idempotencyKey: `k-${__VU}-${__ITER}-${Date.now()}`, // <= 64 chars
  };
  return http.post(`${BASE}/api/payments`, JSON.stringify(body), auth(p.token));
}

export function createOnly(pairs) {
  const p = pairs[(__VU - 1) % pairs.length];
  const res = createPayment(p);
  check(res, { 'create returned 202': (r) => r.status === 202 });
}

export function sagaFlow(pairs) {
  const p = pairs[Math.floor(Math.random() * pairs.length)];
  const start = Date.now();
  const res = createPayment(p);
  if (!check(res, { 'create returned 202': (r) => r.status === 202 })) return;

  const id = res.json('id');
  const deadline = start + 30000;
  while (Date.now() < deadline) {
    const g = http.get(`${BASE}/api/payments/${id}`, auth(p.token));
    const st = g.status === 200 ? g.json('status') : null;
    if (st === 'APPROVED' || st === 'REJECTED') {
      sagaE2E.add(Date.now() - start);
      return;
    }
    sleep(POLL_SECONDS);
  }
  sagaStuck.add(1);
}
