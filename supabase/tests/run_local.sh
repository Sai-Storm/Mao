#!/usr/bin/env bash
# Runs the migrations + RLS/RPC test suite against a throwaway local Postgres.
# Usage: PGPORT=54329 supabase/tests/run_local.sh   (needs initdb/pg_ctl/psql on PATH)
set -euo pipefail
cd "$(dirname "$0")/../.."
PORT="${PGPORT:-54329}"; DATA="$(mktemp -d)/pgdata"
initdb -D "$DATA" -U postgres -A trust -E UTF8 >/dev/null
pg_ctl -D "$DATA" -o "-p $PORT" -l "$DATA.log" -w start >/dev/null
trap 'pg_ctl -D "$DATA" -m fast stop >/dev/null; rm -rf "$DATA"' EXIT
psql -p "$PORT" -U postgres -qc "create database mao"
for f in supabase/tests/00_supabase_stub.sql supabase/migrations/*.sql; do
  psql -p "$PORT" -U postgres -d mao -v ON_ERROR_STOP=1 -q -f "$f" 2>&1 | grep -v -i "wal_level\|HINT" || true
done
for f in supabase/tests/[1-9]*_tests.sql; do
  echo "== $f"
  psql -p "$PORT" -U postgres -d mao -q -f "$f" 2>&1 | grep -E "^(PASS|FAIL)|passed|ERROR"
done
