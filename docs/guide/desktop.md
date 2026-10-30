# Trackhop Desktop (Windows, Linux; beta)

> **Beta.** Trackhop Desktop works for everyday use but is still young: pages and settings can change
> between releases. Keep a copy of the data folder (or save the set-up from *Tools › Backup &
> restore*) and [report](reporting-a-problem) what goes wrong. The unofficial connectors stay an alpha.

The same sync engine as the Android app, as a program on your computer, with nothing else to
install: no Docker, no Java. It runs in the background with an icon next to the clock, shows its
pages in your browser, and runs scheduled syncs while the computer is on. It is the
[Docker version](docker) packed as an app, so what works there works here, and the other way round.

## Install

The current version is **0.5.3** ([what's in it](https://github.com/xlollx/Trackhop/releases/tag/server-v0.5.3)); every version is in the
[Desktop & Server releases](https://github.com/xlollx/Trackhop/releases?q=server-v&expanded=true)
(tagged `server-v…`: Desktop and Docker have their own version, separate from the app's).

| System | Download | How |
|---|---|---|
| Windows 10 or 11 | [Installer (.msi)](https://github.com/xlollx/Trackhop/releases/download/server-v0.5.3/Trackhop-Desktop-v0.5.3-windows.msi) | Open it. It installs for your user only, no administrator needed, with a Start menu entry. |
| Windows, no install | [Portable (.zip)](https://github.com/xlollx/Trackhop/releases/download/server-v0.5.3/Trackhop-Desktop-v0.5.3-windows.zip) | Unzip anywhere (Documents, a USB stick) and run `Trackhop\Trackhop.exe`. |
| Debian, Ubuntu, Mint… | [.deb](https://github.com/xlollx/Trackhop/releases/download/server-v0.5.3/Trackhop-Desktop-v0.5.3-linux.deb) | `sudo apt install ./Trackhop-Desktop-v0.5.3-linux.deb`, then *Trackhop* in the menu. |
| Any other Linux (x86-64) | [.tar.gz](https://github.com/xlollx/Trackhop/releases/download/server-v0.5.3/Trackhop-Desktop-v0.5.3-linux.tar.gz) | Unpack and run `Trackhop/bin/Trackhop`. |

**Package managers and stores.** Scoop has Trackhop Desktop already; the others are on their way,
and their commands do not work until the *(soon)* goes away here:

| Where | Command |
|---|---|
| winget (Windows) | `winget install xlollx.Trackhop` *(soon, in review)* |
| Chocolatey (Windows) | `choco install trackhop` *(soon, in review)* |
| Scoop (Windows) | `scoop bucket add xlollx https://github.com/xlollx/scoop-bucket`, then `scoop install xlollx/trackhop` |
| Microsoft Store (Windows) | *(soon)* |
| Flathub (Linux) | `flatpak install flathub io.github.xlollx.Trackhop` *(soon)* |

**Windows warns about an unrecognised app** ("Windows protected your PC"): the installer is not
signed with a paid certificate, as is usual for open-source projects. Choose *More info*, then
*Run anyway*. The files are built by GitHub from this repository's code, in the
[Desktop workflow](https://github.com/xlollx/Trackhop/actions/workflows/desktop.yml).

To update, install the newer file over the old one: your syncs and sign-ins stay.

## Use it

Start **Trackhop**: its window opens (from 0.3.0; it uses the system's web engine, Edge's WebView2
on Windows and WebKitGTK on Linux). Add services, create syncs, run them, as in the
[Docker guide](docker#services): the pages are the same. The same pages are also at
`http://127.0.0.1:8642` in any browser on this computer (*Open in the browser* in the tray's menu).
Closing the window leaves Trackhop running in the tray, for the scheduled syncs.

The icon next to the clock has **Open Trackhop**, **Open in the browser**, **Start with the
computer** (it then starts hidden when you sign in, and scheduled syncs keep running) and **Quit**.
Starting Trackhop again while it runs just brings its window up. Some Linux desktops (GNOME without the *AppIndicator*
extension) have no icons there: use **Quit Trackhop** at the bottom of the page instead.

Only this computer can reach the page: it listens on `127.0.0.1` and asks for no password.

## Services

As on the [server](docker#services): Spotify, YouTube and TIDAL with your own key; Navidrome and
other Subsonic servers, Jellyfin, Plex; Last.fm and ListenBrainz as sources; SoundCloud public
links; CSV files; YouTube Music with pasted cookies (unofficial, alpha). Deezer is not there yet on
the desktop (it wants an `https` redirect). Amazon Music, Spotify without a key and Apple Music
are not there yet either: they need a browser inside the app, which is the next step.

The **Microsoft Store** version offers only the official connections, the personal servers,
Last.fm, ListenBrainz and files (store rules, as for the app on Google Play): for the unofficial ones
(YouTube Music, Spotify and Deezer with cookies, SoundCloud links) use the installer or the ZIP.

**Your own key.** Register this redirect with the service: `http://127.0.0.1:8642/callback`
(Spotify and Google both accept plain `http` for `127.0.0.1`). The service's page in Trackhop
shows the exact address and the steps.

## Command line

Desktop comes with `trackhop-cli`, to run syncs, read their results and add or remove tracks from a
terminal, a script or Task Scheduler while Trackhop is running: `trackhop-cli run --all --wait`.
Where it is and every command: [the trackhop command](cli).

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
