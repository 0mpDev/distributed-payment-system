#!/usr/bin/env python3
"""
Proves the leaked-reservation fix: payments created while wallet-service is DOWN are accepted as PENDING
and completed by the recovery job once the wallet is back, with no funds left reserved.

    python recovery_demo.py --compose-dir C:\\path\\to\\repo\\infra\\docker
"""
import argparse
import subprocess
import sys
import time
import uuid

from correctness_test import Api, TERMINAL, setup_pair


def compose(cwd, *args):
    subprocess.run(["docker", "compose", *args], cwd=cwd, check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--compose-dir", required=True, help="folder containing docker-compose.yml")
    ap.add_argument("--timeout", type=int, default=240)
    args = ap.parse_args()

    api = Api(args.base)
    token, payer, payee = setup_pair(api, payer_balance=1000)

    print("stopping wallet-service ...")
    compose(args.compose_dir, "stop", "wallet-service")
    t0 = time.time()
    try:
        code, body = api.pay(token, payee, 100, f"recovery-{uuid.uuid4().hex[:12]}")
    finally:
        print("starting wallet-service ...")
        compose(args.compose_dir, "start", "wallet-service")
    accepted_pending = code == 202 and body.get("status") == "PENDING"
    print(f"payment created while wallet was down -> HTTP {code}, status={body.get('status')}")
    if code != 202:
        print("FAIL: payment was not accepted while the wallet was down")
        sys.exit(1)

    pid = body["id"]
    status = body.get("status")
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        try:
            status = api.payment(token, pid)["status"]
        except Exception:
            status = "?"
        if status in TERMINAL:
            break
        time.sleep(2)
    elapsed = time.time() - t0

    w_payer = w_payee = None
    for _ in range(60):  # wallet may still be warming up
        try:
            w_payer, w_payee = api.wallet(token, payer), api.wallet(token, payee)
            break
        except Exception:
            time.sleep(2)

    ok = (accepted_pending and status == "APPROVED" and w_payer is not None
          and w_payer["balance"] == 900 and w_payer["reservedBalance"] == 0 and w_payee["balance"] == 100)
    print(f"\n[{'PASS' if ok else 'FAIL'}] wallet outage recovery")
    print(f"    accepted_as_PENDING_while_wallet_down: {accepted_pending}")
    print(f"    final_status (expect APPROVED): {status}")
    print(f"    seconds_from_create_to_final (includes wallet restart): {elapsed:.0f}")
    if w_payer:
        print(f"    payer_balance (expect 900): {w_payer['balance']}")
        print(f"    payer_reserved (expect 0): {w_payer['reservedBalance']}")
        print(f"    payee_balance (expect 100): {w_payee['balance']}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
