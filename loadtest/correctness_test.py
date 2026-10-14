#!/usr/bin/env python3
"""
PayStream correctness tests - run against the full docker-compose stack.

    pip install requests
    python correctness_test.py                      # default: http://localhost:8080
    python correctness_test.py --base http://localhost:8080 --concurrency 100

Every test creates its own fresh users/wallets, so you can re-run freely.
Results are printed and also written to results.json.
"""
import argparse
import base64
import json
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

import requests
from requests.adapters import HTTPAdapter

PASSWORD = "Password123!"
CURRENCY = "BRL"
TERMINAL = {"APPROVED", "REJECTED", "CANCELLED"}
REQ_TIMEOUT = 60

# Opening hundreds of brand-new TCP connections in the same instant overflows the listen backlog on
# Windows / Docker Desktop and shows up as ConnectionResetError. Reuse connections and cap what is in flight.
MAX_INFLIGHT = 64
SESSION = requests.Session()
SESSION.mount("http://", HTTPAdapter(pool_connections=4, pool_maxsize=MAX_INFLIGHT))
SESSION.mount("https://", HTTPAdapter(pool_connections=4, pool_maxsize=MAX_INFLIGHT))
INFLIGHT = threading.BoundedSemaphore(MAX_INFLIGHT)
_stats_lock = threading.Lock()
STATS = {"connection_retries": 0}


def _request(method, url, retries=0, **kw):
    """Only pass retries>0 for calls that are safe to repeat (GETs, and POST /payments with the same
    idempotency key - which is exactly what idempotency keys exist for)."""
    kw.setdefault("timeout", REQ_TIMEOUT)
    for attempt in range(retries + 1):
        try:
            with INFLIGHT:
                return SESSION.request(method, url, **kw)
        except requests.ConnectionError:
            if attempt == retries:
                raise
            with _stats_lock:
                STATS["connection_retries"] += 1
            time.sleep(0.2 * (attempt + 1))


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def jwt_sub(token: str) -> str:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def hdr(token):
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def register(self):
        email = f"ct-{uuid.uuid4().hex[:12]}@example.com"
        r = _request("POST", f"{self.base}/api/auth/register",
                     json={"email": email, "password": PASSWORD})
        r.raise_for_status()
        token = r.json()["accessToken"]
        return token, jwt_sub(token)

    def create_wallet(self, token, user_id, balance):
        r = _request("POST", f"{self.base}/api/wallets", headers=hdr(token),
                     json={"userId": user_id, "initialBalance": balance, "currency": CURRENCY})
        r.raise_for_status()

    def wallet(self, token, user_id):
        r = _request("GET", f"{self.base}/api/wallets/{user_id}", headers=hdr(token), retries=3)
        r.raise_for_status()
        return r.json(parse_float=Decimal)

    def pay(self, token, payee_id, amount, key):
        try:
            r = _request("POST", f"{self.base}/api/payments", headers=hdr(token), retries=3,
                         json={"payeeId": payee_id, "amount": amount,
                               "currency": CURRENCY, "idempotencyKey": key})
        except requests.ConnectionError:
            return 0, {}  # still unreachable after retries: reported as a connection error, not a crash
        try:
            body = r.json()
        except ValueError:
            body = {}
        return r.status_code, body

    def payment(self, token, payment_id):
        r = _request("GET", f"{self.base}/api/payments/{payment_id}", headers=hdr(token), retries=3)
        r.raise_for_status()
        return r.json()

    def wait_terminal(self, token, payment_ids, timeout=90):
        """Poll until every payment is APPROVED/REJECTED. Returns {id: status}."""
        deadline = time.time() + timeout
        pending, done = set(payment_ids), {}
        while pending and time.time() < deadline:
            def check(pid):
                return pid, self.payment(token, pid)["status"]
            with ThreadPoolExecutor(min(32, len(pending))) as ex:
                for pid, st in ex.map(check, list(pending)):
                    if st in TERMINAL:
                        done[pid] = st
                        pending.discard(pid)
            if pending:
                time.sleep(0.3)
        for pid in pending:
            done[pid] = "STUCK"
        return done


def fire_concurrently(fns):
    """Run all callables at the same instant (barrier) and return their results."""
    barrier = threading.Barrier(len(fns))

    def wrap(fn):
        barrier.wait()
        return fn()

    with ThreadPoolExecutor(len(fns)) as ex:
        return list(ex.map(wrap, fns))


def setup_pair(api, payer_balance, payee_balance=0):
    token, payer = api.register()
    payee = str(uuid.uuid4())
    api.create_wallet(token, payer, payer_balance)
    api.create_wallet(token, payee, payee_balance)
    return token, payer, payee


RESULTS = []


def record(name, passed, details):
    RESULTS.append({"test": name, "passed": passed, **details})
    print(f"\n[{'PASS' if passed else 'FAIL'}] {name}")
    for k, v in details.items():
        print(f"    {k}: {v}")


# --------------------------------------------------------------------------- #
# tests
# --------------------------------------------------------------------------- #
def test_idempotency_race(api, n):
    """N simultaneous requests, SAME idempotency key -> exactly one payment, one debit."""
    token, payer, payee = setup_pair(api, payer_balance=1000)
    key = f"race-{uuid.uuid4().hex[:16]}"
    amount = 100

    results = fire_concurrently([lambda: api.pay(token, payee, amount, key) for _ in range(n)])
    codes = [c for c, _ in results]
    ids = {b.get("id") for c, b in results if c == 202}

    final = api.wait_terminal(token, list(ids))
    w_payer, w_payee = api.wallet(token, payer), api.wallet(token, payee)

    ok = (
        all(c == 202 for c in codes)
        and len(ids) == 1
        and list(final.values()) == ["APPROVED"]
        and w_payer["balance"] == 900
        and w_payer["reservedBalance"] == 0
        and w_payee["balance"] == 100
    )
    record("Idempotency under concurrent duplicates", ok, {
        "concurrent_requests": n,
        "http_202_count": codes.count(202),
        "other_status_codes": sorted({c for c in codes if c != 202}),
        "distinct_payment_ids": len(ids),
        "payer_balance (expect 900)": w_payer["balance"],
        "payer_reserved (expect 0)": w_payer["reservedBalance"],
        "payee_balance (expect 100)": w_payee["balance"],
    })


def test_idempotency_conflict(api):
    """Same key, DIFFERENT amount -> must be rejected with a 4xx, not processed, not a 500."""
    token, payer, payee = setup_pair(api, payer_balance=1000)
    key = f"conflict-{uuid.uuid4().hex[:16]}"
    c1, b1 = api.pay(token, payee, 100, key)
    c2, _ = api.pay(token, payee, 250, key)
    api.wait_terminal(token, [b1["id"]])
    w = api.wallet(token, payer)
    ok = c1 == 202 and c2 in (409, 422) and w["balance"] == 900
    record("Idempotency key reuse with different payload is rejected", ok, {
        "first_status": c1,
        "second_status (expect 409/422)": c2,
        "payer_balance (expect 900)": w["balance"],
        "note": "500 means the running payment-service is not returning the 409 from GlobalExceptionHandler: "
                "check the stack trace with `docker compose logs payment-service` and confirm the image was "
                "rebuilt from the patched code" if c2 == 500 else "",
    })


def test_no_overspend(api, n):
    """Balance covers exactly 100 payments; fire n=2x that concurrently with distinct keys."""
    amount = 10
    affordable = 100
    token, payer, payee = setup_pair(api, payer_balance=amount * affordable, payee_balance=0)
    initial_total = Decimal(amount * affordable)

    keys = [f"ovr-{uuid.uuid4().hex[:16]}" for _ in range(n)]  # fixed up front so a retry reuses its key
    results = fire_concurrently([lambda i=i: api.pay(token, payee, amount, keys[i]) for i in range(n)])
    server_errors = [c for c, _ in results if c >= 500 or c == 0]
    accepted = [b["id"] for c, b in results if c == 202 and b.get("status") != "REJECTED"]
    rejected_at_creation = sum(1 for c, b in results if c == 202 and b.get("status") == "REJECTED")

    final = api.wait_terminal(token, accepted)
    approved = sum(1 for s in final.values() if s == "APPROVED")
    stuck = sum(1 for s in final.values() if s == "STUCK")
    w_payer, w_payee = api.wallet(token, payer), api.wallet(token, payee)

    conserved = w_payer["balance"] + w_payee["balance"] == initial_total
    ok = (
        not server_errors
        and approved == affordable
        and rejected_at_creation == n - affordable
        and stuck == 0
        and w_payer["balance"] == 0
        and w_payer["reservedBalance"] == 0
        and w_payee["balance"] == initial_total
        and conserved
    )
    record("No overspend under concurrent payments from one wallet", ok, {
        "concurrent_payments": n,
        f"approved (expect {affordable})": approved,
        f"rejected_insufficient_funds (expect {n - affordable})": rejected_at_creation,
        "http_5xx_or_unreachable": len(server_errors),
        "connection_retries_used": STATS["connection_retries"],
        "stuck_payments": stuck,
        "payer_balance (expect 0)": w_payer["balance"],
        "payer_reserved (expect 0)": w_payer["reservedBalance"],
        "payee_balance (expect 1000)": w_payee["balance"],
        "money_conserved": conserved,
        "note": "payer_reserved > 0 after everything is terminal = leaked reservation "
                "(wallet reserve committed but payment tx rolled back)" if w_payer["reservedBalance"] != 0 else "",
    })


def test_fraud_rejection_releases_funds(api):
    """Amount above the 50,000 fraud threshold -> REJECTED and reserved funds released."""
    token, payer, payee = setup_pair(api, payer_balance=100000)
    code, body = api.pay(token, payee, 60000, f"fraud-{uuid.uuid4().hex[:16]}")
    final = api.wait_terminal(token, [body["id"]])
    w_payer, w_payee = api.wallet(token, payer), api.wallet(token, payee)
    ok = (
        code == 202
        and final[body["id"]] == "REJECTED"
        and w_payer["balance"] == 100000
        and w_payer["reservedBalance"] == 0
        and w_payee["balance"] == 0
    )
    record("Fraud rejection compensates (releases reserved funds)", ok, {
        "final_status (expect REJECTED)": final[body["id"]],
        "payer_balance (expect 100000)": w_payer["balance"],
        "payer_reserved (expect 0)": w_payer["reservedBalance"],
        "payee_balance (expect 0)": w_payee["balance"],
    })


def test_rate_limit(api, burst, replenish):
    """(1) The limiter must be wired in: Spring Cloud Gateway adds X-RateLimit-* headers to every response.
    (2) One user firing far more than the bucket holds must get 429s, and the number let through must be
    about burst + replenish * elapsed (token bucket), not unlimited.

    Run the gateway with a small bucket so the test is deterministic:
        RATE_LIMIT_BURST=20 RATE_LIMIT_REPLENISH=1   and   --rate-burst 20 --rate-replenish 1
    """
    token, user = api.register()
    url = f"{api.base}/api/wallets/{user}"

    probe = _request("GET", url, headers=hdr(token))
    advertised = probe.headers.get("X-RateLimit-Burst-Capacity")
    advertised_rate = probe.headers.get("X-RateLimit-Replenish-Rate")
    limiter_active = advertised is not None

    n = max(burst * 15, 300)
    t0 = time.time()

    def one():
        try:
            return _request("GET", url, headers=hdr(token)).status_code
        except requests.ConnectionError:
            return 0

    codes = fire_concurrently([one for _ in range(n)])
    elapsed = time.time() - t0
    limited = codes.count(429)
    passed_through = sum(1 for c in codes if c not in (0, 429))
    ceiling = burst + replenish * elapsed + 5
    in_range = burst * 0.9 <= passed_through + 1 <= ceiling

    ok = limiter_active and str(advertised) == str(burst) and limited > 0 and in_range
    note = ""
    if not limiter_active:
        note = ("no X-RateLimit-* headers: the RequestRateLimiter filter is not active, so the gateway "
                "image does not contain the new code (rebuild it)")
    elif str(advertised) != str(burst):
        note = f"gateway advertises burst {advertised}, test expects {burst}: pass matching --rate-burst/--rate-replenish"
    elif limited == 0:
        note = "limiter is active but nothing was rejected: raise the request count or lower RATE_LIMIT_BURST"
    record("Gateway rate limit enforced (token bucket)", ok, {
        "limiter_headers_present": limiter_active,
        "advertised_burst_capacity": advertised,
        "advertised_replenish_rate": advertised_rate,
        "requests_sent": n,
        "allowed": passed_through + 1,
        "rejected_429": limited,
        "elapsed_s": round(elapsed, 2),
        "expected_allowed_range": f"{int(burst * 0.9)} .. {int(ceiling)}",
        "note": note,
    })


# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--concurrency", type=int, default=100,
                    help="concurrent requests for the race tests (the overspend test uses 2x this)")
    ap.add_argument("--rate-burst", type=int, default=100, help="gateway RATE_LIMIT_BURST")
    ap.add_argument("--rate-replenish", type=int, default=50, help="gateway RATE_LIMIT_REPLENISH (req/s)")
    ap.add_argument("--skip-rate-limit", action="store_true")
    ap.add_argument("--only", help="comma-separated tests to run: idempotency_race, idempotency_conflict, "
                                   "no_overspend, fraud_rejection_compensation, rate_limit")
    args = ap.parse_args()
    api = Api(args.base)

    tests = [
        ("idempotency_race", lambda: test_idempotency_race(api, args.concurrency)),
        ("idempotency_conflict", lambda: test_idempotency_conflict(api)),
        ("no_overspend", lambda: test_no_overspend(api, args.concurrency * 2)),
        ("fraud_rejection_compensation", lambda: test_fraud_rejection_releases_funds(api)),
    ]
    if not args.skip_rate_limit:
        tests.append(("rate_limit", lambda: test_rate_limit(api, args.rate_burst, args.rate_replenish)))
    if args.only:
        wanted = {x.strip() for x in args.only.split(",")}
        tests = [(n, t) for n, t in tests if n in wanted]
        if not tests:
            sys.exit(f"--only matched no tests: {args.only}")
    for name, t in tests:
        try:
            t()
        except Exception as e:  # keep going so one failure doesn't hide the rest
            record(name, False, {"error": repr(e)})

    passed = sum(1 for r in RESULTS if r["passed"])
    print(f"\n==== {passed}/{len(RESULTS)} tests passed ====")
    with open("results.json", "w") as f:
        json.dump(RESULTS, f, indent=2, default=str)
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
