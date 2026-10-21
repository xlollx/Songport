#!/usr/bin/env bash
# Turns a short screen recording of the app into the animated demo at the top of the README.
#
#   scripts/readme_demo.sh recording.mp4 [start-seconds] [length-seconds]
#
# Writes docs/store/demo.gif: 300 px wide, 12 frames per second, its own 128-colour palette, at most
# 12 seconds. Record on the phone (Samsung: quick settings, Screen recorder, no sound): open a sync,
# tap Run, let the live view fill, open the review. Ten seconds are enough.
set -euo pipefail
in=${1:?usage: scripts/readme_demo.sh recording.mp4 [start] [length]}
start=${2:-0}
len=${3:-12}
out="$(dirname "$0")/../docs/store/demo.gif"
command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
filters="fps=12,scale=300:-1:flags=lanczos"
ffmpeg -v error -y -ss "$start" -t "$len" -i "$in" \
  -filter_complex "[0:v]$filters,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  "$out"
echo "$out: $(du -h "$out" | cut -f1)"
echo 'README: <p align="center"><img src="docs/store/demo.gif" width="300" alt="A sync running"></p>'
