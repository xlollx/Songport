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

## Try the beta

Songport is in open beta. Install it from [F-Droid](https://f-droid.org/packages/com.xlollx.songport/),
with [Obtainium](https://github.com/ImranR98/Obtainium) (add `https://github.com/xlollx/Songport`), or
as a signed APK from the [releases page](https://github.com/xlollx/Songport/releases/latest), and report
anything that does not work as described: *Settings → Diagnostics → Report an issue* opens a GitHub
issue with the diagnostic report filled in (versions, connectors, the last runs and log lines, never
tokens or passwords).

- [Guide](guide/): getting started, troubleshooting, reporting a problem
- [Source code and documentation](https://github.com/xlollx/Songport)
- [Connector plugins](plugins): extra services through separately installed apps
- [Privacy policy](privacy-policy)
- [Report a security issue](https://github.com/xlollx/Songport/security)
