#!/usr/bin/env bash
# Deterministic size/format exports only; approved source artwork remains untouched.
set -euo pipefail
brand_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
brand_sources="$brand_root/assets/brand"
brand_resources="$brand_root/app/src/main/res/drawable-nodpi"
command -v magick >/dev/null || { echo 'ImageMagick 7 is required to export brand assets.' >&2; exit 1; }
mkdir -p "$brand_resources"
magick "$brand_sources/copenhagen-logo-original.png" -resize 384x384 -strip \
    -define webp:lossless=true "$brand_resources/copenhagen_logo.webp"
magick "$brand_sources/copenhagen-launcher-padded.png" -resize 432x432 -strip \
    -define webp:lossless=true "$brand_resources/copenhagen_launcher_artwork.webp"
magick "$brand_sources/copenhagen-logo-original.png" -resize 960x960 -strip \
    -define webp:lossless=true "$brand_resources/copenhagen_splash.webp"
