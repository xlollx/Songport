#!/usr/bin/env bash
# Self-hosted music servers in docker, all with the same 12-song library, for LiveServersIT:
#   scripts/live_servers.sh up [server...]     # prints the env to export, then
#   cd android && gradle :core:test --tests '*LiveServersIT*'
#   scripts/live_servers.sh down [server...]
# Servers: navidrome jellyfin (the default), gonic airsonic subsonic ampache jellyfin108 plex, or all.
# Needs docker, curl, python3 and ffmpeg. Data under $SONGPORT_IT_DIR (default /tmp/songport-it);
# containers are named $SONGPORT_IT_NAME-<server> (default songport-it), so two copies can coexist
# as long as their ports differ. Docker Hub images can come from a mirror: SONGPORT_IT_HUB=mirror.gcr.io/
#
# Accounts: admin / songport where a script can set the password (Navidrome, Jellyfin; Ampache: it),
# the server's default admin / admin otherwise (Gonic, Airsonic-Advanced, Subsonic). Plex runs
# unclaimed, never signed in to plex.tv, with every network allowed without a token: the
# connector's X-Plex-Token is then any string.
set -euo pipefail
DIR=${SONGPORT_IT_DIR:-/tmp/songport-it}
NAME=${SONGPORT_IT_NAME:-songport-it}
HUB=${SONGPORT_IT_HUB:-}
PASS=songport
# Every server on 127.0.0.1 only; a port can be moved with SONGPORT_IT_<SERVER>_PORT.
port() { local v="SONGPORT_IT_${1}_PORT"; echo "${!v:-$2}"; }
ND_PORT=$(port NAVIDROME 4533) JF_PORT=$(port JELLYFIN 8096) GONIC_PORT=$(port GONIC 4747) AIRSONIC_PORT=$(port AIRSONIC 4040)
SUBSONIC_PORT=$(port SUBSONIC 4041) AMPACHE_PORT=$(port AMPACHE 8090) JF108_PORT=$(port JELLYFIN108 8097) PLEX_PORT=$(port PLEX 32401)
ND=http://127.0.0.1:$ND_PORT
JF=http://127.0.0.1:$JF_PORT
ALL="navidrome jellyfin gonic airsonic subsonic ampache jellyfin108 plex"

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
wait_for() { for _ in $(seq 240); do curl -sf -H 'Accept: application/json' "$1" | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null && return; sleep 1; done; echo "timeout: $1" >&2; exit 1; }
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }
# Polls $1 until it prints 12 (songs indexed), running $2 now and then to ask for a scan. Prints the last count.
until_12() { local n=0; for i in $(seq 150); do n=$(eval "$1" 2>/dev/null || echo 0); [ "$n" = 12 ] && break; if [ $((i % 10)) = 5 ]; then eval "${2:-true}" >/dev/null 2>&1 || true; fi; sleep 2; done; echo "$n"; }
# Subsonic API, plain password: these scripted calls are not what is tested.
sub() { curl -s "$1/rest/$2?u=$3&p=$4&v=1.13.0&c=it&f=json${5:-}"; }
sub_songs() { sub "$1" getRandomSongs "$2" "$3" '&size=50' | json '["subsonic-response"].get("randomSongs",{}).get("song",[]).__len__()'; }
run() { local c=$1; shift; docker rm -f "$NAME-$c" >/dev/null 2>&1 || true; docker run -d --name "$NAME-$c" "$@" >/dev/null; }

# ------------------------------------------------------------------ Navidrome
up_navidrome() {
  mkdir -p "$DIR/nd"
  run nd -p "127.0.0.1:$ND_PORT:4533" -e ND_SCANSCHEDULE=1m -v "$DIR/media:/music:ro" -v "$DIR/nd:/data" "${HUB}deluan/navidrome:latest"
  wait_for "$ND/rest/ping?f=json"
  curl -s -o /dev/null -H 'Content-Type: application/json' -X POST "$ND/auth/createAdmin" -d "{\"username\":\"admin\",\"password\":\"$PASS\"}" || true
  local n; n=$(until_12 "sub $ND search3 admin $PASS '&query=&songCount=50' | json '[\"subsonic-response\"].get(\"searchResult3\",{}).get(\"song\",[]).__len__()'")
  echo "Navidrome $(sub $ND ping admin $PASS | json '["subsonic-response"].get("serverVersion")') ($n songs)" >&2
  echo "export SONGPORT_IT_NAVIDROME_URL=$ND SONGPORT_IT_NAVIDROME_USER=admin SONGPORT_IT_NAVIDROME_PASS=$PASS"
}

# ------------------------------------------------------------------ Jellyfin: latest on the host network, 10.8 on a port
jellyfin_setup() { # url container envprefix
  local url=$1 c=$2
  # Jellyfin answers, then restarts its listener while it loads: trust its own health check first.
  for _ in $(seq 180); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = healthy ] && break; sleep 1; done
  wait_for "$url/System/Info/Public"
  local auth='MediaBrowser Client="songport-it", Device="it", DeviceId="songport-it", Version="1"' wizard=None
  # The wizard state shows once the server has loaded; the startup calls fail before that.
  for _ in $(seq 60); do
    wizard=$(curl -s "$url/System/Info/Public" | json '.get("StartupWizardCompleted")' 2>/dev/null || echo None)
    [ "$wizard" != None ] && break; sleep 1
  done
  sleep 2
  if [ "$wizard" != True ]; then
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$url/Startup/Configuration" -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
    curl -sf -H "Authorization: $auth" "$url/Startup/User" >/dev/null
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$url/Startup/User" -d "{\"Name\":\"admin\",\"Password\":\"$PASS\"}"
    curl -sf -H "Authorization: $auth" -X POST "$url/Startup/Complete"
  fi
  local tok
  tok=$(curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST "$url/Users/AuthenticateByName" -d "{\"Username\":\"admin\",\"Pw\":\"$PASS\"}" | json '["AccessToken"]')
  auth="$auth, Token=\"$tok\""
  if ! curl -sf -H "Authorization: $auth" "$url/Library/VirtualFolders" | grep -q '"/music"'; then
    curl -sf -H "Authorization: $auth" -H 'Content-Type: application/json' -X POST \
      "$url/Library/VirtualFolders?name=Music&collectionType=music&paths=%2Fmusic&refreshLibrary=true" -d '{"LibraryOptions":{}}'
  fi
  # The first scan can run before the folder is readable and find nothing: ask again now and then.
  local n
  n=$(until_12 "curl -s -H 'Authorization: $auth' '$url/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0' | json '[\"TotalRecordCount\"]'" \
    "curl -s -o /dev/null -H 'Authorization: $auth' -X POST '$url/Library/Refresh'")
  echo "Jellyfin $(curl -s "$url/System/Info/Public" | json '["Version"]') ($n songs)" >&2
  echo "export SONGPORT_IT_$3_URL=$url SONGPORT_IT_$3_USER=admin SONGPORT_IT_$3_PASS=$PASS"
}
up_jellyfin() {
  mkdir -p "$DIR/jf/config" "$DIR/jf/cache"
  run jf -p "127.0.0.1:$JF_PORT:8096" -v "$DIR/media:/music:ro" -v "$DIR/jf/config:/config" -v "$DIR/jf/cache:/cache" "${HUB}jellyfin/jellyfin:latest"
  jellyfin_setup "$JF" "$NAME-jf" JELLYFIN
}
# 10.8 gives each copy of a song in a playlist its own entry id (10.9 shares them): the other removal path.
up_jellyfin108() {
  mkdir -p "$DIR/jf108/config" "$DIR/jf108/cache"
  run jf108 -p "127.0.0.1:$JF108_PORT:8096" -v "$DIR/media:/music:ro" -v "$DIR/jf108/config:/config" -v "$DIR/jf108/cache:/cache" "${HUB}jellyfin/jellyfin:10.8.13"
  jellyfin_setup "http://127.0.0.1:$JF108_PORT" "$NAME-jf108" JELLYFIN108
}

# ------------------------------------------------------------------ other Subsonic API servers
up_gonic() {
  local u=http://127.0.0.1:$GONIC_PORT; mkdir -p "$DIR/gonic/data" "$DIR/gonic/cache" "$DIR/gonic/podcasts" "$DIR/gonic/playlists"
  run gonic -p "127.0.0.1:$GONIC_PORT:80" -e GONIC_MUSIC_PATH=/music -e GONIC_PODCAST_PATH=/podcasts -e GONIC_PLAYLISTS_PATH=/playlists \
    -e GONIC_CACHE_PATH=/cache -e GONIC_DB_PATH=/data/gonic.db -e GONIC_SCAN_AT_START_ENABLED=true \
    -v "$DIR/media:/music:ro" -v "$DIR/gonic/data:/data" -v "$DIR/gonic/cache:/cache" -v "$DIR/gonic/podcasts:/podcasts" -v "$DIR/gonic/playlists:/playlists" \
    "${HUB}sentriz/gonic:latest"
  wait_for "$u/rest/ping?f=json"
  local n; n=$(until_12 "sub_songs $u admin admin" "sub $u startScan admin admin")
  echo "Gonic $(sub "$u" ping admin admin | json '["subsonic-response"].get("serverVersion")') ($n songs)" >&2
  echo "export SONGPORT_IT_GONIC_URL=$u SONGPORT_IT_GONIC_USER=admin SONGPORT_IT_GONIC_PASS=admin"
}
java_subsonic() { # name port image label envprefix: Airsonic-Advanced and the original Subsonic start alike
  local c=$1 u=http://127.0.0.1:$2; mkdir -p "$DIR/$c"
  # A container removed while running leaves its HSQLDB lock behind, and the next one would not start.
  docker rm -f "$NAME-$c" >/dev/null 2>&1 || true; rm -f "$DIR/$c"/db/*.lck
  if [ "$c" = airsonic ]; then
    run "$c" -p "127.0.0.1:$2:4040" -e PUID=0 -e PGID=0 -v "$DIR/media:/music:ro" -v "$DIR/$c:/config" "$3"
  else
    run "$c" -p "127.0.0.1:$2:4040" -e APP_UID=0 -e APP_GID=0 -v "$DIR/media:/music:ro" -v "$DIR/$c:/subsonic" "$3"
  fi
  # The servlet container answers long before the API does.
  local ok=
  for _ in $(seq 240); do sub "$u" ping admin admin | grep -q '"ok"' && ok=1 && break; sleep 1; done
  [ -n "$ok" ] || { echo "timeout: $u (docker logs $NAME-$c)" >&2; exit 1; }
  local n; n=$(until_12 "sub_songs $u admin admin" "sub $u startScan admin admin")
  echo "$4, API $(sub "$u" ping admin admin | json '["subsonic-response"]["version"]') ($n songs)" >&2
  echo "export SONGPORT_IT_$5_URL=$u SONGPORT_IT_$5_USER=admin SONGPORT_IT_$5_PASS=admin"
}
up_airsonic() { java_subsonic airsonic "$AIRSONIC_PORT" lscr.io/linuxserver/airsonic-advanced:latest Airsonic-Advanced AIRSONIC; }
# Subsonic 6.1, the original server (API 1.16.0): a fresh install runs its 30-day API trial.
up_subsonic() { java_subsonic subsonic "$SUBSONIC_PORT" "${HUB}hurricane/subsonic:latest" "Subsonic 6" SUBSONIC; }

# Ampache checks a Subsonic token against a separate "Subsonic password", which its CLI sets for
# non-admin users only: the user under test is "it" (the account password works only as p=).
up_ampache() {
  local u=http://127.0.0.1:$AMPACHE_PORT c="$NAME-ampache" cli; mkdir -p "$DIR/ampache/mysql" "$DIR/ampache/config"
  run ampache -p "127.0.0.1:$AMPACHE_PORT:80" -e MYSQL_PASS=mysqlpw -e DB_NAME=ampache -e DB_HOST=localhost -e DB_USER=admin -e DB_PASSWORD=mysqlpw \
    -e AMPACHE_DB_USER=ampache -e AMPACHE_DB_PASSWORD=ampachepw -e AMPACHE_ADMIN_USER=admin -e AMPACHE_ADMIN_PASSWORD=$PASS -e AMPACHE_ADMIN_EMAIL=admin@example.invalid \
    -v "$DIR/media:/media:ro" -v "$DIR/ampache/mysql:/var/lib/mysql" -v "$DIR/ampache/config:/var/www/config" "${HUB}ampache/ampache:latest"
  # The first start installs the database, then hands over to supervisord.
  for _ in $(seq 180); do docker logs "$c" 2>&1 | grep -q "supervisord started" && break; sleep 2; done
  for _ in $(seq 60); do sub "$u" ping admin $PASS | grep -q '"ok"' && break; sleep 2; done
  cli() { docker exec -u www-data "$c" php /var/www/bin/cli "$@" >/dev/null 2>&1; }
  if [ ! -f "$DIR/ampache/config/.songport-it" ]; then
    cli run:addCatalog music /media
    cli admin:addUser it -p $PASS -e it@example.invalid -l 25
    cli admin:updateUser -p $PASS it
    touch "$DIR/ampache/config/.songport-it"
  fi
  local n; n=$(until_12 "sub_songs $u it $PASS" "cli run:updateCatalog")
  echo "Ampache $(sub "$u" ping it $PASS | json '["subsonic-response"].get("serverVersion")') ($n songs)" >&2
  echo "export SONGPORT_IT_AMPACHE_URL=$u SONGPORT_IT_AMPACHE_USER=it SONGPORT_IT_AMPACHE_PASS=$PASS"
}

# ------------------------------------------------------------------ Plex
up_plex() {
  local u=http://127.0.0.1:$PLEX_PORT; mkdir -p "$DIR/plex/config" "$DIR/plex/transcode"
  # Unclaimed (no PLEX_CLAIM): it is never signed in to plex.tv, and every client counts as local.
  run plex -p "127.0.0.1:$PLEX_PORT:32400" -e ALLOWED_NETWORKS=0.0.0.0/0 -e TZ=UTC \
    -v "$DIR/media:/music:ro" -v "$DIR/plex/config:/config" -v "$DIR/plex/transcode:/transcode" "${HUB}plexinc/pms-docker:latest"
  wait_for "$u/identity"
  for _ in $(seq 60); do curl -sf -o /dev/null "$u/library/sections" && break; sleep 1; done
  if ! curl -s "$u/library/sections" | grep -q 'title="Music"'; then
    # The files' own tags: nothing is fetched from Plex's metadata servers.
    curl -sf -o /dev/null -X POST "$u/library/sections?name=Music&type=artist&agent=tv.plex.agents.none&scanner=Plex%20Music&language=xn&location=%2Fmusic"
  fi
  local n; n=$(until_12 "curl -s -H 'Accept: application/json' '$u/library/sections/1/all?type=10' | json '[\"MediaContainer\"][\"size\"]'" \
    "curl -s '$u/library/sections/1/refresh'")
  echo "Plex $(curl -s -H 'Accept: application/json' "$u/identity" | json '["MediaContainer"]["version"]') ($n songs)" >&2
  echo "export SONGPORT_IT_PLEX_URL=$u SONGPORT_IT_PLEX_TOKEN=songport-it"
}

# ------------------------------------------------------------------
container() { case $1 in navidrome) echo nd ;; jellyfin) echo jf ;; jellyfin108) echo jf108 ;; *) echo "$1" ;; esac; }
servers() {
  local s="${*:-navidrome jellyfin}" x
  [ "$s" = all ] && s=$ALL
  for x in $s; do case " $ALL " in *" $x "*) ;; *) echo "unknown server: $x" >&2; exit 2 ;; esac; done
  echo "$s"
}
up() { local s x; s=$(servers "$@"); library; for x in $s; do "up_$x"; done; }
down() { local s x; s=$(servers "${@:-all}"); for x in $s; do docker rm -f "$NAME-$(container "$x")" >/dev/null 2>&1 || true; done; }

cmd=${1:-}; shift || true
case "$cmd" in
  up) up "$@" ;;
  down) down "$@" ;;
  *) echo "usage: $0 up|down [all | $ALL]" >&2; exit 2 ;;
esac
