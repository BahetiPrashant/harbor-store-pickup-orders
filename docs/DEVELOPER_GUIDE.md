# Developer guide

## Prerequisites

- Java 21 and Maven 3.9 or later
- Python 3.12
- Node.js 22 or later and npm
- Go 1.22 or later only if running the Go worker outside Compose
- Docker Compose only for the containerized demo or secure deployment

## Local development

The simplest local setup uses the Java embedded scheduler. Start these three services in separate terminals from the repository root:

**Terminal 1 — order service** (default development settings, seeded users, in-memory order storage):

```powershell
cd order-service
mvn -s maven-settings.xml spring-boot:run
```

**Terminal 2 — intake service:**

```powershell
cd intake-service
py -3.12 -m pip install -r requirements.txt
py -3.12 -m uvicorn app.main:app --host 127.0.0.1 --port 8081
```

**Terminal 3 — web app:**

```powershell
cd web
npm ci
npm run dev
```

Open `http://localhost:3000/login`.

| Account | Email | Local password |
|---|---|---|
| Customer | `ada@harbor.example` | `local-dev-password` |
| Operator | `ops@harbor.example` | `local-dev-password` |

Local Java promotes due orders every 15 seconds after a 30-second eligibility delay. Local orders are held in memory and are lost when Java restarts. Catalog prices come from `catalog/catalog.json`.

Stop each foreground service with `Ctrl+C` in its terminal.

### Optional Go worker locally

By default, the embedded Java scheduler is enabled. If you want to run the Go worker instead, stop Java and restart it with `ORDERFLOW_EMBEDDED_PROMOTE=false` set in its terminal, then use a fourth terminal:

```powershell
cd status-worker
$env:ORDER_API_URL = "http://localhost:8080"
$env:INTERNAL_TOKEN = "local-dev-internal-token-32bytes"
$env:INTERVAL = "15s"
go run ./cmd/worker
```

Do not run both promoters at once during local development. The API compare-and-set protects against duplicate transitions, but one promoter makes local behavior easier to follow.

If port 8080 is already occupied, start Java with:

```powershell
mvn -s maven-settings.xml "-Dspring-boot.run.arguments=--server.port=18080" spring-boot:run
```

Set `ORDER_API_URL` to `http://localhost:18080` for the Go worker and web app. Before starting Next.js, also set `INTAKE_API_URL` to the intake service address, for example:

```powershell
$env:ORDER_API_URL = "http://localhost:18080"
$env:INTAKE_API_URL = "http://localhost:8081"
npm run dev
```

## Compose

For a local review/demo with seeded accounts, run from the repository root:

```powershell
docker compose -f deploy/compose/compose.demo.yaml up --build
```

The demo binds the web app and Java API to localhost and uses in-memory Java storage. It does not require a `.env` file.

The secure production-shaped Compose file enables the `prod` profile, uses H2 file storage on the named `order-data` volume, disables seeded accounts and the embedded scheduler, and runs the Go worker every five minutes. It is a single-node configuration; do not share its H2 file between multiple Java replicas.

## Production users and secrets

The production profile loads users from `ORDERFLOW_USERS_FILE`. The file is mounted read-only and must contain Argon2id password hashes; plaintext passwords are not stored. From `order-service`, generate a hash in an interactive terminal:

```powershell
mvn -s maven-settings.xml org.codehaus.mojo:exec-maven-plugin:3.5.0:java '-Dexec.mainClass=io.orderflow.PasswordHashTool'
```

Enter a password with at least 12 characters when prompted. Store the complete output in a JSON record, for example:

```json
[
  {"email":"operator@example.com","id":"user-operator","role":"OPERATOR","passwordHash":"$argon2id$..."},
  {"email":"customer@example.com","id":"user-customer","role":"CUSTOMER","customerId":"customer-001","customerName":"Example Customer","passwordHash":"$argon2id$..."}
]
```

Create `deploy/compose/users.local.json` with complete hashes. Keep it and all secret values outside version control. From the repository root, set independent random secrets of at least 32 bytes and the absolute path to the user file:

```powershell
$env:JWT_SECRET = (py -c "import secrets; print(secrets.token_urlsafe(48))")
$env:INTERNAL_TOKEN = (py -c "import secrets; print(secrets.token_urlsafe(48))")
$env:ORDERFLOW_DB_PASSWORD = (py -c "import secrets; print(secrets.token_urlsafe(48))")
$env:ORDERFLOW_USERS_FILE_HOST = (Resolve-Path .\deploy\compose\users.local.json).Path.Replace('\', '/')
docker compose -f deploy/compose/compose.yaml up --build
```

The file-backed H2 store preserves orders across Java restarts on the named volume. Before using multiple order-service instances, replace it with a shared relational database and adapt the JDBC repository/schema. `ORDERFLOW_DB_URL` can override the H2 JDBC URL for local H2 testing; the supplied JDBC driver and adapter are H2-specific.

## Configuration reference

| Service | Settings |
|---|---|
| Java | `JWT_SECRET`, `INTERNAL_TOKEN`, `ORDERFLOW_SEED_USERS`, `ORDERFLOW_SEED_PASSWORD`, `ORDERFLOW_USERS_FILE`, `ORDERFLOW_PROMOTE_DELAY_MS`, `ORDERFLOW_EMBEDDED_PROMOTE`, `ORDERFLOW_PROMOTE_INTERVAL_MS`, `ORDERFLOW_DB_URL`, `ORDERFLOW_DB_USERNAME`, `ORDERFLOW_DB_PASSWORD` |
| Python | `CATALOG_PATH` |
| Go worker | `ORDER_API_URL`, `INTERNAL_TOKEN`, `ORDERFLOW_ENV`, `INTERVAL` |
| Next.js | `ORDER_API_URL`, `INTAKE_API_URL` |

## Tests and build

Run each command from the listed module directory:

```powershell
# order-service
mvn -s maven-settings.xml test

# intake-service
py -3.12 -m pytest

# status-worker
go test ./...

# web
npm ci
npm run build
```

GitHub Actions runs the Java, Python, Go, and web checks on pushes and pull requests. The assignment requirement mapping is in [reviewer brief](REVIEWER_BRIEF.md); architecture is in [HLD](HLD.md) and [LLD](LLD.md). AI tool use and review corrections are documented in [AI usage notes](AI_USAGE.md).
