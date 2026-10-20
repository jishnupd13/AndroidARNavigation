#!/bin/sh
# Copies every path file the app saved on the connected phone into ./paths (one JSON file per path).
# Needs a DEBUG build of the app installed and USB debugging on. Open the app once first, so data an older
# version saved is split into per-path files.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.app.androidnativearcore
DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$DIR/paths"
files=$("$ADB" shell run-as "$PKG" ls files/paths 2>/dev/null | tr -d '\r')
[ -n "$files" ] || { echo "No path files on the phone (open the app once, and record or push a path)."; exit 1; }
for f in $files; do
  case "$f" in
    *.json)
      "$ADB" shell run-as "$PKG" cat "files/paths/$f" > "$DIR/paths/$f.tmp" && mv "$DIR/paths/$f.tmp" "$DIR/paths/$f" \
        && echo "pulled $f ($(wc -c < "$DIR/paths/$f") bytes)" ;;
  esac
done
