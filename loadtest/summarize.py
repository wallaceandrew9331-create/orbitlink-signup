"""Print one markdown table row per load-test run from the files run.sh writes.

    python loadtest/summarize.py baseline stress pool40 ...
"""
import json
import re
import statistics
import sys
from pathlib import Path

RESULTS = Path(__file__).parent / "results"


def trend(metrics, key):
    v = metrics[key]["values"]
    return v["p(50)"], v["p(95)"], v["p(99)"]


def peak_cpu(name, container):
    path = RESULTS / f"{name}.stats.txt"
    samples = [float(line.split()[1].rstrip("%")) for line in path.read_text().splitlines()
               if line.startswith(f"orbitlink-{container}-1")]
    busy = sorted(samples)[-6:]  # the burst covers ~6 of the 5-second samples
    return statistics.median(busy) if busy else 0.0


def gauge(name, metric):
    text = (RESULTS / f"{name}.metrics.txt").read_text()
    m = re.search(rf"{re.escape(metric)} ([0-9.E]+)", text)
    return float(m.group(1)) if m else float("nan")


def waits(name):
    path = RESULTS / f"{name}.pgwait.txt"
    if not path.exists():
        return {}
    totals, samples = {}, 0
    for line in path.read_text().splitlines():
        if line == "--":
            samples += 1
        elif "|" in line:
            what, n = line.rsplit("|", 1)
            totals[what] = totals.get(what, 0) + int(n)
    return {k: v / max(samples, 1) for k, v in sorted(totals.items(), key=lambda kv: -kv[1])}


def verify_ok(name):
    text = (RESULTS / f"{name}.verify.txt").read_text()
    return text.count("(0 rows)") == 4


def row(name):
    m = json.loads((RESULTS / f"{name}.json").read_text())["metrics"]
    a50, a95, a99 = trend(m, "http_req_duration{scenario:availability}")
    o50, o95, o99 = trend(m, "http_req_duration{scenario:orders}")
    failed = m["http_req_failed{scenario:availability}"]["values"]["rate"] * 100
    orders_ok = m["checks{scenario:orders}"]["values"]["rate"] * 100
    reqs = m["http_reqs"]["values"]["rate"]
    dropped = m.get("dropped_iterations", {}).get("values", {}).get("count", 0)
    return (f"| {name} | {reqs:,.0f} | {dropped:,} | {a50:.1f} / {a95:.1f} / {a99:.1f} | {failed:.2f}% "
            f"| {o50:.1f} / {o95:.1f} / {o99:.1f} | {100 - orders_ok:.2f}% "
            f"| {gauge(name, 'hikaricp.connections.pending'):.0f} "
            f"| {peak_cpu(name, 'api'):.0f}% | {peak_cpu(name, 'postgres'):.0f}% "
            f"| {'pass' if verify_ok(name) else 'FAIL'} |")


if __name__ == "__main__":
    print("| Run | Req/s (mean) | Dropped iterations | Lookup p50 / p95 / p99 (ms) | Lookup errors "
          "| Order p50 / p95 / p99 (ms) | Order errors | Pool waiters at peak | API CPU | Postgres CPU | Invariants |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name in sys.argv[1:]:
        print(row(name))
    for name in sys.argv[1:]:
        w = waits(name)
        if w:
            print(f"\n{name} Postgres backends by wait (mean of samples): "
                  + ", ".join(f"{k} {v:.1f}" for k, v in list(w.items())[:6]))
