#!/usr/bin/env bash
# The server's end-to-end test (server/e2e/e2e.js): builds the image as CI does, starts Navidrome and
# Jellyfin (scripts/live_servers.sh) with one more song and a few test playlists, runs the container on a fresh
# volume, drives the page with Playwright, then removes everything it started.
#   server/e2e/run.sh                 # all phases
#   server/e2e/run.sh auth sync       # some phases (see the list at the end of e2e.js)
# Needs docker, ffmpeg, node with Playwright (require('playwright')) and Chromium. Not run in CI.
# SONGPORT_E2E_SHOTS=<dir> also saves a screenshot of each page at desktop and phone width.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export SONGPORT_IT_DIR=${SONGPORT_IT_DIR:-/tmp/songport-it}
PORT=${SONGPORT_E2E_PORT:-18080}
NAME=songport-e2e
IMAGE=${SONGPORT_E2E_IMAGE:-songport-e2e}
PASSWORD='e2e pässword'

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$NAME-data" >/dev/null 2>&1 || true
  # The library goes back to the 12 songs of LiveServersIT.
  [ -n "${SONGPORT_E2E_KEEP_SERVERS:-}" ] || { "$ROOT/scripts/live_servers.sh" down; rm -rf "${SONGPORT_IT_DIR:?}/media/<img src=x onerror=alert('artist')>"; }
}
trap cleanup EXIT

[ -n "${SONGPORT_E2E_NO_BUILD:-}" ] || docker build -f "$ROOT/server/Dockerfile" -t "$IMAGE" "$ROOT"
# One more song, with markup in its artist and title: added once the library of 12 is up (the test's
# first phase waits for both servers to see it and makes the playlists it syncs).
A="<img src=x onerror=alert('artist')>"
rm -rf "${SONGPORT_IT_DIR:?}/media/$A"
eval "$("$ROOT/scripts/live_servers.sh" up)"
mkdir -p "$SONGPORT_IT_DIR/media/$A/XSS Album"
f="$SONGPORT_IT_DIR/media/$A/XSS Album/01 xss.mp3"
[ -f "$f" ] || ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=900:duration=8" -ac 1 -metadata artist="$A" \
  -metadata album_artist="$A" -metadata album="XSS Album" -metadata title="<script>window.__xss=1</script>Pwn \"quoted\" & 'single'" \
  -metadata track=1 -metadata date=2021 -metadata TSRC=XXXSS2100001 "$f"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker volume rm "$NAME-data" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --network host -e SONGPORT_PORT="$PORT" -e SONGPORT_PASSWORD="$PASSWORD" \
  -e SONGPORT_URL="http://127.0.0.1:$PORT" -v "$NAME-data:/data" "$IMAGE" >/dev/null
for _ in $(seq 60); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 1; done

SONGPORT_E2E_URL="http://127.0.0.1:$PORT" SONGPORT_E2E_PASSWORD="$PASSWORD" SONGPORT_E2E_CONTAINER="$NAME" \
  node "$ROOT/server/e2e/e2e.js" "$@"
