# Load and correctness tests

Scripts that exercise a running PayStream stack through the API gateway. Results and hardware are in
[`docs/VERIFICATION.md`](../docs/VERIFICATION.md).

## Prerequisites

- The stack running: from the repo root `./gradlew bootJar`, then `cd infra/docker && docker compose up -d --build`
  (after code changes add `--force-recreate`; the images package the pre-built jars, so rebuild the jars first).
- Python 3.10+ with `requests` (`python -m venv .venv`, then `pip install requests`).
- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) for the load test.

## Gateway rate limit

The gateway limits each user (token bucket in Redis; defaults 50 req/s, burst 100). The stress tests send 100-200
requests from one user, so run them with the limit raised, and the rate-limit test with a small bucket.
Set the variables in the shell, then recreate the gateway:

```powershell
cd infra\docker
$env:RATE_LIMIT_REPLENISH="100000"; $env:RATE_LIMIT_BURST="100000"     # stress / load tests
docker compose up -d --force-recreate api-gateway

$env:RATE_LIMIT_REPLENISH="1"; $env:RATE_LIMIT_BURST="20"              # rate-limit test
docker compose up -d --force-recreate api-gateway

Remove-Item Env:RATE_LIMIT_REPLENISH, Env:RATE_LIMIT_BURST             # back to defaults
docker compose up -d --force-recreate api-gateway
```

## Correctness tests

```powershell
python correctness_test.py --skip-rate-limit                                     # needs the raised limit
python correctness_test.py --only rate_limit --rate-burst 20 --rate-replenish 1  # needs the small bucket
```

Checks: idempotency under concurrent duplicates, key reuse with a different payload (409), no overspend when 200
payments hit a wallet funded for 100, fraud rejection releases reserved funds, and rate limiting (429 plus
`X-RateLimit-*` headers). Each test creates fresh users and wallets, so it can be re-run freely. Results are also
written to `results.json`.

## Wallet-outage recovery

```powershell
python recovery_demo.py --compose-dir ..\infra\docker
```

Stops wallet-service, creates a payment (expects 202 `PENDING`), restarts the wallet, and waits for the recovery
job to complete the payment; then checks balances and that nothing is left reserved.

## Load test (k6)

```powershell
k6 run k6\load_test.js                                          # throughput, then saga timing (about 4 min)
k6 run -e ONLY=saga -e PAIRS=20 -e SAGA_RATE=20 k6\load_test.js   # saga timing only at 20 payments/s
```

Options (`-e NAME=value`): `ONLY` (`throughput` | `saga` | `both`), `PAIRS` (virtual users / payer wallets, default 50),
`SAGA_RATE` (payments/s, default 10), `POLL_MS` (status polling interval, default 250), `THROUGHPUT_DURATION`.
k6 prints the request rate averaged over the whole run; for `create_throughput` the real rate is `count / 60`.

Kafka consumer threads are set with `APP_KAFKA_CONCURRENCY` (default 6) on payment-service and fraud-service.

## Reconcile after a run

Wait about two minutes for Kafka to drain, then:

```powershell
docker compose exec -T postgres psql -U finflow -d finflow -c "SELECT status, count(*) FROM payment.payments GROUP BY status;"
docker compose exec -T postgres psql -U finflow -d finflow -c "SELECT count(*) AS unpublished_outbox FROM payment.outbox_events WHERE published = false;"
docker compose exec -T postgres psql -U finflow -d finflow -c "SELECT coalesce(sum(reserved_balance),0) AS total_reserved FROM wallet.wallets;"
docker compose exec -T postgres psql -U finflow -d finflow -c "SELECT count(*) AS double_settled FROM (SELECT payment_id, type FROM wallet.wallet_entries GROUP BY payment_id, type HAVING count(*) > 1) x;"
```

Expected: only `APPROVED`/`REJECTED` rows, then 0, 0, 0. Also check the `*.DLQ` topics are empty in Kafka UI
(http://localhost:8090).

## Coverage

After `./gradlew test` (Docker running, for the Testcontainers tests), from the repo root:
`python loadtest/coverage_summary.py` prints JaCoCo line coverage per module.
