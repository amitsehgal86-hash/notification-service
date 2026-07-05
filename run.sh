#!/usr/bin/env bash
#
# Plug-and-play launcher for the Notification Service.
# Requires only a JRE 17+. On first run the embedded PostgreSQL binary is extracted from the JAR
# and a data cluster is created under ./data/pg (persists across restarts).
#
set -euo pipefail
cd "$(dirname "$0")"

# --- locate the JAR (distribution root first, then a source build) ---------------------------
JAR=""
if [[ -f "notification-service.jar" ]]; then
  JAR="notification-service.jar"
elif [[ -f "target/notification-service.jar" ]]; then
  JAR="target/notification-service.jar"
else
  echo "No JAR found. Building it (needs Maven + network the first time)…"
  mvn -s build-settings.xml -B clean package -DskipTests
  JAR="target/notification-service.jar"
fi

# --- verify Java 17+ -------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java not found. Install a JRE 17 or newer (e.g. 'brew install temurin' or from adoptium.net)." >&2
  exit 1
fi
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
if [[ "$JV" -lt 17 ]]; then
  echo "ERROR: Java 17+ required, found Java $JV." >&2
  exit 1
fi

echo "Starting Notification Service on http://localhost:8080 (embedded Postgres on :54329)"
exec java -jar "$JAR" "$@"
