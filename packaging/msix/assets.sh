#!/usr/bin/env bash
# The MSIX's tiles and logos (Assets/), from the app icon docs/store/icon512.png (CC BY-SA 4.0).
# Run again with ImageMagick after the icon changes, and commit the result:
#   packaging/msix/assets.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
icon="$here/../../docs/store/icon512.png"
out="$here/Assets"
mkdir -p "$out"
for size in 44 50 150; do
  convert "$icon" -filter Lanczos -resize "${size}x${size}" -strip "PNG32:$out/logo-$size.png"
done
mv "$out/logo-44.png" "$out/Square44x44Logo.png"
mv "$out/logo-50.png" "$out/StoreLogo.png"
mv "$out/logo-150.png" "$out/Square150x150Logo.png"
# The wide tile: the icon in the middle of the splash screen's indigo.
convert -size 310x150 xc:'#1E1452' \( "$icon" -filter Lanczos -resize 120x120 \) -gravity center -composite \
  -strip "PNG32:$out/Wide310x150Logo.png"
ls -l "$out"
