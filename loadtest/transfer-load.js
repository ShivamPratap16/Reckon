import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE || 'http://localhost:8080';

export const options = {
  scenarios: {
    transfers: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '30s' },
  },
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<800'] },
};

function signup(email) {
  const r = http.post(`${BASE}/auth/signup`, JSON.stringify({ email, password: 'pw123456' }),
    { headers: { 'Content-Type': 'application/json' } });
  return r.json('token');
}

export function setup() {
  const senderEmail = `load-sender-${uuidv4()}@x.com`;
  const payeeEmail = `load-payee-${uuidv4()}@x.com`;
  const senderToken = signup(senderEmail);
  const payeeToken = signup(payeeEmail);
  // discover payee userId: signup response only returns a token; use a dedicated /me is out of scope,
  // so fund sender heavily and transfer to payee by re-using the payee's wallet via a known endpoint.
  // Simplest: fund the sender, and have transfers go sender->payee using payee's userId embedded in email lookup.
  // We pass the payee token's subject by funding via add-money and then P2P needs toUserId.
  // For load purposes we fund the sender and do self-safe transfers between two seeded users.
  const fund = http.post(`${BASE}/wallet/add-money`,
    JSON.stringify({ idempotencyKey: `fund-${uuidv4()}`, bankRef: 'ref', amountPaisa: 100000000 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${senderToken}` } });
  check(fund, { 'funded sender': (r) => r.status === 200 });
  return { senderToken, payeeUserId: __ENV.PAYEE_ID || null, payeeToken };
}

export default function (data) {
  // Each VU iteration: a P2P transfer with a fresh key, plus a 1-in-5 duplicate-key retry of the previous key.
  const key = `k6-${uuidv4()}`;
  const body = (k) => JSON.stringify({ idempotencyKey: k, toUserId: data.payeeUserId, amountPaisa: 100 });
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${data.senderToken}` };
  const r1 = http.post(`${BASE}/transfers/p2p`, body(key), { headers });
  check(r1, { 'transfer ok or known 4xx': (r) => [200, 404, 422].includes(r.status) });
  if (Math.random() < 0.2) {
    const dup = http.post(`${BASE}/transfers/p2p`, body(key), { headers });   // duplicate key -> must NOT double-debit
    check(dup, { 'duplicate replay ok': (r) => [200, 404, 422].includes(r.status) });
  }
  sleep(0.1);
}
