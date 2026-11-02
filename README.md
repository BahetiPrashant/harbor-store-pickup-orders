# Harbor orders

Repository: [github.com/BahetiPrashant/harbor-store-pickup-orders](https://github.com/BahetiPrashant/harbor-store-pickup-orders)

Harbor is a store-pickup order desk. Customers choose catalog items, agree to pay cash at collection, and can cancel while an order is pending. The API state path is `PENDING → PROCESSING → SHIPPED → DELIVERED`; the interface presents the final three states as Preparing, Ready for pickup, and Collected. A plain-English suggestion can add catalog items to the cart; only **Place order** saves anything.

## Services

| Service | Stack | Port |
|---|---|---:|
| Order service | Java 21, Spring Boot 3.4.5 | 8080 |
| Intake service | Python 3.12, FastAPI | 8081 |
| Web | Next.js 15, React 19 | 3000 |
| Status worker | Go 1.22 | — |

Local development uses an indexed in-memory order repository. The secure Compose profile uses an H2 file database on a named Docker volume, so orders survive Java restarts. That profile is single-node; the catalog is loaded once at startup.

## Local run

Open three terminals from the project root:

```powershell
cd order-service
mvn -s maven-settings.xml spring-boot:run
```

```powershell
cd intake-service
py -3.12 -m pip install -r requirements.txt
py -3.12 -m uvicorn app.main:app --port 8081
```

```powershell
cd web
npm ci
npm run dev
```

Open [http://localhost:3000/login](http://localhost:3000/login). The local customer account is `ada@harbor.example`; the operator account is `ops@harbor.example`. Both use `local-dev-password`.

The local Java scheduler promotes due orders every 15 seconds after a 30-second pending window. In production, orders are eligible immediately and the Go worker checks every 5 minutes, so a pending order is picked up on the next scheduled poll (up to roughly 5 minutes later). The embedded Java scheduler is enabled by default locally. To use the Go worker instead, restart Java with `ORDERFLOW_EMBEDDED_PROMOTE=false`, then run the worker with `ORDER_API_URL=http://localhost:8080`, `INTERNAL_TOKEN=local-dev-internal-token-32bytes`, and `INTERVAL=15s`. See the [developer guide](docs/DEVELOPER_GUIDE.md) for exact PowerShell commands.

## Docker Compose

For a local review/demo using the seeded customer and operator accounts:

```powershell
docker compose -f deploy/compose/compose.demo.yaml up --build
```

The demo binds the Java API to localhost and uses local-only seeded credentials. For the secure production-shaped configuration, create a hashed users file and set private secrets as described in the [developer guide](docs/DEVELOPER_GUIDE.md#production-users-and-secrets), then run `docker compose -f deploy/compose/compose.yaml up --build`.

## Checks

Run `mvn -s maven-settings.xml test` in `order-service`, `py -3.12 -m pytest` in `intake-service`, `go test ./...` in `status-worker`, and `npm ci` followed by `npm run build` in `web`. GitHub Actions runs those checks on pushes and pull requests.

More detail: [HLD](docs/HLD.md), [LLD](docs/LLD.md), [developer guide](docs/DEVELOPER_GUIDE.md), and [reviewer brief](docs/REVIEWER_BRIEF.md).

AI assistance used for this assignment and the issues found during review are recorded in [AI usage notes](docs/AI_USAGE.md).

The local authenticated read-load check and rerun instructions are in [performance test results](docs/PERFORMANCE_TEST.md).
