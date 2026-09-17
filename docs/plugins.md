# Connector plugins

Songport talks to music services through their official APIs. Some services have no open API, or
make the official route heavy for a single user (a developer account, a paid program, a daily quota).
For those, Songport defines an **open connector interface**: a separate Android app, a *plugin*, can
hold the login to a service and answer Songport's requests for playlists and tracks. Songport does not
include, download or install plugins; it discovers one that is already installed and offers its
services in the account picker.

## How a plugin is discovered

A plugin declares an activity that handles the intent action `com.xlollx.songport.action.CONNECTOR`
and carries a `<meta-data>` entry named `com.xlollx.songport.connector.authority` with the authority of
its `ContentProvider`. Songport calls that provider with `ContentResolver.call()`; the methods and their
arguments are documented in the reference implementation below. The provider must verify the caller's
package and signing certificate before answering.

## Known plugins

### Songport Bridge

[Songport Bridge](https://github.com/xlollx/Songport-YTM-Bridge) is the reference plugin, by the same
author, free and open source (GPL-3.0). It is distributed on GitHub only, never on Google Play.
It adds:

- **YouTube Music** without a Google Cloud project and without quota.
- **Amazon Music**, which has no open API at all.
- **Spotify** and **Apple Music** sign-in without creating a developer app or paying for the Apple
  Developer Program (the official routes stay available in Songport).

Read this before installing: the Bridge uses the same web interfaces the services' own web players use,
not official APIs. That is against those services' terms of use, it can stop working whenever a service
changes something, and in the worst case a service could restrict the account you sign in with. The
Bridge shows a notice and asks for your acceptance before any sign-in. Your passwords are typed only on
the services' own pages and are never stored; the Bridge keeps the session cookies, encrypted on the
phone, and hands Songport only the data or short-lived tokens it needs.

Download: [latest release](https://github.com/xlollx/Songport-YTM-Bridge/releases/latest). Install the
APK, sign in to the services you want inside the Bridge, then add them in Songport from Accounts → +.
