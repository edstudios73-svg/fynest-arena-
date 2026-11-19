#!/usr/bin/env bash
# Recreate a throwaway local database, start SmartAttend and run the browser end-to-end test.
set -euo pipefail
DB=${E2E_DB:-smartattend_e2e}
su postgres -c "psql -qc 'DROP DATABASE IF EXISTS $DB WITH (FORCE)' -c 'CREATE DATABASE $DB OWNER smartattend'" >/dev/null
mvn -q package -DskipTests
DATABASE_URL=jdbc:postgresql://localhost:5432/$DB DATABASE_PASSWORD=${DATABASE_PASSWORD:-devpass} \
SMARTATTEND_SUPERADMIN_EMAIL=super@smartattend.test SMARTATTEND_SUPERADMIN_PASSWORD='Initial-Pass-2026!' \
  java -jar target/smartattend-1.0.0.jar > target/e2e-app.log 2>&1 &
APP=$!
trap 'kill $APP' EXIT
for i in $(seq 60); do curl -sf localhost:8080/ >/dev/null && break; sleep 1; done
PSQL="psql postgresql://smartattend:${DATABASE_PASSWORD:-devpass}@localhost/$DB" node tests-e2e/e2e.mjs
