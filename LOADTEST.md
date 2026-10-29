# Launch-day load test

What happens when a launch announcement sends a burst of people to the availability and order endpoints at once: where the service broke, what fixed it, and what is still unresolved. Every number below comes from a run recorded in [`loadtest/results/`](loadtest/results/), summarised with [`loadtest/summarize.py`](loadtest/summarize.py). The most important result in this document is that **single runs near saturation are not trustworthy**: the same configuration gave a lookup p99 of 19 ms in one run and 1.2 s in the next. So the comparisons below use several runs and report the spread (see [Caveats](#caveats)).

## Summary

| | Before (10 connections) | After (40 connections, fewer round trips) |
|---|---|---|
| Spike to 6,000 lookups/s + 1,500 orders/s | **collapses**: lookup p50 ~1 s, p99 ~2.9 s; ~3,900 requests queued for a DB connection; k6 had to drop ~300k of the iterations it was asked to start | **mostly keeps up**: lookup p50 16–26 ms, p99 0.4–1.4 s over 3 runs; 12k–68k iterations dropped |
| Throughput at that spike | ~2,350 req/s | ~4,700–5,250 req/s |
| Spike to 4,000 lookups/s + 800 orders/s | lookup p50 233 ms, p99 1.4 s; 2,515 queued for a connection | 2 of 3 runs: lookup p50 ~2 ms, **p99 13–19 ms**, nothing queued. 1 of 3 runs: p99 1.2 s (Finding 3) |
| Correctness under load (oversell, waitlist gaps, queue jumping, counter drift) | **0 violations** | **0 violations** |
| Duplicate submissions (5% of orders sent twice with the same key) | 0 mismatched replays | 0 mismatched replays |
| Abusive client (one IP, 50 req/s) | ~79% rejected with 429 | ~79% rejected with 429 |

Two changes, each made after measuring:

1. **Connection pool 10 → 40** ([`application.yml`](backend/src/main/resources/application.yml)). With virtual threads every request gets its own thread, so nothing limits concurrency before the JDBC pool, and a 10-connection pool became a queue of thousands. This was the big, unambiguous win.
2. **Fewer round trips inside the order transaction** ([`OrderService.place`](backend/src/main/java/com/orbitlink/signup/orders/OrderService.java)): 8 → 5 statements on the order path and 10 → 7 on the waitlist path. Directionally better (median lookup p99 roughly halved, median pool queue ~6× shorter), but within this test's noise. Details in Finding 2.

One thing that **did not** turn out to matter: lock contention on hot cells, which I expected to be the problem. It was a small share of database time even with 70% of orders aimed at five cities.

## Setup

| | |
|---|---|
| Host | AMD Ryzen 9 5950X (16 cores / 32 threads), 128 GB RAM, Windows 11 Pro |
| Runtime | Docker Engine 29.8.2 on Docker Desktop (WSL2 VM: 32 vCPUs, 62.75 GiB) |
| API | One Spring Boot 4.1 container, Java 21, virtual threads, Tomcat defaults |
| Database | PostgreSQL 16.15, image defaults (`synchronous_commit = on`, `max_connections = 100`) |
| Redis | 7 (alpine), image defaults: rate-limit buckets and lookup counters |
| Load generator | k6 v2.3.0 in a container on the same Docker network, calling the API directly (not through nginx) |

Everything shares one machine, including k6, so the load generator competes with the system for CPU.

## Method

[`loadtest/run.sh`](loadtest/run.sh) runs one load level:

1. `docker compose down -v`, then `up --build` with no demo seed, so every run starts with empty tables.
2. Wait for `/actuator/health`, plus 5 s.
3. Run [`loadtest/spike.js`](loadtest/spike.js), 100 s in total:
   - **availability**: arrival rate ramps to PEAK/15 for 20 s (normal day), then to PEAK over 10 s (the announcement lands), holds 60 s, and ramps down over 10 s;
   - **orders**: the same shape with its own peak. Each order uses a fresh email and Idempotency-Key, and 5% are sent a second time with the same key and body; the replay must return the same order or waitlist entry;
   - **abuser**: one fixed IP at a constant 50 req/s against the availability endpoint.
4. While k6 runs: `docker stats` every 5 s (100% = one core), Hikari pool gauges at 60 s (mid-burst), and 10 samples of `pg_stat_activity` wait events, one per second, starting at 55 s.
5. Afterwards, [`loadtest/verify.sql`](loadtest/verify.sql) checks the invariants: no cell over capacity, each cell's `reserved` counter equals its live orders, waitlist positions exactly 1..n per cell, and no cell has both a free slot and people waiting.

Traffic is skewed on purpose: 70% of requests land within ±0.2° of five metro centres (Memphis, Austin, Denver, Seattle, Monterrey), which puts most orders into a few dozen H3 cells. That's the hard case for row locks. The other 30% are spread across the continental US. Cells start at the default 20 slots, so hot cells sell out within seconds and most orders become waitlist joins, which is also what a real launch looks like.

Each request carries a random `X-Forwarded-For` (the API trusts it in this setup), so the per-IP rate limiter sees thousands of distinct clients and only the abuser gets throttled.

"Dropped iterations" are requests k6 was scheduled to start but couldn't, because all 2,000 of its virtual users were blocked waiting for responses. They are the clearest sign that the system fell behind the offered load. Requests k6 did send almost never failed (0.00% errors in every run), because a request waiting in the pool queue eventually succeeds; the cost shows up as latency and dropped iterations, not errors.

The order p99 always includes the fake payment provider's deliberate timeouts: 10% of charges wait out the 1.5 s client timeout. So order p99 sits around 1.6 s even when everything else is idle. The order p50 and p95 are the meaningful numbers.

## Results

Every run, in the order they were made. "Old" means the order transaction from before Finding 2. Peaks: baseline 1,500 lookups/s + 300 orders/s; `*-4000` 4,000 + 800; everything else 6,000 + 1,500. Plus the abuser's 50/s throughout.

| Run | Code / pool | Req/s (mean) | Dropped iterations | Lookup p50 / p95 / p99 (ms) | Lookup errors | Order p50 / p95 / p99 (ms) | Order errors | Pool waiters at peak | API CPU | Postgres CPU | Invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline | old / 10 | 1,341 | 0 | 1.3 / 2.0 / 3.8 | 0.00% | 6.9 / 90.1 / 1571.8 | 0.00% | 0 | 274% | 129% | pass |
| stress | old / 10 | 2,358 | 303,856 | 977.0 / 2152.8 / 2888.8 | 0.00% | 1145.4 / 4830.5 / 6217.1 | 0.00% | 3949 | 554% | 419% | pass |
| before-4000 | old / 10 | 3,155 | 31,765 | 232.5 / 844.1 / 1426.7 | 0.00% | 439.4 / 2290.5 / 3486.9 | 0.00% | 2515 | 757% | 403% | pass |
| pool40 | old / 40 | 5,330 | 5,026 | 10.2 / 107.7 / 176.1 | 0.00% | 46.6 / 286.2 / 1613.9 | 0.00% | 373 | 1016% | 725% | pass |
| pool40-old-1 | old / 40 | 3,584 | 183,318 | 464.2 / 1320.0 / 1946.7 | 0.00% | 693.8 / 2663.0 / 3953.7 | 0.00% | 3766 | 1000% | 875% | pass |
| pool40-old-2 | old / 40 | 2,977 | 241,268 | 683.8 / 1639.3 / 2418.0 | 0.00% | 832.5 / 3202.2 / 4507.0 | 0.00% | 3870 | 939% | 904% | pass |
| pool40-old-3 | old / 40 | 4,997 | 42,880 | 158.0 / 683.4 / 1018.2 | 0.00% | 262.9 / 1395.4 / 2243.2 | 0.00% | 2742 | 981% | 856% | pass |
| pool40-fewer-roundtrips | new / 40 | 4,712 | 67,931 | 25.7 / 733.5 / 1362.0 | 0.00% | 101.3 / 1596.6 / 2786.7 | 0.00% | 559 | 967% | 822% | pass |
| pool40-fewer-roundtrips-b | new / 40 | 5,171 | 21,828 | 16.3 / 351.4 / 705.2 | 0.00% | 61.2 / 910.2 / 1715.6 | 0.00% | 118 | 982% | 791% | pass |
| pool40-fewer-roundtrips-c | new / 40 | 5,246 | 11,432 | 20.4 / 214.6 / 368.1 | 0.00% | 72.8 / 502.9 / 1668.2 | 0.00% | 1528 | 987% | 823% | pass |
| final-4000 | new / 40 | 3,166 | 27,722 | 68.0 / 758.2 / 1216.9 | 0.00% | 172.2 / 1584.4 / 2449.2 | 0.00% | 441 | 920% | 702% | pass |
| final-4000-b | new / 40 | 3,481 | 565 | 1.8 / 7.6 / 19.1 | 0.00% | 9.3 / 97.8 / 1575.6 | 0.00% | 0 | 714% | 464% | pass |
| final-4000-c | new / 40 | 3,457 | 5 | 1.6 / 5.0 / 12.9 | 0.00% | 8.5 / 93.5 / 1572.9 | 0.00% | 0 | 660% | 410% | pass |

How to read this table:

- **The baseline is easy.** At 1,500 lookups/s nothing queues: lookups take 1–4 ms, and the order p99 is just the injected payment timeouts.
- **"Req/s" is what k6 actually sent**, averaged over the whole 100 s, including ramps. When the system falls behind, k6 runs out of virtual users and sends less, so a lower req/s at the same target load is a symptom, not a lighter test.
- **"Lookup errors" and "Order errors" are always zero.** The service never failed requests under overload; it made them wait. That's Finding 3.
- **Order p99 never drops below ~1.57 s** because 10% of payments wait out the fake provider's 1.5 s timeout.

## Finding 1: the connection pool was the queue

The first spike run at 6,000 lookups/s and 1,500 orders/s fell over: lookup p50 977 ms, p99 2.9 s, and k6 dropped 304k iterations. At mid-burst the Hikari gauges read **10 active, 3,949 pending**.

Virtual threads explain the shape. Tomcat runs each request on its own virtual thread, so there's no thread-pool limit in front of the database: thousands of requests reach `getConnection()` and queue there. Lookups are a single indexed `SELECT` and should take about 1 ms, but they wait in the same queue as order transactions.

Was Postgres itself the limit? `pg_stat_activity` at peak (mean backends per state across 10 samples):

| 10 connections, 6k/1.5k spike | backends |
|---|---|
| running on CPU | 2.6 |
| waiting on WAL write lock (`LWLock:WALWrite`) | 2.1 |
| idle in transaction, waiting for the client's next statement | 1.1 |
| waiting on WAL fsync (`IO:WALSync`) | 1.0 |
| waiting on row locks (`Lock:transactionid`) | ~0 |

Postgres used about 4 of 32 cores. Its backends were mostly waiting on commit flushes and on the application. Only 10 transactions could be in flight at once, so only 10 commits could share a WAL flush. The database had headroom; the pool didn't let us use it.

**Fix: 40 connections.** That's still well under `max_connections = 100`, and leaves room for a second replica plus admin sessions. In the first run at the same load, lookup p50 dropped from 977 ms to 10 ms and throughput went from ~2,350 to ~5,300 req/s. Repeats showed that first run was a lucky one (Finding 2), but at both load levels the 40-connection runs beat the 10-connection runs at the same load.

## Finding 2: round trips inside the transaction

With 40 connections the pool was still saturated at peak, and the biggest wait state changed:

| 40 connections, 6k/1.5k spike, before round-trip change | backends |
|---|---|
| idle in transaction, waiting for the client's next statement | 10.7 |
| waiting on WAL write lock | 9.1 |
| idle (between transactions, connection held by the pool) | 6.5 |
| running on CPU | 3.3 + 2.6 |
| waiting on row locks | 2.5 |

"Idle in transaction" means Postgres had finished a statement and was waiting for the API to send the next one. It's time a pooled connection is held but not used. The order transaction sent 8 statements one after another, and the waitlist path sent 10:

| Statement | Before | After |
|---|---|---|
| claim idempotency key | ✓ | ✓ |
| create cell if missing (`INSERT … ON CONFLICT DO NOTHING`) | every order | only when no cell row matched |
| duplicate checks (live order? already waiting?) | 2 queries | 1 `UNION ALL` query |
| reserve slot / take waitlist position | ✓ | ✓ (position query also returns the promoted count) |
| insert order or entry | ✓ | ✓ with `RETURNING` |
| re-select the order / the cell for "people ahead" | 1 query | removed |
| attach key, outbox | ✓ | ✓ |

Same semantics, and the full test suite including the oversell test passed unchanged. Because single runs were so noisy, I compared several runs of each version at the same load:

| 6k/1.5k spike, 40 connections | Old code (4 runs) | New code (3 runs) |
|---|---|---|
| Lookup p99, ms | 176, 1,018, 1,947, 2,418 (median ~1,480) | 368, 705, 1,362 (median 705) |
| Pool waiters at mid-burst | 373, 2,742, 3,766, 3,870 (median ~3,250) | 118, 559, 1,528 (median 559) |
| Dropped iterations | 5k, 43k, 183k, 241k (median ~113k) | 11k, 22k, 68k (median 22k) |

The medians all move the right way, by 2–6×, but the ranges overlap, and the best single run in the whole set was old code. My conclusion is that it's a real improvement, but I can't put a number on it at this load with this harness. I kept it because it's strictly less work per order and doesn't make the code harder to read.

## Finding 3: near capacity the system is bistable

Three runs of the final code at a lower load (4,000 lookups/s + 800 orders/s) should have been the clean "after" numbers. They weren't consistent:

| 4,000 lookups/s + 800 orders/s | Code / pool | Lookup p50 / p99 (ms) | Order p50 / p95 (ms) | Pool waiters at peak | Dropped iterations |
|---|---|---|---|---|---|
| before-4000 | old / 10 | 232.5 / 1,426.7 | 439.4 / 2,290.5 | 2,515 | 31,765 |
| final-4000 | new / 40 | 68.0 / 1,216.9 | 172.2 / 1,584.4 | 441 | 27,722 |
| final-4000-b | new / 40 | 1.8 / 19.1 | 9.3 / 97.8 | 0 | 565 |
| final-4000-c | new / 40 | 1.6 / 12.9 | 8.5 / 93.5 | 0 | 5 |

In two of three runs the final code handles this spike with lookups at 2 ms p50 and 13–19 ms p99, and Postgres backends mostly sit idle (~30 of 40 idle in `pg_stat_activity`). In the third, it looks almost like the old 10-connection code.

The runs are either fine or not: either the pool never queues, or a queue forms early in the spike and doesn't drain until the spike ends. When it queues, every request waits longer, holds its connection longer and finishes later, so the queue feeds itself. Nothing in the service sheds load: a request will wait up to Hikari's default `connectionTimeout` of 30 s for a connection, and Tomcat keeps accepting new requests the whole time. Which side of the tipping point a run lands on depends on small things: JIT warm-up during the first seconds of the ramp, and what k6 and Docker are doing on the same 32 threads.

I haven't fixed this; it's the top next step. The fix is **admission control**, not more tuning:

- a short `connectionTimeout` (e.g. 250 ms) that turns pool exhaustion into a fast `503` with `Retry-After`. The SPA already retries 5xx with the same Idempotency-Key, so a shed order retries safely;
- a separate, small pool or semaphore for lookups, so a burst of orders can't starve the read path (or the reverse);
- a concurrency limit in front of `/api/orders`, sized to the pool, so excess load waits in a bounded queue outside the database layer.

## Correctness under load

In every run, at every load level, including the ones that collapsed:

- no cell had `reserved > capacity`, and every cell's counter matched its live orders exactly;
- every cell's waitlist positions were exactly 1..n, with no gaps or duplicates;
- no cell had a free slot while people were waiting;
- 0 of the ~5% replayed submissions got a different order or waitlist entry back;
- the abuser was rejected ~79% of the time (about 3,960 of 5,000 requests, close to the 10 req/s refill against 50 req/s offered), while every other request passed the limiter.

Overload made the service slow, but it never made it wrong. That's the property the locking design is meant to guarantee, and it held at the worst point of the test.

## Remaining limits and next steps

In rough order of payoff:

1. **Admission control** (Finding 3). Bounded queues and fast 503s, so overload costs a retry instead of a 2-second wait for everyone.
2. **Commit cost.** `LWLock:WALWrite` plus `IO:WALSync` were 5–10 of the busy backends at peak. Every order commits once. Options: `synchronous_commit = off` for waitlist joins only (a crash could lose the last few hundred ms of joins, and the customer would see an error on retry, not a wrong position), or batching outbox inserts. Neither is free; I'd measure before choosing.
3. **The read path doesn't need Postgres for most cells.** Untouched cells are virtual, but lookups still query Postgres to find that out. A short-TTL cache of cell rows (or Redis as the read model, updated after commit) would take lookups off the pool entirely. The tradeoff is that slot counts on the map could be a second or two stale, which is fine for a map as long as the order path stays transactional.
4. **One API replica, one machine.** Everything here is single-instance, and the load generator shares the box. The numbers are useful for before/after on the same machine, not as capacity planning.

## Caveats

- **Variance near saturation is huge.** Identical configurations gave lookup p99 between 19 ms and 1.2 s (Finding 3). Below saturation (the 1,500/300 baseline) runs are tight: p99 under 4 ms.
- **One machine.** k6, API, Postgres and Redis share 32 hardware threads, and at peak the API (~10 cores), Postgres (~8) and k6 together use most of them. Absolute numbers would differ on separate hosts.
- **Point samples.** CPU is the median of the busiest six 5-second `docker stats` samples. Pool gauges are a single read at 60 s. Wait events are 10 samples over 10 s.
- **k6 percentiles cover the whole run**, including ramp-up and ramp-down, not just the peak window. That understates peak latency somewhat.
- **nginx isn't in the path.** k6 calls the API directly, so it can set `X-Forwarded-For` per request and simulate many clients. nginx would add a little latency and is untested here.

## Reproducing

```bash
./loadtest/run.sh baseline                                          # 1,500 lookups/s + 300 orders/s
DB_POOL_SIZE=10 PEAK_LOOKUPS=6000 PEAK_ORDERS=1500 ./loadtest/run.sh stress
PEAK_LOOKUPS=6000 PEAK_ORDERS=1500 ./loadtest/run.sh pool40          # default pool is now 40
python loadtest/summarize.py baseline stress pool40                  # markdown table + wait states
```
