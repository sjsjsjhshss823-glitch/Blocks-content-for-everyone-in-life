#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker Desktop/Engine first." >&2
  exit 1
fi
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env. Review it before continuing."
fi
mkdir -p data media secrets
if [ ! -f secrets/client_secret.json ]; then
  echo "Missing secrets/client_secret.json. Add your Google OAuth client JSON locally; do not commit it." >&2
  exit 2
fi
echo "Starting SongFlow API. ACE-Step must already be running on the host at port 8001."
docker compose up --build -d
echo "Open http://localhost:8000/docs and complete /oauth/start before queueing a private test song."
