#!/usr/bin/env bash
# Trackhop Desktop (alpha): packs the server build into an app with its own Java runtime.
#
#   gradle -p server installDist && server/desktop/package.sh
#
# On Linux: dist/Trackhop-Desktop-v<version>-linux.deb and -linux.tar.gz (unpack and run
# Trackhop/bin/Trackhop, any distribution). On Windows (Git Bash, WiX 3 on the PATH):
# dist/Trackhop-Desktop-v<version>-windows.msi (per-user install, no administrator) and
# -windows.zip (portable). Needs a JDK 17 or newer with jpackage. The entry point is
# server/src/main/kotlin/.../Desktop.kt; the guide is docs/guide/desktop.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
version=$(tr -d '[:space:]' < server/VERSION)
built=server/build/install/songport-server/lib
[ -f "$built/songport-server-$version.jar" ] || { echo "Run 'gradle -p server installDist desktopLibs' first" >&2; exit 1; }
work=server/build/desktop
rm -rf "$work" && mkdir -p "$work" dist
name="Trackhop-Desktop-v$version"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) os=windows; icon=server/desktop/trackhop.ico ;;
  Linux) os=linux; icon=server/desktop/trackhop.png ;;
  *) echo "Windows or Linux only for now" >&2; exit 1 ;;
esac
# The jars, and SWT for this system: Desktop's own window (DesktopWindow.kt), not in the Docker image.
lib="$work/lib"
mkdir -p "$lib" && cp "$built"/*.jar "$lib"/
ls server/build/desktop-libs/$os/*.jar > /dev/null 2>&1 || { echo "Run 'gradle -p server desktopLibs' first" >&2; exit 1; }
cp server/build/desktop-libs/$os/*.jar "$lib"/

# What the jars use (jdeps --print-module-deps), plus TLS's elliptic curves, the XML the texts are
# read from, other charsets, and the formats of the app's languages.
modules=java.base,java.desktop,java.instrument,java.logging,java.net.http,java.xml,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata
locales=en,it,fr,de,es,pt,pl,ru,tr,zh,ja,ko

# The app itself: the jars, the launcher (no console window on Windows) and a trimmed runtime.
jpackage --type app-image --name Trackhop --dest "$work/out" \
  --input "$lib" --main-jar "songport-server-$version.jar" --main-class com.xlollx.songport.server.DesktopKt \
  --app-version "$version" --vendor xlollx --copyright "GPL-3.0-only" \
  --description "Trackhop Desktop (alpha): keeps playlists in sync between music services" \
  --icon "$icon" --java-options "-Xmx512m" \
  --add-modules "$modules" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --include-locales=$locales"

if [ "$os" = windows ]; then
  # PowerShell wants Windows paths, not Git Bash's /d/a/….
  zip=$(cygpath -w "$root/dist/$name-windows.zip")
  (cd "$work/out" && powershell -NoProfile -Command "Compress-Archive -Path Trackhop -DestinationPath '$zip' -Force")
  # The upgrade code stays the same forever: a newer MSI replaces the installed one.
  jpackage --type msi --app-image "$work/out/Trackhop" --name Trackhop --dest "$work" \
    --app-version "$version" --vendor xlollx --description "Trackhop Desktop (alpha)" \
    --win-per-user-install --win-menu --win-menu-group Trackhop --win-shortcut \
    --win-upgrade-uuid 5b8f3c1e-7a2d-4e9b-9c61-2f0d8a4b7e13 \
    --about-url https://xlollx.github.io/Trackhop/guide/desktop
  mv "$work"/Trackhop-*.msi "dist/$name-windows.msi"
else
  tar -C "$work/out" -czf "dist/$name-linux.tar.gz" Trackhop
  jpackage --type deb --app-image "$work/out/Trackhop" --name Trackhop --dest "$work" \
    --app-version "$version" --vendor xlollx --description "Trackhop Desktop (alpha)" \
    --linux-package-name trackhop --linux-shortcut --linux-menu-group "AudioVideo;Audio" \
    --linux-app-category sound \
    --linux-package-deps "libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37" --linux-deb-maintainer "xlollx@users.noreply.github.com" \
    --about-url https://xlollx.github.io/Trackhop/guide/desktop
  mv "$work"/trackhop_*.deb "dist/$name-linux.deb"
fi
ls -l dist
