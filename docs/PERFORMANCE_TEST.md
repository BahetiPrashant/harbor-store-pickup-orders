# Local load check

Run date: 2 November 2026 (local Windows development environment)

## Result

The Java order service handled 8 concurrent clients for 20 seconds. The script made one setup order, then repeatedly exercised authenticated catalog reads, paged order lists, and lookup of that order.

| Request | Requests | p50 | p95 | p99 | HTTP errors |
|---|---:|---:|---:|---:|---:|
| `GET /api/catalog` | 9,250 | 4.44 ms | 9.26 ms | 18.99 ms | 0 |
| `GET /api/orders?limit=20` | 9,249 | 4.47 ms | 9.32 ms | 19.62 ms | 0 |
| `GET /api/orders/{id}` | 9,249 | 4.42 ms | 9.18 ms | 19.05 ms | 0 |

Total: 27,748 successful requests, 1,387.4 requests/second across the three read paths, and no client-side failures. Maximum observed latency ranged from 516 ms to 954 ms, so occasional long pauses occurred even though p99 stayed below 20 ms.

The one-time place-order setup request returned successfully, but order placement was not under load. Login, cancel, operator transitions, the Python draft service, the Go worker, and the Next.js frontend were not benchmarked. The run used one local Java process and its in-memory repository; it is a repeatable development baseline, not a multi-instance or production capacity result. No write-path or production-capacity claim is made from this run.

Raw measurements are in [load-test-results.json](load-test-results.json).

## Rerun

Start the local Java service using the steps in [README](../README.md), then from the project root run:

```powershell
py -3.12 tools/load_test.py --users 8 --duration 20 --output docs/load-test-results.json
```

The script uses the local demo customer and creates one uniquely keyed setup order. Use a fresh Java process for a clean in-memory store. Adjust `--users` and `--duration` to change concurrency and test length. It exits nonzero if a request fails.
