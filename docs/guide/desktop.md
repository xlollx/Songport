# Trackhop Desktop (Windows, Linux; alpha)

> **⚠️ Alpha.** Trackhop Desktop is an early test version: it has had little real use, things can
> break, and settings or the data format can change between releases without a migration. Keep a
> copy of the data folder and [report](reporting-a-problem) what goes wrong.

The same sync engine as the Android app, as a program on your computer, with nothing else to
install: no Docker, no Java. It runs in the background with an icon next to the clock, shows its
pages in your browser, and runs scheduled syncs while the computer is on. It is the
[Docker version](docker) packed as an app, so what works there works here, and the other way round.

## Install

Download from the [latest release](https://github.com/xlollx/Trackhop/releases/latest):

| System | File | How |
|---|---|---|
| Windows 10 or 11 | `Trackhop-Desktop-v….-windows.msi` | Open it. It installs for your user only, no administrator needed, with a Start menu entry. |
| Windows, no install | `Trackhop-Desktop-v….-windows.zip` | Unzip anywhere and run `Trackhop\Trackhop.exe`. |
| Debian, Ubuntu, Mint… | `Trackhop-Desktop-v….-linux.deb` | `sudo apt install ./Trackhop-Desktop-v….-linux.deb`, then *Trackhop* in the menu. |
| Any other Linux (x86-64) | `Trackhop-Desktop-v….-linux.tar.gz` | Unpack and run `Trackhop/bin/Trackhop`. |

**Windows warns about an unrecognised app** ("Windows protected your PC"): the installer is not
signed with a paid certificate, as is usual for open-source projects. Choose *More info*, then
*Run anyway*. The files are built by GitHub from this repository's code, in the
[Desktop workflow](https://github.com/xlollx/Trackhop/actions/workflows/desktop.yml).

To update, install the newer file over the old one: your syncs and sign-ins stay.

## Use it

Start **Trackhop**: the browser opens on `http://127.0.0.1:8642`. Add services, create syncs,
run them, as in the [Docker guide](docker#services); the page is the same.

The icon next to the clock has **Open Trackhop**, **Start with the computer** (it then starts
hidden when you sign in, and scheduled syncs keep running) and **Quit**. Starting Trackhop again
while it runs just opens the page. Some Linux desktops (GNOME without the *AppIndicator*
extension) have no icons there: use **Quit Trackhop** at the bottom of the page instead.

Only this computer can reach the page: it listens on `127.0.0.1` and asks for no password.

## Services

As on the [server](docker#services): Spotify, YouTube and TIDAL with your own key; Navidrome and
other Subsonic servers, Jellyfin, Plex; Last.fm and ListenBrainz as sources; SoundCloud public
links; CSV files; YouTube Music with pasted cookies (unofficial, alpha). Deezer is not there yet on
the desktop (it wants an `https` redirect). Amazon Music, Spotify without a key and Apple Music
are not there yet either: they need a browser inside the app, which is the next step.

**Your own key.** Register this redirect with the service: `http://127.0.0.1:8642/callback`
(Spotify and Google both accept plain `http` for `127.0.0.1`). The service's page in Trackhop
shows the exact address and the steps.

## Where things are

| | Windows | Linux |
|---|---|---|
| Data (syncs, sign-ins, log) | `%APPDATA%\Trackhop` | `~/.local/share/trackhop` |
| The program | `%LOCALAPPDATA%\Trackhop` (MSI) | `/opt/trackhop` (deb) |

Back up the data folder and you have backed up Trackhop. The log is `songport.log` there, and in
the page under *Log*.

**Settings** are the [server's variables](docker#settings), for whoever needs them: for example
`SONGPORT_PORT` if another program already uses port 8642 (then register the new address with the
services), or `SONGPORT_DATA` for another data folder. A password does not apply here.

## Remove it

Windows: *Settings → Apps → Trackhop → Uninstall*. Linux: `sudo apt remove trackhop`. The data
folder stays: delete it too to remove everything.

[Back to the guide](./)
