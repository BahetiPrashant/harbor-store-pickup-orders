# Reviewer brief

Harbor is a store-pickup desk with cash payment at collection. Review the customer and operator paths, integer catalog pricing, idempotent placement, pending-only cancellation, automatic pending-to-processing promotion, receipt printing, and grounded natural-language cart suggestions.

The browser talks to Next.js route handlers. Java owns order state. Python can suggest only active catalog rows. Go calls Java's internal promotion API. Local development uses memory; secure Compose uses a file-backed H2 database on a named volume. The H2 setup is single-node, not a multi-replica persistence design.

Local demo users are `ada@harbor.example` and `ops@harbor.example`, password `local-dev-password`. Production requires independent private JWT, worker, and H2 database secrets, disables seed users, and loads Argon2id password hashes from a read-only users file. Production orders become eligible immediately and the worker polls every five minutes, promoting each pending order on the next poll.

## Assignment requirement check

| Requirement | Implementation |
|---|---|
| Place an order with multiple items | `POST /api/orders`; line items and totals are validated and priced from the catalog |
| Retrieve by order ID | `GET /api/orders/{orderId}` with customer ownership checks and operator access |
| Statuses and progression | `PENDING → PROCESSING → SHIPPED → DELIVERED`; only one forward step is allowed |
| Automatic promotion | Go polls every five minutes in Compose; due-order selection and promotion share the Java state transition and compare-and-set guard |
| List all or by status | `GET /api/orders` supports status, customer, cursor, and bounded page size; customers are scoped to their own orders |
| Cancel only while pending | Customer cancellation returns conflict after the order leaves `PENDING` |
| Persistence and retry safety | Production Compose uses H2 on a named volume; customer-scoped idempotency prevents duplicate order creation |

The Java suite covers multi-item creation, status filters and pagination, transitions, cancellation restrictions, scheduled promotion, production startup validation, and JDBC persistence/CAS. Python tests cover draft grounding and validation; Go tests exercise due-order polling and promotion outcomes. The web production build is also checked. Run commands and the single-node H2 limitation are in the [developer guide](DEVELOPER_GUIDE.md).

A small local read-load check measured catalog, paged list, and order lookup with 8 concurrent clients. Results, scope, limitations, and rerun steps are in [performance test results](PERFORMANCE_TEST.md). These measurements use one local in-memory Java process and do not establish production capacity.
