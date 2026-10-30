---
title: Your Shazams in a playlist
description: Every song you Shazam, automatically in a playlist of your choice on YouTube Music, Deezer, TIDAL, Spotify, Navidrome or any other service, with Trackhop. Free and open source.
---

# Your Shazams in a playlist

A **Shazam sync** puts every song you Shazam into a playlist of your choice, on any service
Trackhop connects to: YouTube Music, Deezer, TIDAL, Navidrome, Jellyfin, Plex, another Spotify
account, a playlist file… Shazam a song in a bar, and it is in your playlist by the next sync.

## How it works

Shazam has no way in for other apps: there is no public API for the songs you Shazam, and Trackhop
does not use unofficial ones. But Shazam itself can keep your Shazams on **Spotify** or **Apple
Music**: once connected in its settings, it adds every song you Shazam to a playlist there called
**"My Shazam Tracks"** (its name can be translated into your language, always with "Shazam" in it).
A Shazam sync reads that playlist through Spotify's or Apple Music's own connector and brings the
new songs to the playlist you chose.

## Step by step

1. **In the Shazam app**, open *Settings* and *Connect to Spotify* (or *Apple Music*). Shazam a
   song: "My Shazam Tracks" appears on that service.
2. **In Trackhop**, connect the same Spotify or Apple Music account in *Accounts*.
3. *Syncs → New sync*, and pick **Shazam** as the source. Trackhop finds "My Shazam Tracks" on your
   connected account and shows it, with how many songs it has. Not there yet? The editor says how
   to turn it on, with a *Check again* button.
4. **Pick the target**: the service, and a new playlist or one of yours.
5. **Save.** A Shazam sync runs **every hour** by default; change it in the options. Its card wears
   Shazam's mark.

Shared a song from Shazam to Trackhop? The sheet's *Sync my Shazams* opens the same editor.

## On Desktop and Docker

The web page has **New Shazam sync** on the home page: it finds "My Shazam Tracks" on the
connected Spotify (Apple Music signs in only in the app), then asks where to bring the songs. The
API lists such syncs with `"shazam": true`.

## Tips

- **Mirror removals** is off: a song you remove from your Shazams stays in the target. Turn it on
  to remove it there too.
- Songs Trackhop could not find with certainty on the target go to the **review**, as in any sync.
- Shazam's public charts ("Shazam Top 200" and the like) are not your Shazams: Trackhop leaves them
  out when it looks for your playlist.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
