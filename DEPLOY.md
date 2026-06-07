# Reckon — Production Deployment Guide

This guide covers deploying the full Reckon stack (app + Postgres + Kafka + Redis) on a single VM using Docker Compose. This is the pragmatic portfolio deploy — real managed services (RDS/MSK/ElastiCache) are the production-grade alternative noted at the end.

---

## Prerequisites

- A VM/VPS with Docker 24+ and Docker Compose V2 (`docker compose` not `docker-compose`)
- Minimum: 2 vCPU, 4 GB RAM (Kafka + app + Postgres all on one VM)
- Inbound firewall: port 8080 (app), optionally 5432/9092/6379 for direct access
- Docker Hub or a private registry accessible from the VM (or build on the VM directly)

---

## Step 1: Set required secrets

```bash
export JWT_SECRET="$(openssl rand -base64 48)"   # 64-char random — store this safely
export DB_PASSWORD="$(openssl rand -hex 16)"
# Optionally:
export DB_USER=reckon
export OTEL_EXPORTER_OTLP_ENDPOINT=""  # e.g. http://otel-collector:4317
```

> The app **refuses to boot** under the `prod` profile if `JWT_SECRET` equals the dev default or is too short. This is enforced at startup.

---

## Step 2: Build the image (or pull from registry)

```bash
# Build locally on the VM (requires source):
docker build -t reckon:local .

# Or pull from your registry:
# docker pull ghcr.io/your-org/reckon:latest
# docker tag ghcr.io/your-org/reckon:latest reckon:local
```

---

## Step 3: Start the stack

```bash
docker compose -f docker-compose.prod.yml up -d
```

This starts Postgres, Redis, Kafka, and the app. The app waits for all dependencies to be healthy before starting (via `depends_on: condition: service_healthy`).

---

## Step 4: Verify the app is healthy

```bash
# Poll until the app responds:
curl -s http://localhost:8080/actuator/health | jq .

# Expected:
# { "status": "UP" }

# Check container statuses:
docker compose -f docker-compose.prod.yml ps
```

---

## Step 5: Run the load test (optional smoke test)

```bash
cd loadtest
# Requires k6: https://k6.io/docs/get-started/installation/
k6 run script.js
```

---

## Observability

The observability stack (Prometheus + Grafana + Jaeger) is in `observability/`. Start it alongside:

```bash
docker compose -f observability/docker-compose.yml up -d
```

Prometheus scrapes `:8080/actuator/prometheus`. Grafana is at `:3000` (default admin/admin). Jaeger traces are at `:16686`.

---

## Updating

```bash
docker build -t reckon:local .
docker compose -f docker-compose.prod.yml up -d --no-deps app
```

---

## Managed-services alternative (real prod)

For a production-grade deployment, replace the compose services with managed equivalents:

| Compose service | AWS managed alternative |
|---|---|
| `postgres` | Amazon RDS for PostgreSQL |
| `kafka` | Amazon MSK (Managed Kafka) |
| `redis` | Amazon ElastiCache for Redis |

Point the app via environment variables (`DB_URL`, `KAFKA_BOOTSTRAP`, `REDIS_HOST`) — the app code requires no changes. The single-VM compose is the pragmatic choice for a portfolio project: it demonstrates the full operational model without cloud account dependencies.

---

## Stopping

```bash
docker compose -f docker-compose.prod.yml down       # keep volumes (data intact)
docker compose -f docker-compose.prod.yml down -v    # destroy volumes (data lost)
```
