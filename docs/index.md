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

Songport is in open beta. Install the signed APK from the
[releases page](https://github.com/xlollx/Songport/releases/latest) (or the Google Play open-testing
track once listed) and report anything that does not work as described with an
[issue](https://github.com/xlollx/Songport/issues/new/choose), attaching the technical details report
from the app (Settings → Sync log → Share technical details). It contains versions and the last
sync steps, never tokens or track lists.

- [Source code and documentation](https://github.com/xlollx/Songport)
- [Connector plugins](plugins): extra services through separately installed apps
- [Privacy policy](privacy-policy)
- [Report a security issue](https://github.com/xlollx/Songport/security)
