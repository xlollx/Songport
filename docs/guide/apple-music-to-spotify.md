---
title: Transfer Apple Music playlists to Spotify
description: Move your Apple Music playlists and library to Spotify, or keep them in sync, with a free open-source Android app. No Mac or iPhone needed.
---

# Transfer Apple Music playlists to Spotify

Leaving Apple Music, or using both? Trackhop moves your Apple Music playlists to Spotify and, if you
like, **keeps them in sync** afterwards. It runs on an Android phone: no Mac, no iPhone, no paid transfer
service. (Apple Music signs in through a web page inside the app, so this one is phone-only: Trackhop
Desktop and Docker do not reach Apple Music.)

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer.
- Your Apple Music subscription and a Spotify account (free or Premium).

## Step by step

1. **Connect Apple Music.** *Accounts → Add connector → Apple Music.* The quick route signs you in
   with your Apple ID on Apple's own page; nothing else to set up.
2. **Connect Spotify**, by the quick route or with a free developer key of your own.
3. **One playlist:** *Syncs → New sync*, source *Apple Music* and the playlist, target *Spotify* and
   *create a new playlist*. **Many at once:** *Tools → Transfer many playlists* sends a whole
   selection in one go.
4. **Check the preview**, run it, and look at the **review** for the few tracks that need a choice.

## Good to know

- Matching uses the recording's ISRC code where both services have it, so most tracks land exactly;
  the rest are matched on title, artists and duration.
- Your **library** (songs added to Apple Music, not in a playlist) can be the source too, as can
  your saved albums.
- Keep the sync scheduled and new songs you add on Apple Music follow to Spotify by themselves.

## Other directions

Spotify to Apple Music works the same way, as do YouTube Music, TIDAL, Deezer, Amazon Music and your
own Navidrome, Jellyfin or Plex server: any source to any target.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
