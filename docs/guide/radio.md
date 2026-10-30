---
title: Songs from a radio, in a playlist
description: Save the songs an internet radio plays, in the hours you choose, to a playlist on Spotify, YouTube Music, Deezer, TIDAL, Navidrome or any other service, with Trackhop. Free and open source.
---

# Songs from a radio, in a playlist

A **radio sync** listens to an internet radio in the hours you choose and saves the songs it plays
to a playlist on any service Trackhop connects to: the morning show's music in your Spotify, a
station's evening mix on YouTube Music, a whole weekend of KEXP in Navidrome.

## How it works

Most internet radios, and the streams of FM stations, send the title of the song on air with the
audio (Icecast and Shoutcast "ICY" metadata). Trackhop reads only that title; the audio is never
kept. Each new song is noted once in a File playlist of its own, *Radio - &lt;station&gt;* (the
station's own announcements, adverts and news left out), and the sync takes the songs from there to
your playlist, matched like any sync: every 10 minutes while new songs arrive, and once more when
the window closes.

## Step by step

1. *Syncs → New sync*, and pick **Radio** as the source.
2. **Find the station** by name (from [radio-browser.info](https://www.radio-browser.info), a free
   directory of some 50,000 radios), or paste its stream's address and give it a name.
3. **Pick the target**: the service, and a new playlist (*&lt;station&gt; radio*) or one of yours.
4. **Pick the days and the hours.** The same time twice listens all day; an end before the start
   runs past midnight (Friday 22:00–02:00 listens into Saturday night).
5. **Save.** The card wears a radio and shows the days and hours; while it listens, it says what is
   on air.

## On the phone

The phone does not keep the radio playing for hours: **every 2 minutes** in the window it opens the
stream, reads up to the first title (a few seconds, a few tens of KB) and closes it. Songs last three
minutes and more, so this catches nearly all of them for a few megabytes an hour instead of about
60 for the whole stream.

- **Wi‑Fi only** is on for a new radio sync: on mobile data the card says *Waiting for Wi‑Fi*. Turn
  it off in the sync's options to listen on mobile data too.
- While a window is open, a quiet notification says *Listening to &lt;station&gt;* and the last song.
- To start on time with the screen off, Android needs Trackhop allowed to set **alarms and
  reminders** (Android 14 and later ask you; the editor shows the way). Without it, Trackhop starts
  listening when you open it, or from a *Tap to listen* notification.
- Android 15 allows this kind of background work **6 hours a day**: past that, listening stops, the
  songs heard so far are synced and the card says so, with *Listen again*. Otherwise it starts
  again at the next window.

## On Desktop and Docker

The web page has **New radio sync** on the home page, with the same steps; the server listens
continuously in the window ([Docker guide](docker#radio-syncs)).

## Tips

- *This stream sends no song titles*: try another of the station's streams (an MP3 or AAC one rather
  than HLS), or another station.
- Songs Trackhop could not find with certainty on the target go to the **review**, as in any sync.
- The songs heard so far are in *Files*, in the *Radio - &lt;station&gt;* playlist.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
