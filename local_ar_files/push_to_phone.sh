#!/bin/sh
# Copies every file in ./paths onto the connected phone. Files with the same name are replaced; other paths on the
# phone stay. Use  ./push_to_phone.sh --replace  to delete all paths on the phone first.
# Needs a DEBUG build of the app installed (run it once first so its folder exists) and USB debugging on.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.app.androidnativearcore
DIR="$(cd "$(dirname "$0")" && pwd)"
"$ADB" shell am force-stop "$PKG"          # the app reads the files only when it starts
"$ADB" shell run-as "$PKG" mkdir -p files/paths
if [ "$1" = "--replace" ]; then
  "$ADB" shell "run-as $PKG sh -c 'rm -f files/paths/*.json'"
  echo "removed the paths on the phone"
fi
for file in "$DIR"/paths/*.json; do
  [ -e "$file" ] || { echo "No files in $DIR/paths"; exit 1; }
  name=$(basename "$file")
  "$ADB" shell "run-as $PKG sh -c 'cat > files/paths/$name'" < "$file" || { echo "Could not write $name"; exit 1; }
  echo "pushed $name ($(wc -c < "$file") bytes)"
done
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
echo "Done. Open the app and check the destination list."
