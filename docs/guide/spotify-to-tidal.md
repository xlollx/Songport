---
title: Move Spotify playlists to TIDAL
description: Copy Spotify playlists and liked songs to TIDAL with exact ISRC matching, and keep them in sync. Free, open source, on Android.
---

# Move Spotify playlists to TIDAL

Trackhop copies Spotify playlists and liked songs to TIDAL and can keep them in sync. TIDAL and
Spotify both publish the recording's ISRC code, so most tracks are matched **exactly**, not guessed.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer, or
  [Trackhop Desktop](desktop) / [Docker](docker) with your own Spotify and TIDAL keys.
- A TIDAL subscription and a free TIDAL developer key: the app shows each step, it takes about two
  minutes.

## Step by step

1. **Connect Spotify** (*Accounts → Add connector → Spotify*).
2. **Connect TIDAL** and follow the steps to create your key on TIDAL's developer site.
3. *Syncs → New sync*: source *Spotify* and a playlist or *Liked songs*, target *TIDAL* and *create
   a new playlist*. For many playlists at once: *Tools → Transfer many playlists*.
4. **Preview**, run, and check the **review** for anything TIDAL does not have.

## Good to know

- Tracks TIDAL does not carry stay in the review list, with suggestions; nothing is added at random.
- A scheduled sync keeps the TIDAL copy up to date as your Spotify playlist changes, and **mirror
  removals** follows deletions too.
- To keep a copy outside both services, *Tools → Export a playlist* saves it as CSV or M3U.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
