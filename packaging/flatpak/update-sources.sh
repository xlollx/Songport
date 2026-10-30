#!/usr/bin/env bash
# Regenerates packaging/flatpak/gradle-sources.json: every Maven file the Desktop build needs (Gradle
# plugins, libraries, the Kotlin compiler, SWT for Linux) with its address and sha512, which
# flatpak-builder downloads into maven-local/ before the offline build (offline.init.gradle).
# Run it after a dependency or plugin version changes in server/, android/core or android/ytm, and
# copy the result to the Flathub repository with the manifest. Needs JDK 17+, Gradle 8.14, curl.
#
#   packaging/flatpak/update-sources.sh
#
# How: the same build as the Flatpak's (installDist desktopLibs) with an empty Gradle home, then
# every file that build downloaded, found on Maven Central or the Gradle Plugin Portal.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
home=$(mktemp -d)
trap 'rm -rf "$home"' EXIT
GRADLE_USER_HOME="$home" gradle -p "$root/server" installDist desktopLibs --no-daemon -q ${GRADLE_EXTRA_ARGS:-}
python3 - "$home/caches/modules-2/files-2.1" > "$root/packaging/flatpak/gradle-sources.json" <<'PY'
import hashlib, json, os, subprocess, sys, time
cache = sys.argv[1]
repos = ["https://repo.maven.apache.org/maven2", "https://plugins.gradle.org/m2"]

def exists(url):
    for attempt in range(8):
        code = subprocess.run(["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "-I", "-L", url],
                              capture_output=True, text=True).stdout.strip()
        if code in ("429", "000") or code.startswith("5"):
            time.sleep(5 * (attempt + 1)); continue
        return code == "200"
    sys.exit(f"no answer for {url}")

sources = []
for group in sorted(os.listdir(cache)):
    for module in sorted(os.listdir(f"{cache}/{group}")):
        for version in sorted(os.listdir(f"{cache}/{group}/{module}")):
            files = {}
            for sha1 in os.listdir(f"{cache}/{group}/{module}/{version}"):
                for name in os.listdir(f"{cache}/{group}/{module}/{version}/{sha1}"):
                    files[name] = f"{cache}/{group}/{module}/{version}/{sha1}/{name}"
            for name in sorted(files):
                path = f"{group.replace('.', '/')}/{module}/{version}"
                url = next((f"{r}/{path}/{name}" for r in repos if exists(f"{r}/{path}/{name}")), None)
                if url is None:
                    sys.exit(f"{path}/{name}: on neither Maven Central nor the Plugin Portal")
                sources.append({
                    "type": "file",
                    "url": url,
                    "sha512": hashlib.sha512(open(files[name], "rb").read()).hexdigest(),
                    "dest": f"maven-local/{path}",
                    "dest-filename": name,
                })
print(json.dumps(sources, indent=2))
PY
echo "packaging/flatpak/gradle-sources.json: $(grep -c '"url"' "$root/packaging/flatpak/gradle-sources.json") files"
