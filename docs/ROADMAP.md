# Roadmap

What other playlist tools offer and users ask for, checked against what Songport already does
(September 2026). Sources: the competitors' own feature pages and support articles (Soundiiz,
TuneMyMusic, FreeYourMusic, SongShift, MusConv, Playlisty, Houdini, the ytmusicapi-based CLIs),
store and Trustpilot reviews as quoted by review sites, and the issue trackers of the open-source
tools. Nothing here needs a Songport server; that is a fixed constraint.

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
- setlist.fm links (shared or pasted) and Shazam exports become playlists.
- ListenBrainz loved tracks writable with the user token.
- A source or target playlist deleted on the service (or unfollowed on Spotify) stops the sync with
  its name instead of feeding a ghost.
- "Extend with AI" on any playlist in the playlist manager: description or the spirit of what is
  there, review, then only what the service has is added.
- Connections log in Settings › Security, and a plain "where does the key go?" in the AI setup.
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
- YouTube Music already pauses on Google's abuse page with a doubling wait and a "verify" button;
  song results are preferred and videos are only a fallback.

## Next

1. **Last.fm loves** (needs the API secret for the session flow).
2. A throttled YouTube Music queue that resumes the next day by itself.

## Name

"SongPort" is also a universal music link converter (songport.link), unrelated to this project. The
app keeps its name on GitHub and F-Droid; if it ever goes to Google Play it will be published as
**Trackhop** (checked in September 2026: no app, repository or service with that name). The package
id `com.xlollx.songport` stays whatever the display name is.

Not planned: smart links with analytics, listening statistics dashboards, anything hosted.
