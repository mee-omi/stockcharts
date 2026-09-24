#!/usr/bin/env bash
# Back up the StockCharts database from the docker-compose stack.
#
#   scripts/backup-db.sh                 # writes backups/stockcharts-<UTC timestamp>.dump
#   KEEP=30 BACKUP_DIR=/mnt/nas scripts/backup-db.sh
#
# The dump is pg_dump's custom format (compressed, restorable with scripts/restore-db.sh).
# Schedule it with cron, e.g.:  0 2 * * *  cd /path/to/stockcharts && scripts/backup-db.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BACKUP_DIR=${BACKUP_DIR:-backups}
KEEP=${KEEP:-14}
DB=${POSTGRES_DB:-stockcharts}
DB_USER=${POSTGRES_USER:-stockcharts}
# How to run pg_dump; override to back up a database outside docker-compose (e.g. PG_EXEC="").
PG_EXEC=${PG_EXEC-docker compose exec -T timescaledb}

mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/stockcharts-$(date -u +%Y%m%d-%H%M%S).dump"

$PG_EXEC pg_dump -U "$DB_USER" -Fc "$DB" > "$file.partial"
if [ ! -s "$file.partial" ]; then
  echo "backup failed: empty dump" >&2
  rm -f "$file.partial"
  exit 1
fi
mv "$file.partial" "$file"

# Keep only the newest $KEEP backups.
ls -1t "$BACKUP_DIR"/stockcharts-*.dump | tail -n +"$((KEEP + 1))" | while read -r old; do rm -f -- "$old"; done

echo "$file ($(du -h "$file" | cut -f1))"
