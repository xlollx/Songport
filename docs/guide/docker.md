# Trackhop on a server (Docker, alpha)

> **⚠️ Alpha.** The server is an early test version: it has had little real use, things can break,
> and settings or the data format can change between releases without a migration. Use it alongside
> the app rather than instead of it, keep a copy of the data volume, and
> [report](reporting-a-problem) what goes wrong.

The same sync engine as the Android app, running on a home server, a NAS or a small VPS, with a
web page to connect services, create syncs and see how they went. Scheduled syncs run there,
whether or not your phone is on.

## Start it

```sh
docker run -d --name trackhop \
  -p 8080:8080 \
  -v trackhop-data:/data \
  -e SONGPORT_URL=http://localhost:8080 \
  -e SONGPORT_PASSWORD=choose-one \
  ghcr.io/xlollx/trackhop:latest
```

Then open `http://localhost:8080` (any user name, the password you chose).

With Docker Compose:

```yaml
services:
  trackhop:
    image: ghcr.io/xlollx/trackhop:latest
    container_name: trackhop
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./trackhop-data:/data
    environment:
      SONGPORT_URL: http://localhost:8080
      SONGPORT_PASSWORD: choose-one
      # SONGPORT_LOCALE: it
```

The image runs on `amd64` and `arm64` (Raspberry Pi 4/5, most NAS). To build it yourself, from the
repository root: `docker build -f server/Dockerfile -t trackhop .`

**Coming from Songport?** Trackhop was called Songport up to version 1.1. The old image name,
`ghcr.io/xlollx/songport`, still works and gets every release with the same tags as
`ghcr.io/xlollx/trackhop`, so an existing setup keeps updating as it is. To switch, change only the
image name: keep your volume (or folder) and your settings. The variables keep their `SONGPORT_`
prefix and the log file its name `songport.log`, so nothing else needs to change.

## Settings

| Variable | Default | What it does |
|---|---|---|
| `SONGPORT_URL` | `http://localhost:8080` | The address your browser uses to reach Trackhop. Sign-ins come back to `<SONGPORT_URL>/callback`. |
| `SONGPORT_PASSWORD` | none | Asks for this password on every page. **Set it** whenever anyone else can reach the port. |
| `SONGPORT_LOCALE` | English | Language of the texts that come from the engine: `it`, `de`, `fr`, `es`, `pt-BR`, `pl`, `ru`, `tr`, `zh`, `ja`, `ko`. |
| `SONGPORT_PORT` | `8080` | Port inside the container. |
| `SONGPORT_DATA` | `/data` | Where everything is kept (the volume). |
| `SPOTIFY_CLIENT_ID`, `TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY` | none | Your keys, if you'd rather pass them here than type them in the page. |
| `DEEZER_REDIRECT_URL` | `<SONGPORT_URL>/callback` | Deezer's redirect, which must be `https`. |

Everything lives in the `/data` volume: `store.json` (syncs, reports, match cache, settings),
`tokens.json` (sign-ins, readable only by the container's user), `songport.log`, and `csv/` for
playlist files. Back that folder up and you have backed up Trackhop.

## Services

| Service | On the server | What you need |
|---|---|---|
| Spotify | yes | Your own key from developer.spotify.com |
| YouTube | yes | Your own Google OAuth client (with its secret) |
| TIDAL | yes | Your own key from developer.tidal.com |
| Deezer | yes (beta) | Your own app, and an `https` address |
| Navidrome / Subsonic, Jellyfin, Plex | yes | The server's address and your login |
| Last.fm, ListenBrainz | yes, as sources | Your user name |
| SoundCloud | yes, as a source (unofficial) | Nothing: pick it as the source of a new sync and paste a public set or likes link. [More](soundcloud) |
| CSV files | yes | Put them in `csv/` inside the data volume |
| YouTube Music | yes (unofficial, alpha) | The cookies of a browser where you are signed in: [see below](#youtube-music-unofficial) |
| Amazon Music, Spotify (web), Apple Music | no | See below |

**Why not all of them.** The other quick routes (Amazon Music, Spotify without a key, Apple Music
without a key) work by running the service's own web player inside the phone's browser engine:
there is no such browser on a server. Apple Music's official route signs in with Apple's MusicKit
JS, which needs one too. Keep those syncs in the app.

## YouTube Music (unofficial)

The server talks to music.youtube.com the way the app's built-in YouTube Music connector does,
through the web player's own endpoints, signed in with the cookies of a browser where you are
signed in. It is **not an official API**: Google can change it or block it at any time, and it is
an **alpha** on the server.

**Copy the cookies** (a desktop browser, Chrome, Edge or Firefox):

1. Open [music.youtube.com](https://music.youtube.com) and sign in with the account to sync.
2. Open the developer tools (<kbd>F12</kbd>, or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>I</kbd>; on a
   Mac <kbd>Cmd</kbd>+<kbd>Option</kbd>+<kbd>I</kbd>) and pick the **Network** tab.
3. Reload the page, type `browse` in the filter box and click one of the requests that appear (any
   request to `music.youtube.com` will do).
4. Under **Headers**, then **Request Headers**, find `cookie:`, right-click its value and copy it
   (in Firefox: *Raw* view, then select the value).
5. In Trackhop, **Services**, **YouTube Music (unofficial, alpha)**, paste it in *Cookies* and press
   **Connect**. The page checks them with one call before saving.

A `cookies.txt` export or the JSON export of a cookie-editor extension works too: paste the file's
content. Only YouTube's and Google's cookies are kept. Optionally paste your browser's user agent
as well (type `navigator.userAgent` in the developer tools' *Console*): requests then look like that
browser's.

**What to know before using it:**

- **They are as good as your password.** The cookies give full access to that Google account. They
  are stored only in the data volume (`tokens.json`, readable by its owner only) and never written
  to the log. Consider a separate Google account for syncing.
- **They end.** Signing out of that browser session, changing the password or Google rotating them
  ends the cookies: paste fresh ones. To keep them alive longer, copy them from a browser profile
  you then leave alone (a private window ends its cookies when it closes).
- **Google's block.** Without the app's hidden browser every call is plain HTTP, which Google's
  abuse detection ("Sorry… unusual traffic") stops sooner. When that happens Trackhop pauses
  YouTube Music for a few minutes, longer each time. Open music.youtube.com in a browser **on the same
  network as the server**, pass Google's check, then paste fresh cookies: that also lifts the pause.
- **Home beats datacenter.** Addresses of VPS and cloud providers are flagged far more often than a
  home connection: a server at home (a NAS, a Raspberry Pi) works best.

**Your own key.** The app's built-in keys only work with the app's own redirect (`songport://`),
so on the server every service that needs a key needs yours. Each service page in Trackhop lists
the steps and shows the exact redirect address to paste. The rules each service sets:

- **Spotify** accepts `http` only on `127.0.0.1`, otherwise `https`: use
  `SONGPORT_URL=http://127.0.0.1:8080` when you sign in from the server itself, or put Trackhop
  behind a reverse proxy with https.
- **Google (YouTube)**: create a *Web application* OAuth client, add `<SONGPORT_URL>/callback` as an
  authorized redirect URI, and paste both the client ID and the client secret. Google accepts `http`
  only for `localhost`.
- **Deezer** wants an `https` redirect.
- **TIDAL** takes the redirect you register.

Signing in from another computer than the server is fine, as long as `SONGPORT_URL` is the
address that computer's browser uses.

## Behind a reverse proxy

Point the proxy at port 8080 and set `SONGPORT_URL` to the public `https://` address. Trackhop
needs nothing else: no websockets, no special headers. `/health` answers `ok` without a password,
for health checks.

## How it runs

- Scheduled syncs (hourly, every 6 hours, daily, weekly) start on their own; the page also has
  *Run now*, *Pause* and *Stop*.
- A scheduled run whose two sides are unchanged since last time is skipped, as in the app.
- A scheduled run that finds no network is retried after 15 minutes.
- The log is in the page, in `docker logs trackhop` (or the name you gave the container), and in
  `songport.log`.

## Not there yet

The app's review screens (choosing a match for a track that wasn't found, confirming uncertain
matches), the playlist tools and backups are not in the web page yet: the server runs syncs and
reports what was not found.

[Back to the guide](./)
