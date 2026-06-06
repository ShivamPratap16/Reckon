#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> starting postgres + kafka"
docker compose up -d
echo "==> waiting for postgres"; sleep 8

echo "==> building + starting app"
./gradlew bootJar -q
KAFKA_BOOTSTRAP=localhost:9092 java -jar build/libs/reckon-0.1.0.jar > /tmp/reckon-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

echo "==> waiting for app health"
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then echo "app up"; break; fi
  sleep 2
done

# seed a payee, resolve its userId from the DB for the k6 script
PAYEE_EMAIL="k6-payee-$(date +%s)@x.com"
curl -sf -XPOST http://localhost:8080/auth/signup -H 'Content-Type: application/json' \
  -d "{\"email\":\"$PAYEE_EMAIL\",\"password\":\"pw123456\"}" >/dev/null
PGPASSWORD=reckon
PAYEE_ID=$(docker compose exec -T postgres psql -U reckon -d reckon -tAc \
  "SELECT id FROM users WHERE email='$PAYEE_EMAIL'")
echo "==> payee=$PAYEE_ID"

echo "==> running k6"
PAYEE_ID="$PAYEE_ID" VUS="${VUS:-20}" DURATION="${DURATION:-30s}" k6 run loadtest/transfer-load.js

echo "==> post-run reconciliation (must be clean: no unbalanced txns, no balance drift)"
UNBAL=$(docker compose exec -T postgres psql -U reckon -d reckon -tAc \
  "SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)<>0) x")
DRIFT=$(docker compose exec -T postgres psql -U reckon -d reckon -tAc \
  "SELECT count(*) FROM (SELECT a.id FROM accounts a LEFT JOIN ledger_entries le ON le.account_id=a.id GROUP BY a.id,a.balance HAVING a.balance<>COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount WHEN 'DEBIT' THEN -le.amount END),0)) y")
echo "unbalanced_txns=$UNBAL  balance_drifts=$DRIFT"
test "$UNBAL" = "0" && test "$DRIFT" = "0" && echo "RECONCILIATION CLEAN ✅" || { echo "RECONCILIATION FAILED ❌"; exit 1; }
