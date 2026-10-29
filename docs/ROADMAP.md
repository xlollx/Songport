# Roadmap

What other playlist tools offer and users ask for, checked against what Trackhop already does
(September 2026). Sources: the competitors' own feature pages and support articles (Soundiiz,
TuneMyMusic, FreeYourMusic, SongShift, MusConv, Playlisty, Houdini, the ytmusicapi-based CLIs),
store and Trustpilot reviews as quoted by review sites, and the issue trackers of the open-source
tools. Nothing here needs a Trackhop server; that is a fixed constraint.

## Already covered

Unlimited, free, no account, on-device processing; ISRC-first matching with penalties for live,
karaoke, cover and sped-up versions; review of uncertain and not-found matches with manual search,
pasted links and the user's own AI; two-way sync; mirror removals opt-in with safety rules (empty
source, interrupted search, large unattended removals); scheduled syncs with retries and
notifications; liked songs as source and target (inserted oldest-first so the order matches);
batch transfer of many playlists; copy, merge, split, sort, shuffle, dedupe; rename and delete
playlists; versioned backups; CSV, M3U, XSPF, JSPF, iTunes XML, JSON and text in, five formats out;
shareable report of what was not found or matched loosely; multiple accounts per service; YouTube
quota meter; own API keys to escape shared quotas.

## Done since (September 2026)

- Saved albums, followed artists and podcasts as sync objects (Spotify, Deezer, Subsonic/Navidrome,
  Jellyfin; Apple Music albums, add only). OPML subscription files import as podcasts.
- Matching rules per sync: strictness, explicit or clean, same album, studio versions only.
- Backup to a folder of the user's choice, daily or weekly.
- The source playlist's description travels to the created target.
- Repeats across playlists and liked songs in no playlist, per service, with export.
- Sort by release year, date added and duration.
- setlist.fm links (shared or pasted) and Shazam exports become playlists; songs shared from Shazam go into a playlist on any service.
- ListenBrainz loved tracks writable with the user token.
- A source or target playlist deleted on the service (or unfollowed on Spotify) stops the sync with
  its name instead of feeding a ghost.
- "Extend with AI" on any playlist in the playlist manager: description or the spirit of what is
  there, review, then only what the service has is added.
- Connections log in Settings › Developer options (off by default, with the technical report and the traffic capture), and a plain "where does the key go?" in the AI setup.
- Spotify without Premium: the web route now talks to the player's own backend (GraphQL gateway,
  playlist store), since Spotify refuses first-party tokens on the public Web API (December 2025).
- Saved albums and subscribed artists on YouTube Music (the library tabs, as ytmusicapi reads
  them) and on TIDAL (user collections v2). Apple Music (web) removes tracks, renames and deletes
  playlists through the web player's backend, which the public API refuses.
- Exact recording for tracks without an ISRC: Deezer's public catalogue gives the ISRC once per
  track (anonymous, cached a week), so Spotify, Apple Music and TIDAL are asked for the recording,
  not a look-alike. Off in Settings. The match cache is shared across the routes and accounts of
  a service, and the Spotify web route reads only the query hash it needs.
- Scheduled syncs skip the run when neither side changed since the last one (Spotify snapshot id,
  Deezer checksum, TIDAL and Apple modification dates, the head of the liked list).
- Listening history as a source: Spotify's last fifty plays, YouTube Music's history, Last.fm and
  ListenBrainz recent listens, newest first, a track once.
- The source playlist's cover travels to a created Spotify playlist (Spotify and Deezer list
  covers; Spotify accepts one, under 256 KB).
- In the review, a track link from any service is resolved to the target service through song.link
  (Odesli), an extra on top of the target's own links.
- Amazon Music renames and deletes playlists, mapped from the desktop player's own calls.
- YouTube Music already pauses on Google's abuse page with a doubling wait and a "verify" button;
  song results are preferred and videos are only a fallback.

- Keep the source order: after adding, the target is moved into the source's order where the
  service has a move (Spotify on both routes, YouTube Music, Deezer); the fewest moves, computed in
  pure Kotlin and tested.
- A track the target already holds under another id (remaster, single edition) is not added twice:
  the target index answers by ISRC and name after the search as well.
- Not-found tracks for which the target returned no candidate at all are marked and listed last.
- "Test the connection" in each account's menu; the preview opens right after a sync is created.
- A single track shared from a service's app goes into one of your playlists; several
  "Artist - Title" lines shared as text become a file playlist. Short share links resolve.
- The AI generator checks each proposal on the destination before the review and asks for more
  until the count is met; the count is typed, any size, in rounds above a hundred.
- The sync engine runs in JUnit against in-memory services; a daily canary workflow makes the
  anonymous calls the app depends on (Spotify registry and player page, short links, Apple's web
  token, Deezer, song.link, YouTube Music and Amazon Music pages) and fails loudly. Its first run
  found song.link answering 401 to anonymous calls: the review's song.link extra is quietly off
  until that API opens again or a key scheme is added.

## Next

1. **Last.fm loves** (needs the API secret for the session flow).
2. A throttled YouTube Music queue that resumes the next day by itself.

## Getting the word out

Listings to ask for once the F-Droid page is live (each is a pull request or a form, done once):

- [x] **F-Droid** badge in the README (added before the page went live: check that https://f-droid.org/packages/com.xlollx.songport/ answers).
- [ ] **Obtainium's app list** (github.com/ImranR98/apps.obtainium.imranr.dev): a pull request adding the app's JSON entry.
- [ ] **Navidrome**: the apps page of the documentation (navidrome/website repository), as a tool that syncs streaming playlists into Navidrome.
- [ ] **Jellyfin**: the third-party clients list on jellyfin.org (jellyfin/jellyfin.org repository), and awesome-jellyfin.
- [ ] **Awesome lists on GitHub**: offa/android-foss, pcqpcq/open-source-android-apps (Multimedia), and any awesome-subsonic or self-hosted music list; read each one's contributing rules first.
- [ ] **AlternativeTo**: listed as an alternative to Soundiiz, TuneMyMusic and FreeYourMusic.
- [ ] **IzzyOnDroid** repository (request on its GitLab issue tracker).
- [ ] Posts: r/fossdroid, r/androidapps, r/navidrome, r/jellyfin, r/selfhosted (its weekly thread), Lemmy c/android and c/fdroid, Mastodon #FDroid; Show HN once.
- [x] A short demo GIF at the top of the README (`docs/store/demo.gif`; `scripts/readme_demo.sh recording.mp4` makes a new one).
- [ ] Repository topics and social preview image (Settings: About, and Social preview with `docs/store/feature_en.png`).

## Name

The app is now **Trackhop** everywhere: the GitHub and F-Droid build, the Google Play build, this
repository (`xlollx/Trackhop`), the site (`xlollx.github.io/Trackhop`), the docs and the Docker
image (`ghcr.io/xlollx/trackhop`). Up to version 1.1 it was called Songport outside Google Play.
The reason: "SongPort" (songport.link) is an unrelated, active service in the same niche, a
universal music link converter, and two products with one name confuse users and search results.
Trackhop was checked in September 2026: no app, repository or service with that name. Since both
builds now carry the same name, the generated Play brand strings (`scripts/play_strings.py`) are gone.

What existing installs and setups depend on keeps the old name, on purpose: the package id
`com.xlollx.songport` and the `songport://callback` redirect (registered in users' own developer
apps), the Deezer redirect page `xlollx.github.io/Songport/deezer-redirect.html` (served from the
owner's user-site repository), the release asset names `Songport-vX.apk` / `Songport-vX-play.aab`
(F-Droid's `Binaries:` pattern), the server's `SONGPORT_*` variables, and the image
`ghcr.io/xlollx/songport`, still published next to the new one. The companion app is
now Trackhop Bridge (1.4.0), with the same package id and repository address.

Not planned: smart links with analytics, listening statistics dashboards, anything hosted.
