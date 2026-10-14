# Verification results

Everything below was measured on a single development laptop with the **entire stack running in Docker on the same
machine** (all services, Kafka, ZooKeeper, PostgreSQL, Redis) together with the load generator. Treat the absolute
numbers as "laptop numbers"; the interesting part is the behaviour and the before/after comparison.

| | |
|---|---|
| CPU | Intel Core i5-1155G7, 4 cores / 8 threads |
| RAM | 15.7 GB (Docker Desktop: 8 CPUs, ~7.4 GB) |
| Load tools | [k6](https://k6.io) 2.2.0, Python 3 + `requests` |

Test code lives in [`loadtest/`](../loadtest) (`correctness_test.py`, `recovery_demo.py`, `k6/load_test.js`).

## 1. Correctness under concurrency

All tests create fresh users and wallets and run against the full stack through the API gateway.

| Test | Result |
|---|---|
| 100 simultaneous requests, **same idempotency key** | 100 x HTTP 202, **1** distinct payment, payer debited once |
| Same key, **different amount** | HTTP 409 (payer balance unchanged) |
| 200 concurrent payments from one wallet funded for exactly 100 | **100 approved, 100 rejected**, 0 server errors, payer balance 0, nothing reserved, money conserved |
| Payment above the fraud threshold | Rejected, reserved funds released |
| Gateway rate limit (bucket of 20, refill 1/s), 300 simultaneous requests | 22 allowed, **279 rejected with 429**; `X-RateLimit-*` headers present |
| **Wallet outage**: stop wallet-service, create a payment, restart | Accepted as `PENDING` (202), later `APPROVED` by the recovery job; payer balance correct, **0 reserved** |

## 2. Load test

### Payment creation (`POST /api/payments`)

50 concurrent users for 60 s, each with its own payer wallet, through the gateway:
2,605 requests (about 43 req/s), **0 failures**, p95 1.96 s, p99 2.5 s.

### End-to-end saga completion (HTTP -> outbox -> Kafka -> fraud -> Kafka -> wallet settle -> approved)

Time from `POST` until the payment reaches `APPROVED`/`REJECTED`, measured by polling.
Warm-up run discarded; identical conditions for both configurations.

| Arrival rate | 1 consumer thread (median / p95) | 6 consumer threads (median / p95) |
|---|---|---|
| 5 payments/s | 536 ms / 563 ms | 539 ms / 591 ms |
| 10 payments/s | 538 ms / 801 ms | 540 ms / 825 ms |
| **20 payments/s** | **13.8 s / 29.2 s** (307 payments unfinished after 30 s) | **748 ms / 1.74 s** |

**Finding.** The Kafka topics have 6 partitions, but the listener containers ran with the default concurrency of 1, so
all partitions were processed sequentially by one thread (each fraud result triggers a synchronous wallet settle call).
Below about 10 payments/s the pipeline is bounded by the 500 ms outbox polling interval; above that, the single
consumer thread became the bottleneck and latency collapsed. Concurrency is now configurable
(`APP_KAFKA_CONCURRENCY`, default 6). Two earlier single-thread runs at 20/s gave p95 of 14.9 s and 29.2 s, so the
size of the collapse varies between runs; the difference to 6 threads does not.

## 3. Reconciliation after the load tests

After about 10,000 payments across all runs (9,662 approved, 504 rejected at the time of the check):

| Check | Result |
|---|---|
| Payments in a non-final state | 0 |
| Unpublished outbox events | 0 |
| Funds left reserved | 0 |
| Payments settled more than once | 0 |
| Messages in the dead-letter topics | 0 |

## 4. Reproduce

```powershell
./gradlew bootJar                      # the Dockerfiles package the pre-built jars
cd infra\docker
docker compose up -d --build
cd ..\..\loadtest
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install requests
```

Then follow [`loadtest/README.md`](../loadtest/README.md): each test needs specific gateway rate-limit settings.

## 5. Known limitations

- A payment whose wallet never recovers stays `PENDING` indefinitely (a production version would add a maximum age
  and alerting). The recovery job re-drives payments older than 30 s every 15 s.
- Wallet `settle`/`release` are idempotent per payment but do not verify that a matching reservation exists.
- Dead-letter routing is covered for failures raised while processing a message; consumers use `JsonDeserializer`
  directly, so wrapping it in `ErrorHandlingDeserializer` would be the next hardening step for malformed payloads.
- Laptop measurements with the load generator on the same machine; not a capacity claim for production hardware.
