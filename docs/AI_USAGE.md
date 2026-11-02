# AI usage notes

I used ChatGPT and Cursor substantially during this assignment for requirement breakdown, system design, implementation, tests, documentation, and review. I reviewed and adapted generated suggestions against the requirements and the running code instead of treating them as authoritative. During the final review, I compared the take-home requirements with the repository and checked the production configuration, order-list behavior, and worker lifecycle.

## Issues found and corrections

- The existing API tests did not explicitly verify an order containing multiple distinct items or the status filter on the order list. I added those cases to `OrderApiTest`.
- The tests did not exercise the full operator status path or verify that cancellation is rejected after an order leaves `PENDING`. I added a test covering `PROCESSING → SHIPPED → DELIVERED` and the pending-only cancellation rule.
- The test suite did not verify that the enabled background scheduler actually promotes a due order. I added a scheduler integration test with a short test-only polling interval and checked that the history records the actor as `WORKER`.
- The first production timing combination used a 5-minute pending delay and a 5-minute polling interval, which could leave an order pending for nearly 10 minutes. I removed the compounded delay: production orders become eligible at creation and the worker promotes them on its next 5-minute poll.
- Compose originally contained long but public placeholder secrets, and the production profile had no user provisioning path. I changed Compose to require private JWT, worker, and database secrets plus a read-only Argon2id user file; production now refuses startup if those settings are missing or unsafe.
- A secure Compose configuration was cumbersome for a recruiter to start as a local demo. I separated the localhost-only seeded demo Compose file from the secure profile and documented both run paths.
- Login attempts were limited only by the Java connection address, which is shared by users behind the Next.js proxy. I scoped the limit to a hash of the normalized account plus the connection address, and changed cleanup so every login does not scan every rate-limit bucket.
- Order listing used the global order set even though customer and status indexes were maintained. I changed repository paging to select the matching index and seek from a validated keyset cursor.
- The first implementation kept orders only in process memory, so a restart erased them. I added a production-profile JDBC/H2 adapter on a named Compose volume, retained the in-memory adapter for local development, and added a persistence/CAS test across repository recreation. The documented limit is single-node use.
- Timestamp pagination and the in-memory due queue relied on textual timestamps with variable precision. I added numeric epoch-millisecond ordering for both and tests for adjacent milliseconds and a whole-second/fractional-second due boundary.
- The Go worker previously ignored cancellation during a batch and could leave later due orders for another poll. It now uses the shutdown context, drains bounded batches, and retries a failed initial poll after a short delay.
- The repository abstraction was bypassed by direct construction in the API, and the in-memory adapter extended `ConcurrentHashMap` while separately maintaining indexes. I registered the adapter as a Spring repository, injected the interface, and made the adapter own its record map and indexes under a read/write lock.

I kept the final changes consistent with the existing API and test setup, then reran the Java, Go, Python, and web checks. AI output was useful for accelerating implementation and surfacing review cases; I verified the changes with code inspection and tests. ChatGPT and Cursor were both used substantially, as disclosed to the recruiter; I did not maintain a per-tool log of which individual lines or tasks each one contributed.
