#!/bin/sh
# Trackhop Desktop in the Flatpak (io.github.xlollx.Trackhop.yml): the server build on the Flatpak's
# Java runtime, with SWT for its window. SWT's native libraries are unpacked into the app's own cache
# (the sandbox cannot write ~/.swt); the window and the tray use X11 (Xwayland on Wayland), since
# Java's AWT, which shows the dialogs and the fallback tray, has no Wayland backend.
export GDK_BACKEND=x11
exec /app/jre/bin/java -Xmx512m \
  -Dswt.library.path="${XDG_CACHE_HOME:-$HOME/.cache}/swt" \
  -cp '/app/lib/trackhop/*' com.xlollx.songport.server.DesktopKt "$@"
