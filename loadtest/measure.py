#!/usr/bin/env python3
"""
One measurement of the running backend, printed as a markdown table row (docs/scaling.md).
Python standard library only — nothing to install.

  python3 loadtest/measure.py --pid <backend pid> [--base http://localhost:8080] [--commands 20]

Measures:
  - connected devices and messages/s        (GET /api/stats)
  - backend CPU % and RSS                   (ps; 100 % = one core)
  - JVM heap used and live threads          (GET /q/metrics)
  - REST latency of one device-list page    (GET /api/devices?size=50)
  - command round trip, server-side         (POST setInterval to N online devices, poll until
                                             ACKED; completedAt - createdAt from the command log)
  - SSE batch size                           (read /api/stream for ~5 s)
"""
import argparse
import json
import statistics
import subprocess
import time
import urllib.request
from datetime import datetime


def get_json(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)


def post_json(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def metric(text, name, label_filter=""):
    """Sum of all samples of a Prometheus metric whose line contains label_filter."""
    total = 0.0
    for line in text.splitlines():
        if line.startswith(name) and (line[len(name)] in "{ ") and label_filter in line:
            total += float(line.rsplit(" ", 1)[1])
    return total


def instant(iso):
    # Python < 3.11 can't parse 'Z' and >6 fraction digits; normalise.
    iso = iso.replace("Z", "+00:00")
    main, _, rest = iso.partition(".")
    if rest:
        frac, _, tz = rest.partition("+")
        iso = f"{main}.{frac[:6].ljust(6, '0')}+{tz}"
    return datetime.fromisoformat(iso).timestamp()


def cpu_rss(pid):
    out = subprocess.run(["ps", "-o", "%cpu=,rss=", "-p", str(pid)], capture_output=True, text=True).stdout.split()
    return float(out[0]), int(out[1]) / 1024  # %, MB


def command_round_trips(base, n, interval):
    devices = get_json(f"{base}/api/devices?online=true&size={n}")["items"]
    sent = []
    for d in devices:
        # Same interval the simulator already uses → measuring doesn't change the load.
        c = post_json(f"{base}/api/devices/{d['id']}/commands", {"name": "setInterval", "params": {"seconds": interval}})
        sent.append((d["id"], c["id"]))
    deadline = time.time() + 15
    results = {}
    while len(results) < len(sent) and time.time() < deadline:
        time.sleep(0.5)
        for device_id, command_id in sent:
            if command_id in results:
                continue
            for c in get_json(f"{base}/api/devices/{device_id}/commands?limit=5"):
                if c["id"] == command_id and c["status"] != "SENT":
                    results[command_id] = c
    acked = [c for c in results.values() if c["status"] == "ACKED"]
    ms = sorted((instant(c["completedAt"]) - instant(c["createdAt"])) * 1000 for c in acked)
    return len(sent), len(acked), ms


def sse_batches(base, seconds=5):
    sizes, device_counts = [], []
    end = time.time() + seconds
    with urllib.request.urlopen(f"{base}/api/stream", timeout=30) as r:
        while time.time() < end:
            line = r.readline()
            if line.startswith(b"data:"):
                sizes.append(len(line))
                device_counts.append(len(json.loads(line[5:])["devices"]))
    return sizes, device_counts


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--pid", type=int, required=True, help="backend JVM pid (for CPU/RSS)")
    p.add_argument("--commands", type=int, default=20)
    p.add_argument("--interval", type=int, default=5, help="telemetry interval the simulator runs with")
    a = p.parse_args()

    stats = get_json(f"{a.base}/api/stats")
    cpu_samples = []
    for _ in range(5):
        cpu_samples.append(cpu_rss(a.pid)[0])
        time.sleep(1)
    cpu, rss = statistics.mean(cpu_samples), cpu_rss(a.pid)[1]
    with urllib.request.urlopen(f"{a.base}/q/metrics", timeout=30) as r:
        prom = r.read().decode()
    heap = metric(prom, "jvm_memory_used_bytes", 'area="heap"') / 1024 / 1024
    threads = metric(prom, "jvm_threads_live_threads")

    t = time.time()
    get_json(f"{a.base}/api/devices?size=50")
    list_ms = (time.time() - t) * 1000

    n, acked, rtt = command_round_trips(a.base, a.commands, a.interval)
    sizes, counts = sse_batches(a.base)

    p50 = statistics.median(rtt) if rtt else float("nan")
    p95 = rtt[int(len(rtt) * 0.95) - 1] if rtt else float("nan")
    print("| connected | msgs/s | CPU % | RSS MB | heap MB | threads | list ms | cmd acked | cmd p50 ms | cmd p95 ms | SSE KB/batch | devices/batch |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    print(f"| {stats['connected']} | {stats['messagesPerSecond']} | {cpu:.0f} | {rss:.0f} | {heap:.0f} | {threads:.0f} "
          f"| {list_ms:.0f} | {acked}/{n} | {p50:.0f} | {p95:.0f} "
          f"| {statistics.mean(sizes) / 1024:.0f} | {statistics.mean(counts):.0f} |")


if __name__ == "__main__":
    main()
