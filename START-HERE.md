# Notification Service — portable distribution

## Requirements on the target laptop
- **Java (JRE) 17 or newer.** That is the ONLY prerequisite.
  Check: `java -version`. Install if needed: macOS `brew install temurin`, or https://adoptium.net.
- No PostgreSQL, no Docker, no Maven needed to RUN — the JAR bundles a real embedded PostgreSQL 16
  for macOS (Apple Silicon + Intel) and Linux x64.

## Run
```bash
./run.sh                 # REST service on http://localhost:8080
./tui.sh                 # full-screen terminal dashboard
```
On first start it extracts the embedded Postgres and creates ./data/pg (persists across restarts).

## Demo
Open **http://localhost:8080** in a browser for the interactive dashboard (pipeline diagram, timezone status, query timing, recent-sends inspector).

Or via curl:
```bash
curl -sX POST "http://localhost:8080/internal/seed?consumers=1000000&historyFraction=0.3"
curl -sX POST "http://localhost:8080/internal/smart-load?limit=50000"   # pre-filters eligible consumers at DB layer
```

## Rebuild from source (optional; needs Maven + network the first time)
```bash
mvn -s build-settings.xml clean package
```
`build-settings.xml` pulls dependencies straight from Maven Central (bypasses any corporate mirror).

See README.md for the full API, configuration, and design notes.
