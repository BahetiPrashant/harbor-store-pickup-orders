# Harbor high-level design

Harbor is a four-service order-processing application. Next.js serves the browser experience and proxies authenticated requests. Java owns order validation, pricing, authorization, status transitions, and persistence. Python creates catalog-grounded item suggestions but cannot create orders. A Go worker asks Java for due orders and requests promotion through a private internal API.

```text
Browser → Next.js route handlers → Java order service → H2 file (secure Compose)
                                            └────────→ indexed memory (local)
                              └────────────→ Python intake (catalog grounded)
Go status worker ─────────────────────────→ Java internal promotion endpoints
```

## Deployment modes

| Mode | Order storage | Promotion | Use |
|---|---|---|---|
| Local development | Indexed in-memory repository | Embedded Java scheduler by default; Go worker is an alternative | Fast iteration with seeded demo accounts |
| Demo Compose | Indexed in-memory repository | Go worker, every 15 seconds | Local evaluation with loopback-only exposed ports |
| Secure Compose | H2 file database on named volume | Go worker, every 5 minutes | Single-node production-shaped deployment with provisioned users and private secrets |

Local and demo data is lost when the Java process stops. Secure Compose data survives Java restarts as long as the named volume is retained. The H2 file is a single-node design; use a shared relational database before running multiple order-service replicas.

## Order behavior

The customer submits SKUs and quantities. Java looks up active catalog entries, computes totals in integer USD minor units, and stores an immutable price snapshot with the order. The transition path is `PENDING → PROCESSING → SHIPPED → DELIVERED`; a customer may cancel only in `PENDING`. Production makes orders eligible immediately and the worker polls every five minutes, so promotion occurs on the next poll, within roughly five minutes. Local settings use a 30-second eligibility delay and 15-second scheduler cadence.

Java is the only writer. Order placement uses a customer-scoped idempotency key and request hash. Status updates use a versioned compare-and-set, preventing stale writes. Customer and status list filters use indexed keyset pagination.

## Security boundaries

- Passwords are verified against Argon2id hashes. Production users are provisioned through a read-only JSON file; seeded demo accounts are disabled.
- Short-lived HS256 tokens authorize customer and operator APIs. The browser receives the token in an HTTP-only cookie and calls only Next.js routes.
- The worker API requires a separate internal token. Production validates the JWT secret, worker token, H2 database password, and user file at startup.
- Local/demo credentials and Compose secrets are not production credentials.

See [LLD](LLD.md) for storage and request details and the [developer guide](DEVELOPER_GUIDE.md) for exact startup commands and configuration.
