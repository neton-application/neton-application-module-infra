#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
pgbin="${PG_BIN:-$(pg_config --bindir)}"
port="${NETON_STORAGE_TEST_PG_PORT:-55491}"
temp="$(mktemp -d -t neton-file-storage.XXXXXX)"
cleanup() {
  "$pgbin/pg_ctl" -D "$temp/data" -m immediate stop >/dev/null 2>&1 || true
  find "$temp" -depth -delete
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$pgbin/initdb" -D "$temp/data" -A trust -U storage_runner >/dev/null
"$pgbin/pg_ctl" -D "$temp/data" -l "$temp/postgres.log" -o "-h 127.0.0.1 -p $port -k $temp" -w start
export EVENT_DB_URI="postgresql://storage_runner:fixture@127.0.0.1:$port/postgres"
export NETON_STORAGE_TEST_ROOT="$temp/objects"
./gradlew postgresContractTest --console=plain
