# Songport on a server (Docker, alpha)

> **⚠️ Alpha.** The server is an early test version: it has had little real use, things can break,
> and settings or the data format can change between releases without a migration. Use it alongside
> the app rather than instead of it, keep a copy of the data volume, and
> [report](reporting-a-problem) what goes wrong.

The same sync engine as the Android app, running on a home server, a NAS or a small VPS, with a
web page to connect services, create syncs and see how they went. Scheduled syncs run there,
whether or not your phone is on.

## Start it

```sh
docker run -d --name songport \
  -p 8080:8080 \
  -v songport-data:/data \
  -e SONGPORT_URL=http://localhost:8080 \
  -e SONGPORT_PASSWORD=choose-one \
  ghcr.io/xlollx/songport:latest
```

Then open `http://localhost:8080` (any user name, the password you chose).

With Docker Compose:

```yaml
services:
  songport:
    image: ghcr.io/xlollx/songport:latest
    container_name: songport
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./songport-data:/data
    environment:
      SONGPORT_URL: http://localhost:8080
      SONGPORT_PASSWORD: choose-one
      # SONGPORT_LOCALE: it
```

The image runs on `amd64` and `arm64` (Raspberry Pi 4/5, most NAS). To build it yourself, from the
repository root: `docker build -f server/Dockerfile -t songport .`

## Settings

| Variable | Default | What it does |
|---|---|---|
| `SONGPORT_URL` | `http://localhost:8080` | The address your browser uses to reach Songport. Sign-ins come back to `<SONGPORT_URL>/callback`. |
| `SONGPORT_PASSWORD` | none | Asks for this password on every page. **Set it** whenever anyone else can reach the port. |
| `SONGPORT_LOCALE` | English | Language of the texts that come from the engine: `it`, `de`, `fr`, `es`, `pt-BR`, `pl`, `ru`, `tr`, `zh`, `ja`, `ko`. |
| `SONGPORT_PORT` | `8080` | Port inside the container. |
| `SONGPORT_DATA` | `/data` | Where everything is kept (the volume). |
| `SPOTIFY_CLIENT_ID`, `TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY` | none | Your keys, if you'd rather pass them here than type them in the page. |
| `DEEZER_REDIRECT_URL` | `<SONGPORT_URL>/callback` | Deezer's redirect, which must be `https`. |

Everything lives in the `/data` volume: `store.json` (syncs, reports, match cache, settings),
`tokens.json` (sign-ins, readable only by the container's user), `songport.log`, and `csv/` for
playlist files. Back that folder up and you have backed up Songport.

## Services

| Service | On the server | What you need |
|---|---|---|
| Spotify | yes | Your own key from developer.spotify.com |
| YouTube | yes | Your own Google OAuth client (with its secret) |
| TIDAL | yes | Your own key from developer.tidal.com |
| Deezer | yes (beta) | Your own app, and an `https` address |
| Navidrome / Subsonic, Jellyfin, Plex | yes | The server's address and your login |
| Last.fm, ListenBrainz | yes, as sources | Your user name |
| CSV files | yes | Put them in `csv/` inside the data volume |
| YouTube Music, Amazon Music, Spotify (web), Apple Music | no | See below |

**Why not all of them.** The quick routes (YouTube Music, Amazon Music, Spotify without a key, Apple
Music without a key) work by running the service's own web player inside the phone's browser
engine: there is no such browser on a server. Apple Music's official route signs in with Apple's
MusicKit JS, which needs one too. Keep those syncs in the app.

**Your own key.** The app's built-in keys only work with the app's own redirect (`songport://`),
so on the server every service that needs a key needs yours. Each service page in Songport lists
the steps and shows the exact redirect address to paste. The rules each service sets:

- **Spotify** accepts `http` only on `127.0.0.1`, otherwise `https`: use
  `SONGPORT_URL=http://127.0.0.1:8080` when you sign in from the server itself, or put Songport
  behind a reverse proxy with https.
- **Google (YouTube)**: create a *Web application* OAuth client, add `<SONGPORT_URL>/callback` as an
  authorized redirect URI, and paste both the client ID and the client secret. Google accepts `http`
  only for `localhost`.
- **Deezer** wants an `https` redirect.
- **TIDAL** takes the redirect you register.

Signing in from another computer than the server is fine, as long as `SONGPORT_URL` is the
address that computer's browser uses.

## Behind a reverse proxy

Point the proxy at port 8080 and set `SONGPORT_URL` to the public `https://` address. Songport
needs nothing else: no websockets, no special headers. `/health` answers `ok` without a password,
for health checks.

## How it runs

- Scheduled syncs (hourly, every 6 hours, daily, weekly) start on their own; the page also has
  *Run now*, *Pause* and *Stop*.
- A scheduled run whose two sides are unchanged since last time is skipped, as in the app.
- A scheduled run that finds no network is retried after 15 minutes.
- The log is in the page, in `docker logs songport`, and in `songport.log`.

## Not there yet

The app's review screens (choosing a match for a track that wasn't found, confirming uncertain
matches), the playlist tools and backups are not in the web page yet: the server runs syncs and
reports what was not found.

[Back to the guide](./)
