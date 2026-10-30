#!/usr/bin/env bash
# Prepares phone screenshots for the Google Play listing.
#
# Play accepts JPEG/PNG from 320 to 3840 px per side, with the long side at most twice the short one.
# Long scrolling captures (the 1080x3755 kind) are refused: this crops each image to 2:1 at most,
# keeping the top, and writes the result next to the original under play/.
#
#   scripts/play_screenshots.sh fastlane/metadata/android/en-US/images/phoneScreenshots/*.jpg
#
# Take the screenshots from the play build, on a phone without the
# connector plugins installed, so that nothing in the picture names a plugin.
set -euo pipefail
command -v convert >/dev/null || { echo "ImageMagick (convert) is required" >&2; exit 1; }
for f in "$@"; do
  dir="$(dirname "$f")/play"; mkdir -p "$dir"
  w=$(identify -format "%w" "$f"); h=$(identify -format "%h" "$f")
  if (( h > 2 * w )); then
    convert "$f" -gravity North -crop "${w}x$((2 * w))+0+0" +repage -quality 92 "$dir/$(basename "$f")"
    echo "$(basename "$f"): ${w}x${h} -> ${w}x$((2 * w))"
  else
    cp "$f" "$dir/"
    echo "$(basename "$f"): ${w}x${h} kept"
  fi
done
