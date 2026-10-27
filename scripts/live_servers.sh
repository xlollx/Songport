#!/usr/bin/env bash
# Navidrome and Jellyfin in docker, with the same 12-song library, for LiveServersIT:
#   scripts/live_servers.sh up      # prints the env to export, then
#   cd android && gradle :core:test --tests '*LiveServersIT*'
#   scripts/live_servers.sh down
# Needs docker and ffmpeg. Both servers: admin / songport, data under $DIR (default /tmp/songport-it).
set -euo pipefail
DIR=${SONGPORT_IT_DIR:-/tmp/songport-it}
PASS=songport
ND=http://127.0.0.1:4533
JF=http://127.0.0.1:8096

library() {
  # artist|album|year|ext|isrc|title — accents, "feat.", a remaster suffix, one title by two artists.
  while IFS='|' read -r artist album year ext isrc n title; do
    d="$DIR/media/$artist/$album"; mkdir -p "$d"; f="$d/$n $title.$ext"
    [ -f "$f" ] && continue
    tag=ISRC; [ "$ext" = mp3 ] && tag=TSRC
    ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=$((300 + 40 * 10#$n)):duration=8" -ac 1 \
      -metadata artist="$artist" -metadata album_artist="$artist" -metadata album="$album" -metadata title="$title" \
      -metadata track="$((10#$n))" -metadata date="$year" -metadata "$tag=$isrc" "$f"
  done <<'LIB'
Metallica|Master of Puppets|2020|mp3|USEE10001001|01|Battery
Metallica|Master of Puppets|2020|mp3|USEE10001002|02|Master of Puppets
Metallica|Master of Puppets|2020|mp3|USEE10001003|03|Orion (Remastered 2017)
Måneskin|Teatro d'ira|2020|mp3|ITB002100001|01|Zitti e buoni
Måneskin|Teatro d'ira|2020|mp3|ITB002100002|02|Coraline
Måneskin|Teatro d'ira|2020|mp3|ITB002100003|03|Home
Beyoncé|Renaissance|2019|flac|USSM12200001|01|Café Noir
Beyoncé|Renaissance|2019|flac|USSM12200002|02|Énergie (feat. Pharrell)
Beyoncé|Renaissance|2019|flac|USSM12200003|03|Home
Daft Punk|Discovery|2019|flac|GBDUW0000059|01|One More Time
Daft Punk|Discovery|2019|flac|GBDUW0000060|02|Digital Love
Daft Punk|Discovery|2019|flac|GBDUW0000061|03|Something About Us (feat. Romanthony)
LIB
}

# Until the URL answers JSON: Jellyfin serves a "starting up" page first.
wait_for() { for _ in $(seq 180); do curl -sf "$1" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null && return; sleep 1; done; echo "timeout: $1" >&2; exit 1; }
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

up() {
  library
  down; mkdir -p "$DIR/nd" "$DIR/jf/config" "$DIR/jf/cache"
  docker run -d --name songport-it-nd --network host -e ND_SCANSCHEDULE=1m \
    -v "$DIR/media:/music:ro" -v "$DIR/nd:/data" deluan/navidrome:latest >/dev/null
  docker run -d --name songport-it-jf --network host \
    -v "$DIR/media:/music:ro" -v "$DIR/jf/config:/config" -v "$DIR/jf/cache:/cache" jellyfin/jellyfin:latest >/dev/null

  wait_for "$ND/rest/ping?f=json"
  curl -s -o /dev/null -H 'Content-Type: application/json' -X POST "$ND/auth/createAdmin" -d "{\"username\":\"admin\",\"password\":\"$PASS\"}" || true

  # Jellyfin answers, then restarts its listener while it loads: trust its own health check first.
  for _ in $(seq 180); do [ "$(docker inspect -f '{{.State.Health.Status}}' songport-it-jf)" = healthy ] && break; sleep 1; done
  wait_for "$JF/System/Info/Public"
  local auth='MediaBrowser Client="songport-it", Device="it", DeviceId="songport-it", Version="1"' wizard=None
  # The wizard state shows once the server has loaded; the startup calls fail before that.
  for _ in $(seq 60); do
    wizard=$(curl -s "$JF/System/Info/Public" | json '.get("StartupWizardCompleted")' 2>/dev/null || echo None)
    [ "$wizard" != None ] && break; sleep 1
  done
  sleep 2
  if [ "$wizard" != True ]; then
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$JF/Startup/Configuration" -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
    curl -sf -H "Authorization: $auth" "$JF/Startup/User" >/dev/null
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$JF/Startup/User" -d "{\"Name\":\"admin\",\"Password\":\"$PASS\"}"
    curl -sf -H "Authorization: $auth" -X POST "$JF/Startup/Complete"
  fi
  local tok n=0 j=0
  tok=$(curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$JF/Users/AuthenticateByName" -d "{\"Username\":\"admin\",\"Pw\":\"$PASS\"}" | json '["AccessToken"]')
  auth="$auth, Token=\"$tok\""
  if ! curl -sf -H "Authorization: $auth" "$JF/Library/VirtualFolders" | grep -q '"/music"'; then
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST \
      "$JF/Library/VirtualFolders?name=Music&collectionType=music&paths=%2Fmusic&refreshLibrary=true" -d '{"LibraryOptions":{}}'
  fi

  # Both scans done: 12 songs on each. Jellyfin's first scan can run before the folder is
  # readable and find nothing: ask again now and then.
  for i in $(seq 180); do
    [ $((i % 10)) = 0 ] && [ "${j:-0}" = 0 ] && curl -s -o /dev/null -H "Authorization: $auth" -X POST "$JF/Library/Refresh"
    n=$(curl -s "$ND/rest/search3?u=admin&p=$PASS&v=1.16.1&c=it&f=json&query=&songCount=50" | json '["subsonic-response"].get("searchResult3",{}).get("song",[]).__len__()' 2>/dev/null || echo 0)
    j=$(curl -s -H "Authorization: $auth" "$JF/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0" | json '["TotalRecordCount"]' 2>/dev/null || echo 0)
    [ "$n" -ge 12 ] && [ "$j" -ge 12 ] && break
    sleep 2
  done
  echo "Navidrome $(curl -s "$ND/rest/ping?u=admin&p=$PASS&v=1.16.1&c=it&f=json" | json '["subsonic-response"].get("serverVersion")') ($n songs), Jellyfin $(curl -s "$JF/System/Info/Public" | json '["Version"]') ($j songs)" >&2
  cat <<ENV
export SONGPORT_IT_NAVIDROME_URL=$ND SONGPORT_IT_NAVIDROME_USER=admin SONGPORT_IT_NAVIDROME_PASS=$PASS
export SONGPORT_IT_JELLYFIN_URL=$JF SONGPORT_IT_JELLYFIN_USER=admin SONGPORT_IT_JELLYFIN_PASS=$PASS
ENV
}

down() { docker rm -f songport-it-nd songport-it-jf >/dev/null 2>&1 || true; }

case "${1:-}" in up) up ;; down) down ;; *) echo "usage: $0 up|down" >&2; exit 2 ;; esac
