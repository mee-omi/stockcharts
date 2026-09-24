#!/usr/bin/env bash
# Restore a dump made by scripts/backup-db.sh (or the Kubernetes db-backup CronJob) into the
# docker-compose database. Existing objects are dropped and recreated.
#
#   scripts/restore-db.sh backups/stockcharts-20260924-020000.dump
#
# With docker-compose the backend is stopped during the restore (live ticks would otherwise write
# into tables being recreated) and started again afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

file=${1:?usage: scripts/restore-db.sh <dump file>}
[ -s "$file" ] || { echo "no such dump: $file" >&2; exit 1; }
DB=${POSTGRES_DB:-stockcharts}
DB_USER=${POSTGRES_USER:-stockcharts}
if [ -z "${PG_EXEC+set}" ]; then
  PG_EXEC="docker compose exec -T timescaledb"
  MANAGE_BACKEND=${MANAGE_BACKEND:-1}
fi

if [ "${FORCE:-}" != "1" ]; then
  read -r -p "Replace the contents of database '$DB' with $file? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "aborted"; exit 1; }
fi

# TimescaleDB needs restore mode around pg_restore; on plain PostgreSQL these are no-ops.
timescale() {
  $PG_EXEC psql -q -U "$DB_USER" -d "$DB" -v ON_ERROR_STOP=1 -c \
    "DO \$\$ BEGIN PERFORM $1(); EXCEPTION WHEN undefined_function THEN NULL; END \$\$;"
}

if [ "${MANAGE_BACKEND:-0}" = "1" ]; then
  docker compose stop backend
  trap 'docker compose start backend' EXIT
fi

timescale timescaledb_pre_restore
$PG_EXEC pg_restore -U "$DB_USER" -d "$DB" --clean --if-exists --no-owner < "$file" || status=$?
timescale timescaledb_post_restore
if [ "${status:-0}" -ne 0 ]; then
  echo "pg_restore reported errors (exit ${status}); check the output above" >&2
  exit "$status"
fi
echo "restored $file into $DB"
