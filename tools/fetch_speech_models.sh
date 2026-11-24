#!/usr/bin/env bash
# Fetch the offline speech models (Vosk, Apache 2.0) into app/src/main/assets/speech/<lang>.
# They are not kept in git (about 90 MB). tools/build.sh calls this when they are missing.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
cache=$here/.cache/speech
dest=$here/app/src/main/assets/speech
mkdir -p "$cache" "$dest"
fetch() { # <lang> <model name>
    [ -f "$dest/$1/MODEL_NAME" ] && [ "$(cat "$dest/$1/MODEL_NAME")" = "$2" ] && return 0
    [ -f "$cache/$2.zip" ] || curl -fsS -m 600 -o "$cache/$2.zip" "https://alphacephei.com/vosk/models/$2.zip"
    rm -rf "$dest/$1" "$cache/x"
    unzip -q "$cache/$2.zip" -d "$cache/x"
    mv "$cache/x/$2" "$dest/$1"
    rm -rf "$cache/x"
    echo "$2" > "$dest/$1/MODEL_NAME"
}
fetch de vosk-model-small-de-0.15
fetch en vosk-model-small-en-us-0.15
