# Songport

Android app that keeps playlists in sync across music services, manually or on a schedule.
Free, open source (GPL-3.0), no account, no server: everything runs on your phone.

## How Songport uses your data

Songport connects to the music services you choose (Spotify, YouTube Music, Apple Music, TIDAL,
Deezer, personal servers) with the official login of each service. It reads your playlists and
their tracks, and, when you start or schedule a sync, creates playlists and adds or removes tracks
in your own account. For YouTube Music this means the YouTube Data API with the `youtube`
permission, used only for your playlists and only on your device. Access tokens are stored
encrypted on the phone and never sent to Songport's developer or to any third party. There is no
Songport server. Details in the [privacy policy](privacy-policy).

- [Source code and documentation](https://github.com/xlollx/Songport)
- [Privacy policy](privacy-policy)
- [Report a security issue](https://github.com/xlollx/Songport/security)
