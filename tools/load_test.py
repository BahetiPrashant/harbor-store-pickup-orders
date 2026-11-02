#!/usr/bin/env python3
"""Small, dependency-free load check for Harbor's authenticated order API."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


def request(base: str, path: str, *, method: str = "GET", body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    request_headers = {"Accept": "application/json"}
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)
    req = urllib.request.Request(base + path, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        payload = error.read().decode(errors="replace")
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {payload[:300]}") from error


def percentile(values, percent):
    values = sorted(values)
    if not values:
        return 0.0
    index = min(len(values) - 1, max(0, int((percent / 100) * len(values) + 0.999999) - 1))
    return values[index]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--users", type=int, default=8, help="concurrent read clients")
    parser.add_argument("--duration", type=int, default=30, help="load duration in seconds")
    parser.add_argument("--output", type=Path, help="optional JSON report path")
    args = parser.parse_args()
    if args.users < 1 or args.duration < 1:
        parser.error("--users and --duration must be positive")
    base = args.base_url.rstrip("/")

    status, login = request(base, "/api/auth/login", method="POST", body={
        "email": "ada@harbor.example", "password": "local-dev-password"
    })
    if status != 200:
        raise RuntimeError(f"Login failed with HTTP {status}")
    token = login["accessToken"]
    auth = {"Authorization": f"Bearer {token}"}
    _, catalog = request(base, "/api/catalog", headers=auth)
    if not catalog:
        raise RuntimeError("Catalog is empty")
    sku = catalog[0]["sku"]
    _, order = request(
        base,
        "/api/orders",
        method="POST",
        body={"items": [{"sku": sku, "quantity": 1}]},
        headers={**auth, "Idempotency-Key": f"load-check-{uuid.uuid4()}"},
    )
    order_path = f"/api/orders/{order['orderId']}"
    actions = [
        ("GET /api/catalog", "/api/catalog"),
        ("GET /api/orders?limit=20", "/api/orders?limit=20"),
        ("GET /api/orders/{id}", order_path),
    ]

    samples = defaultdict(list)
    failures = []
    lock = threading.Lock()
    stop_at = time.monotonic() + args.duration
    barrier = threading.Barrier(args.users)

    def run_client(client_id):
        local_count = 0
        barrier.wait()
        while time.monotonic() < stop_at:
            name, path = actions[(local_count + client_id) % len(actions)]
            start = time.perf_counter()
            try:
                code, _ = request(base, path, headers=auth)
                elapsed_ms = (time.perf_counter() - start) * 1000
                with lock:
                    samples[name].append((elapsed_ms, code))
            except Exception as error:
                with lock:
                    failures.append({"request": name, "error": str(error)[:300]})
            local_count += 1

    began = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as pool:
        futures = [pool.submit(run_client, n) for n in range(args.users)]
        for future in futures:
            future.result()
    elapsed = time.monotonic() - began
    rows = []
    successes = 0
    total = 0
    for name, measurements in sorted(samples.items()):
        latencies = [item[0] for item in measurements]
        status_counts = defaultdict(int)
        for _, code in measurements:
            status_counts[str(code)] += 1
            successes += int(200 <= code < 300)
            total += 1
        rows.append({
            "request": name,
            "count": len(latencies),
            "p50_ms": round(percentile(latencies, 50), 2),
            "p95_ms": round(percentile(latencies, 95), 2),
            "p99_ms": round(percentile(latencies, 99), 2),
            "max_ms": round(max(latencies, default=0), 2),
            "statuses": dict(status_counts),
        })
    report = {
        "tested_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base_url": base,
        "users": args.users,
        "duration_seconds": round(elapsed, 2),
        "requests": total,
        "requests_per_second": round(total / elapsed, 2) if elapsed else 0,
        "successful_requests": successes,
        "failed_requests": len(failures),
        "operations": rows,
        "errors": failures[:20],
        "scope": "Authenticated reads: catalog, paged order list, and one order lookup. One order is created as setup.",
    }
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    if failures or successes != total:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
