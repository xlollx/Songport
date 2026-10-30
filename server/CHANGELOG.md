# Trackhop Desktop & Server: changes

Docker (`ghcr.io/xlollx/trackhop`) and Trackhop Desktop share this version (`server/VERSION`) and are
released together as `server-v<version>`. The app keeps its own (`v1.x`), and the engine each
server version runs is the app's of the same commit, shown in the page footer. Up to the app's 1.3.8
the Docker image carried the app's version.

## 0.4.0

- **Backup & restore** in one place (Tools): your whole set-up (syncs, services added, matches you
  chose, ignored tracks, settings, File playlists; never sign-ins) saved to a .trackhop file and
  restored here or in the app (1.3.9) or the other Trackhop; the playlists backed up now or on a
  schedule; the backup folder also gets the set-up of the day.
- **Spotify and Deezer without a key** (unofficial, alpha): through their web players, signed in
  with the cookies of a browser where you are signed in, as YouTube Music already was.

## 0.3.0

- **Trackhop Desktop has its own window**: the pages in a window of their own with Trackhop's icon,
  on the system's web engine (Edge's WebView2 on Windows, WebKitGTK on Linux), no browser tab.
  Closing it leaves Trackhop in the tray, where scheduled syncs keep running; "Open in the browser"
  is in the tray's menu. Links to other sites open in your browser.
- **Playlists** tab: each service's playlists and their tracks; rename, delete, take tracks out; or
  tick several and copy them to another service at once (optionally kept in sync daily).
- **API and webhook** (Tools › API): a token for Home Assistant, n8n or scripts to list the syncs,
  start them and read their runs, and a webhook (HMAC-signed) called when a run ends.
- **Back** at the top of every inner page.
- **Report an issue on GitHub** from the Log page, with the version and the last lines of the log
  filled in.

## 0.2.0

The app's Tools on the page.

- **Full backup**: every playlist and the library of a service (or all of them) as dated File
  playlists, the last 5 versions kept; by hand, or every day or week; optionally copied to a folder.
- **Duplicates**: the tracks a playlist has more than once, and the extra copies removed.
- **Unplayable tracks**: the entries a service no longer plays, replaced by a playable version or
  removed, with an undo.
- **AI playlist**: your own AI (free keys from OpenRouter, Groq or Gemini, or a local model with
  Ollama, no key at all) writes a playlist from a description; Trackhop creates it on a service or
  in File.
- **Your numbers**: what the syncs did, as the app's Stats (Tools, or from the home page).
- **Buy me a coffee** in the footer: optional, nothing is unlocked.

## 0.1.0

The first release with its own version, and the first of Trackhop Desktop (alpha).

- **Trackhop Desktop** for Windows (MSI, no administrator needed, or a portable ZIP) and Linux (.deb
  or tar.gz): the engine and its web page as a program, with its own Java runtime, an icon next to
  the clock (open, start with the computer, quit) and nothing reachable from other computers.
- **The app's look**: its colours, cards and pill buttons, the services' marks, the brand beam, the
  syncs' colours (picked in a sync's settings), and **Sync all now** on the home page.
- **Review after a run**, as in the app: tracks not found (take the closest candidate, search by hand
  and pick, or ignore), uncertain matches (keep, or replace), tracks a mirror sync removed (put back).
- **File playlists**: upload a playlist file in any format Trackhop reads; download each one as CSV,
  M3U, XSPF, JSPF or plain text; a sync that writes to File has a **Download CSV** button.
- **Tools › Export a playlist**: any connected service's playlist to those formats, or into File.
- A sync can be renamed.
