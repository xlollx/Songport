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

## Next, in rough order of value

1. **Saved albums and followed artists** as objects to transfer and sync, next to playlists and
   liked songs. Done for Spotify, Deezer, Subsonic/Navidrome, Jellyfin and Apple Music albums
   (add only); still to do for YouTube Music (web protocol: liked albums and subscriptions) and
   TIDAL (user collections).
2. **Version policy** per sync: prefer studio over live or remix, explicit or clean, same album
   when available, stricter or looser threshold. Today the rules are fixed; the album tie-break
   is the first piece.
3. **Scheduled backup to a folder the user picks** (Storage Access Framework: SD card, Nextcloud,
   Drive, any DocumentsProvider) with one-tap restore, so the versioned backups also leave the
   phone.
4. **Description, cover and visibility** carried to the target playlist where the API allows
   (Spotify, YouTube, Deezer, Subsonic, Jellyfin), and kept updated by the sync.
5. **Cross-playlist duplicate finder**: tracks present in more than one playlist, and liked songs
   that are in no playlist.
6. **Scrobble and "love" writes** to Last.fm and ListenBrainz, so liked songs sync both ways with
   them and a playlist can be scrobbled.
7. **Richer sort keys** where the service exposes them: release date, date added, popularity,
   duration, BPM (Subsonic, Jellyfin, Plex tags; Deezer; Spotify).
8. **Setlist.fm and Shazam import**: a concert setlist or a Shazam CSV becomes a playlist.
9. **YouTube Music hardening**: detect Google's abuse page and say what to do, prefer song
   results over video results everywhere, keep a throttled queue that resumes the next day.
10. **Podcast subscriptions via OPML**, where the services allow following shows.

Not planned: smart links with analytics, listening statistics dashboards, anything hosted.
