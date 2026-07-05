#!/usr/bin/env bash
#
# Launch the bundled terminal UI (full-screen dashboard).
# Same JAR, no web server — drives the embedded DB + services directly.
# Requires only a JRE 17+ and a real terminal (TTY).
#
set -euo pipefail
cd "$(dirname "$0")"

JAR=""
if [[ -f "notification-service.jar" ]]; then
  JAR="notification-service.jar"
elif [[ -f "target/notification-service.jar" ]]; then
  JAR="target/notification-service.jar"
else
  echo "Building JAR first…"
  mvn -s build-settings.xml -B clean package -DskipTests
  JAR="target/notification-service.jar"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java not found. Install a JRE 17 or newer." >&2
  exit 1
fi

exec java -jar "$JAR" tui
